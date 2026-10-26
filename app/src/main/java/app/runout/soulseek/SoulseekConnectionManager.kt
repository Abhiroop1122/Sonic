package app.runout.soulseek

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** How often events are fetched while something is under way, and while nothing is. */
internal const val POLL_ACTIVE_MS = 100L
internal const val POLL_IDLE_MS = 1_000L
/** Fast polls kept after the last event or command: about two seconds' worth. */
private const val ACTIVE_POLLS = 20

/** Owns exactly one native connection. Account identity survives temporary loss of that connection. */
internal class SoulseekConnectionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val factory: () -> SoulseekTransport,
    private val keepInBackground: () -> Boolean,
    private val receive: suspend (JSONObject, String) -> Unit,
    private val problem: (String) -> Unit,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    /** Whether a transfer, search or browse is waiting on events, so they are fetched quickly. */
    private val busy: () -> Boolean = { false },
    /** The port to listen on, kept between sessions so the router's forward stays good; 0 for any. */
    private val listenPort: () -> Int = { 0 },
) {
    private val vault = SoulseekCredentialVault(context)
    private val prefs = context.getSharedPreferences("soulseek-connection", Context.MODE_PRIVATE)
    private val lifecycle = Mutex()
    private val wire = Mutex()
    private var client: SoulseekTransport? = null
    private var credentials: Pair<String, String>? = null
    private var remember = false
    @Volatile private var desired = false
    @Volatile private var foreground = true
    @Volatile private var externalFlows = 0
    @Volatile private var network = true
    private var authenticated = false
    private var retries = 0
    private var retryJob: Job? = null
    private var idleJob: Job? = null
    @Volatile var generation = 0L
        private set
    /** Fast polls left before the loop slows down; reset by every event and command. */
    private val activePolls = AtomicInteger(ACTIVE_POLLS)
    /** Wakes a slow poll at once when a command goes out, so its answer isn't a second late. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** The wait the poll loop chose last; tests read it. */
    @Volatile internal var pollIntervalMs = POLL_ACTIVE_MS
        private set
    private val _session = MutableStateFlow(SoulseekSession())
    val session = _session.asStateFlow()
    private val _savedUsername = MutableStateFlow<String?>(null)
    val savedUsername = _savedUsername.asStateFlow()
    private fun trace(message: String) {
        if (app.runout.BuildConfig.DEBUG) android.util.Log.d("RunoutSession", message)
    }

    suspend fun initialize() {
        val pair = vault.load()
        _savedUsername.value = pair?.first
        if (pair != null && !prefs.getBoolean("autoConnect",true)) {
            credentials = pair; remember = true; authenticated = true
            _session.value = SoulseekSession(SoulseekConnection.PAUSED,pair.first,SoulseekDisconnectReason.MANUAL.label,true,SoulseekDisconnectReason.MANUAL)
        }
    }

    suspend fun signIn(username: String, password: String, save: Boolean) = lifecycle.withLock {
        detach()
        credentials = username to password; remember = save; desired = true; authenticated = false; retries = 0
        prefs.edit().putBoolean("autoConnect",true).apply()
        if (!save) { vault.clear(); _savedUsername.value = null }
        connectLocked()
    }
    suspend fun signInSaved() {
        val pair = vault.load() ?: error("No saved sign-in. Enter your Soulseek username and password.")
        signIn(pair.first, pair.second, true)
    }
    suspend fun reconnect() = lifecycle.withLock {
        if (credentials == null) {
            credentials = vault.load(); remember = credentials != null
        }
        check(credentials != null) { "Enter your Soulseek username and password." }
        desired = true; retries = 0
        prefs.edit().putBoolean("autoConnect",true).apply()
        detach(); connectLocked()
    }
    suspend fun disconnect(reason: SoulseekDisconnectReason, clearAccount: Boolean = false) = lifecycle.withLock {
        trace("disconnect reason=$reason clearAccount=$clearAccount")
        desired = reason == SoulseekDisconnectReason.BACKGROUND_IDLE
        if (!desired) prefs.edit().putBoolean("autoConnect",false).apply()
        detach()
        if (clearAccount) {
            credentials = null; remember = false; authenticated = false
            vault.clear(); _savedUsername.value = null
            _session.value = SoulseekSession()
        } else _session.value = if (credentials == null) SoulseekSession() else SoulseekSession(SoulseekConnection.PAUSED, credentials?.first, reason.label, authenticated, reason)
    }

    suspend fun command(op: String, body: JSONObject.() -> Unit = {}) {
        val request = JSONObject().put("op", op).apply(body).toString()
        activePolls.set(ACTIVE_POLLS); wake.trySend(Unit)
        val response = wire.withLock { client?.command(request) ?: error("Soulseek is offline. Reconnect to continue.") }
        val result = JSONObject(response)
        check(result.optBoolean("ok")) { result.optString("error", "Soulseek could not complete this action.") }
    }

    private suspend fun connectLocked() {
        val pair = credentials ?: return
        trace("connect network=$network authenticated=$authenticated generation=$generation")
        if (!network) { _session.value = SoulseekSession(SoulseekConnection.RECONNECTING, pair.first, "Waiting for a network connection", authenticated, SoulseekDisconnectReason.NETWORK_LOST); return }
        _session.value = SoulseekSession(if (authenticated) SoulseekConnection.RECONNECTING else SoulseekConnection.CONNECTING, pair.first, authenticated = authenticated)
        val current = try { factory() } catch (e: Exception) {
            problem("Soulseek could not start. Reconnect to retry."); scheduleRetry(); return
        }
        generation++
        val epoch = generation
        wire.withLock { client = current }
        try { command("login") { put("username", pair.first); put("password", pair.second); put("listenPort", listenPort()) } }
        catch (e: Exception) {
            detach(cancelRetry = false)
            _session.value = SoulseekSession(SoulseekConnection.RECONNECTING, pair.first, e.message, authenticated, SoulseekDisconnectReason.NETWORK_LOST)
            scheduleRetry(); return
        }
        trace("login queued generation=$epoch")
        scope.launch(worker) {
            var deadline = System.nanoTime() + 70_000_000_000L
            while (generation == epoch) {
                val batch = try {
                    wire.withLock { if (client === current) JSONArray(current.poll()) else JSONArray() }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    // A command/poll can fail just before native reports a terminal loss.
                    // Drain that reason before detaching, otherwise displacement becomes
                    // a retryable protocol error and kicks the other client back off.
                    val loss = try {
                        wire.withLock { if (client === current) JSONArray(current.poll()) else JSONArray() }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { JSONArray() }
                    val nativeLoss = (0 until loss.length()).mapNotNull { loss.optJSONObject(it) }
                        .firstOrNull { it.optString("type") == "session" && it.optString("state") in setOf("error", "disconnected") }
                    if (generation == epoch) sessionEvent(nativeLoss ?: JSONObject().put("type","session").put("state","error").put("reasonCode","PROTOCOL_ERROR").put("reason","The connection could not process a response"), pair.first, epoch)
                    JSONArray()
                }
                for (i in 0 until batch.length()) {
                    if (generation != epoch) break
                    try {
                        val event = batch.getJSONObject(i)
                        if (event.optString("type") == "session") {
                            if (!sessionEvent(event, pair.first, epoch)) break
                        } else lifecycle.withLock { if (generation == epoch) deliver(event, pair.first) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { trace("event rejected generation=$epoch") }
                }
                if (session.value.connected) deadline = Long.MAX_VALUE
                if (System.nanoTime() > deadline && generation == epoch) {
                    sessionEvent(JSONObject().put("type","session").put("state","error").put("reasonCode","NETWORK_LOST").put("reason","Soulseek connection timed out"), pair.first, epoch)
                }
                // Ten polls a second only while something is under way; an idle session, which
                // can stay online in the background for hours, asks once a second.
                if (batch.length() > 0) activePolls.set(ACTIVE_POLLS)
                val fast = activePolls.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0 || busy()
                pollIntervalMs = if (fast) POLL_ACTIVE_MS else POLL_IDLE_MS
                if (fast) delay(POLL_ACTIVE_MS)
                else withTimeoutOrNull(POLL_IDLE_MS) { wake.receive() }
            }
        }
    }

    private suspend fun deliver(event: JSONObject, owner: String) {
        try { receive(event, owner) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { trace("event handler rejected generation=$generation") }
    }

    private suspend fun sessionEvent(event: JSONObject, owner: String, epoch: Long): Boolean {
        val state = event.optString("state")
        var deliver = false
        lifecycle.withLock {
            if (generation != epoch) return false
            when (state) {
                "connecting" -> Unit
                "connected" -> {
                    trace("connected generation=$epoch")
                    authenticated = true; retries = 0
                    _session.value = SoulseekSession(SoulseekConnection.CONNECTED, owner, authenticated = true)
                    if (remember) runCatching { credentials?.let { vault.save(it.first,it.second); _savedUsername.value = it.first } }.onFailure { problem("Connected, but this phone could not securely remember the sign-in.") }
                    deliver = true
                }
                "error", "disconnected" -> {
                    val reason = runCatching { SoulseekDisconnectReason.valueOf(event.optString("reasonCode")) }.getOrDefault(SoulseekDisconnectReason.NETWORK_LOST)
                    trace("session loss reason=$reason generation=$epoch")
                    val text = event.optString("reason").ifBlank { reason.label }
                    detach()
                    val terminal = reason in setOf(SoulseekDisconnectReason.AUTH_REJECTED, SoulseekDisconnectReason.DISPLACED)
                    if (terminal) { desired = false; prefs.edit().putBoolean("autoConnect",false).apply() }
                    if (reason == SoulseekDisconnectReason.AUTH_REJECTED) authenticated = false
                    _session.value = SoulseekSession(if (terminal) SoulseekConnection.ERROR else SoulseekConnection.RECONNECTING, owner, text, authenticated, reason)
                    deliver = true
                    if (!terminal) scheduleRetry()
                }
            }
            if (deliver) deliver(event, owner)
        }
        return generation == epoch
    }

    private fun scheduleRetry(immediate: Boolean = false) {
        retryJob?.cancel()
        if (!desired || !network || !(foreground || externalFlows > 0 || keepInBackground())) return
        val waitMs = if (immediate) 0L else (1_000L shl retries.coerceAtMost(5)).coerceAtMost(30_000L)
        retries++
        trace("retry delayMs=$waitMs foreground=$foreground externalFlows=$externalFlows")
        retryJob = scope.launch(worker) {
            delay(waitMs)
            lifecycle.withLock {
                if (desired && network && (foreground || externalFlows > 0 || keepInBackground()) && !session.value.connected) {
                    detach(cancelRetry = false); connectLocked()
                }
            }
        }
    }
    private suspend fun detach(cancelRetry: Boolean = true) {
        generation++
        if (cancelRetry) { retryJob?.cancel(); retryJob = null }
        wire.withLock { client?.let { runCatching { it.close() } }; client = null }
    }

    suspend fun foregroundChanged(value: Boolean) {
        trace("foreground=$value externalFlows=$externalFlows state=${session.value.state}")
        foreground = value
        idleJob?.cancel()
        if (value) {
            if (credentials == null && savedUsername.value != null) {
                // Existing explicit remember consent is sufficient to restore this account.
                signInSaved()
            } else if (desired && !session.value.connected && session.value.state !in setOf(SoulseekConnection.CONNECTING,SoulseekConnection.RECONNECTING)) reconnect()
            else if (desired && session.value.state == SoulseekConnection.RECONNECTING) lifecycle.withLock { if (client == null) scheduleRetry(true) }
        } else updatePolicy()
    }
    fun externalFlow(active: Boolean) {
        externalFlows = (externalFlows + if (active) 1 else -1).coerceAtLeast(0)
        trace("external active=$active count=$externalFlows foreground=$foreground")
        updatePolicy()
    }
    fun updatePolicy() {
        idleJob?.cancel()
        if (!foreground && externalFlows == 0 && !keepInBackground()) idleJob = scope.launch(worker) {
            delay(5_000)
            if (!foreground && externalFlows == 0 && !keepInBackground() && desired) {
                disconnect(SoulseekDisconnectReason.BACKGROUND_IDLE)
                receive(JSONObject().put("type","session").put("state","paused"), credentials?.first.orEmpty())
            }
        }
    }
    /**
     * [fresh] is false when the same network only reported new capabilities, which mobile data
     * does often: that is no reason to cut a back-off short, only a new network (or the first
     * one after none) is.
     */
    suspend fun networkChanged(available: Boolean, fresh: Boolean = true) {
        trace("network=$available fresh=$fresh state=${session.value.state}")
        val was = network
        network = available
        if (available) lifecycle.withLock {
            if (desired && !session.value.connected && client == null && (fresh || !was || retryJob?.isActive != true)) scheduleRetry(true)
        }
        else if (desired && credentials != null) {
            val owner = credentials!!.first
            lifecycle.withLock {
                detach()
                _session.value = SoulseekSession(SoulseekConnection.RECONNECTING, owner, "Waiting for a network connection", authenticated, SoulseekDisconnectReason.NETWORK_LOST)
            }
            receive(JSONObject().put("type","session").put("state","disconnected").put("reasonCode","NETWORK_LOST").put("reason","Waiting for a network connection"),owner)
        }
    }
}
