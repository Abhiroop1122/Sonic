package app.runout

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.runout.drops.DropItem
import app.runout.social.ShelfItem
import app.runout.model.Album
import app.runout.model.Source
import app.runout.sharing.ImportResult
import app.runout.sharing.VinylPackage
import app.runout.sound.SoundSettings
import app.runout.ui.RoomActions
import app.runout.ui.RoomScreen
import app.runout.ui.RoomState
import app.runout.soulseek.SoulseekSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val graph get() = (application as RunoutApp).graph
    private val message = MutableStateFlow<String?>(null)
    private val claiming = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val activeConversation = MutableStateFlow<String?>(null)
    /** The music folder changed while signed in: Settings › Library asks whether sharing should follow. */
    private val shareSwap = MutableStateFlow(false)
    /** The shared-folder picker was opened from that question while sharing was on. */
    private var resumeSharing = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        graph.soulseek.onExternalFlow(false)
        if (granted) graph.soulseek.setStayOnline(true)
        else message.value = "Stay-online mode needs notifications so you can see and disconnect the session. Local playback remains available."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The theme before the first frame, so a dark room never opens white.
        val phoneDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyTheme(graph.look.look.value.dark(phoneDark))
        if (savedInstanceState == null) handleIncoming(intent)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("capture_intro_deck", false)) app.runout.ui.deck.DeckCapture.requested = true
        // Once a day, when it's on: is there a newer release on GitHub? Quiet if it can't tell.
        if (savedInstanceState == null && BuildConfig.UPDATE_CHECK) graph.scope.launch { graph.updates.checkIfDue() }

        val graph = graph
        // A track that fails to play lifts the needle; the room says why.
        lifecycleScope.launch {
            graph.deck.problem.collect { p -> if (p != null) { message.value = p; graph.deck.clearProblem() } }
        }
        app.runout.ui.TeType.init(this)
        setContent {
            val albums by graph.shelf.collectAsStateWithLifecycle()
            val folder by graph.library.folder.collectAsStateWithLifecycle()
            val scan by graph.library.scan.collectAsStateWithLifecycle()
            val report by graph.library.report.collectAsStateWithLifecycle()
            val deck by graph.deck.state.collectAsStateWithLifecycle()
            val crackle by graph.deck.crackleOn.collectAsStateWithLifecycle()
            val sound by graph.sound.settings.collectAsStateWithLifecycle()
            val output by graph.sound.status.collectAsStateWithLifecycle()
            val profile by graph.social.profile.collectAsStateWithLifecycle()
            val friends by graph.social.friends.collectAsStateWithLifecycle()
            val drops by graph.drops.state.collectAsStateWithLifecycle()
            val claimed by graph.drops.claimed.collectAsStateWithLifecycle()
            val newDrop by graph.drops.newDrop.collectAsStateWithLifecycle()
            val deckFlags by graph.deck.flags.collectAsStateWithLifecycle()
            val look by graph.look.look.collectAsStateWithLifecycle()
            val storage by graph.storage.state.collectAsStateWithLifecycle()
            val update by graph.updates.state.collectAsStateWithLifecycle()
            val autoUpdates by graph.updates.auto.collectAsStateWithLifecycle()
            val updateChannel by graph.updates.channel.collectAsStateWithLifecycle()
            val bluetoothCodecOn by graph.bluetoothCodecs.enabled.collectAsStateWithLifecycle()
            val bluetoothCodec by graph.bluetoothCodecs.codec.collectAsStateWithLifecycle()
            val bluetoothCodecProblem by graph.bluetoothCodecs.problem.collectAsStateWithLifecycle()
            val dark = look.dark(androidx.compose.foundation.isSystemInDarkTheme())
            LaunchedEffect(dark) { applyTheme(dark) }
            // The next launch screen in the pinned theme's paper too (with "phone", Android's own).
            LaunchedEffect(look.theme) {
                if (android.os.Build.VERSION.SDK_INT >= 33) splashScreen.setSplashScreenTheme(
                    when (look.theme) {
                        app.runout.ui.ThemeMode.DARK -> R.style.Theme_Runout_Dark
                        app.runout.ui.ThemeMode.LIGHT -> R.style.Theme_Runout_Light
                        app.runout.ui.ThemeMode.PHONE -> android.content.res.Resources.ID_NULL
                    },
                )
            }
            val sleepAt by graph.deck.sleepAt.collectAsStateWithLifecycle()
            val listening by previewState.collectAsStateWithLifecycle()
            val claimingNow by claiming.collectAsStateWithLifecycle()
            val note by message.collectAsStateWithLifecycle()
            val session by graph.soulseek.session.collectAsStateWithLifecycle()
            val results by graph.soulseek.searchResults.collectAsStateWithLifecycle()
            val searching by graph.soulseek.searching.collectAsStateWithLifecycle()
            val transfers by graph.soulseek.transfers.collectAsStateWithLifecycle()
            val uploads by graph.soulseek.uploads.collectAsStateWithLifecycle()
            val contacts by graph.soulseek.contacts.collectAsStateWithLifecycle()
            val conversations by graph.soulseek.conversations.collectAsStateWithLifecycle()
            val destination by graph.soulseek.destination.collectAsStateWithLifecycle()
            val sharedFolder by graph.soulseek.sharedFolder.collectAsStateWithLifecycle()
            val offerShareSwap by shareSwap.collectAsStateWithLifecycle()
            val sharing by graph.soulseek.sharing.collectAsStateWithLifecycle()
            val stayOnline by graph.soulseek.stayOnline.collectAsStateWithLifecycle()
            val savedUsername by graph.soulseek.savedUsername.collectAsStateWithLifecycle()
            val networkError by graph.soulseek.error.collectAsStateWithLifecycle()
            val selectedConversation by activeConversation.collectAsStateWithLifecycle()
            val searchStatus by graph.soulseek.searchStatus.collectAsStateWithLifecycle()
            val folderPreview by graph.soulseek.folderPreview.collectAsStateWithLifecycle()
            val onboardingComplete by graph.soulseek.preferences.onboarded.collectAsStateWithLifecycle()
            val onboardingStep by graph.soulseek.preferences.step.collectAsStateWithLifecycle()
            val settingsRevision by graph.soulseek.preferences.revision.collectAsStateWithLifecycle()
            val shareConsent = remember(settingsRevision,session.username) { session.username?.let(graph.soulseek.preferences::sharing) ?: false }
            val backgroundConsent = remember(settingsRevision,session.username) { session.username?.let(graph.soulseek.preferences::background) ?: false }
            val turtleMode = remember(settingsRevision) { graph.soulseek.preferences.turtle() }
            val wifiOnly = remember(settingsRevision,session.username) { session.username?.let(graph.soulseek.preferences::wifiOnly) ?: true }
            val uploadSlots = remember(settingsRevision,session.username) {
                session.username?.let(graph.soulseek.preferences::uploadSlots) ?: app.runout.soulseek.SoulseekPreferences.DEFAULT_UPLOAD_SLOTS
            }
            val waitingForWifi by graph.soulseek.waitingForWifi.collectAsStateWithLifecycle()
            val discovery by graph.discovery.state.collectAsStateWithLifecycle()
            val discoveryQuery by graph.discovery.query.collectAsStateWithLifecycle()
            val coverLookupEnabled by graph.library.coverLookupEnabled.collectAsStateWithLifecycle()
            val coverLookupNoticePending by graph.library.coverLookupNoticePending.collectAsStateWithLifecycle()
            var pendingDownload by remember { mutableStateOf<SoulseekSearchResult?>(null) }


            var clock by remember { mutableStateOf(now()) }
            // Only while the activity is started: a clock no one can see needs no wake-ups.
            val clockLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(clockLifecycle) {
                clockLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    while (true) {
                        clock = now()
                        delay(15_000)
                    }
                }
            }

            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                graph.soulseek.onExternalFlow(false)
                if (uri != null) changeFolder(uri)
            }
            val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                graph.soulseek.onExternalFlow(false)
                val pending = pendingDownload; pendingDownload = null
                if (uri != null) graph.scope.launch {
                    graph.soulseek.setDestination(uri).join()
                    if (pending != null && graph.soulseek.destination.value == uri) graph.soulseek.download(pending)
                }
            }
            val sharePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                graph.soulseek.onExternalFlow(false)
                // A new shared folder starts unshared; one picked to follow a new music folder
                // carries on sharing if sharing was on before.
                val resume = resumeSharing; resumeSharing = false
                if (uri != null) graph.scope.launch {
                    graph.soulseek.setSharedFolder(uri).join()
                    if (resume && graph.soulseek.sharedFolder.value == uri && graph.soulseek.session.value.connected) graph.soulseek.setSharing(true)
                }
            }
            val autoEqPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) importAutoEq(uri)
            }
            // The Bluetooth codec readout: Android's companion-device dialog for the headphones, then
            // "Nearby devices" before it. Both only after the listener turns the readout on.
            val linkHeadphones = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                graph.bluetoothCodecs.onLinked(result.resultCode == RESULT_OK)
            }
            val askLink: (android.content.IntentSender) -> Unit = { sender ->
                runCatching { linkHeadphones.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }
            }
            val nearbyDevices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) graph.bluetoothCodecs.refresh(askLink) else graph.bluetoothCodecs.refresh {}
            }
            // Headphones coming and going: look again, while the readout is on.
            LaunchedEffect(output.device.kind, output.device.id, bluetoothCodecOn) {
                if (bluetoothCodecOn && output.device.kind == app.runout.sound.OutputKind.BLUETOOTH) graph.bluetoothCodecs.refresh(askLink)
            }
            val actions = remember {
                object : RoomActions {
                    override fun pickFolder() { graph.soulseek.onExternalFlow(true); folderPicker.launch(null) }
                    override fun rescan() { graph.library.rescan() }
                    override fun repairLibrary() { graph.library.repair() }
                    override fun setCoverLookupEnabled(enabled: Boolean) { graph.library.setCoverLookupEnabled(enabled) }
                    override fun dismissCoverLookupNotice() { graph.library.dismissCoverLookupNotice() }
                    override fun load(album: Album) = graph.deck.load(album)
                    override fun discLanded() = graph.deck.discLanded()
                    override fun cueTrack(album: Album, trackId: String?) = graph.deck.cueTrack(album, trackId)
                    override fun toggleCrackle() = graph.deck.toggleCrackle()
                    override fun needleDrop(fraction: Float) = graph.deck.needleDrop(fraction)
                    override fun playTrack(sideIndex: Int, trackIndex: Int) = graph.deck.playTrack(sideIndex, trackIndex)
                    override fun liftNeedle() = graph.deck.liftNeedle()
                    override fun holdPlatter() = graph.deck.holdPlatter()
                    override fun releasePlatter() = graph.deck.releasePlatter()
                    override fun flip() = graph.deck.flip()
                    override fun eject() = graph.deck.eject()
                    override fun startStop() = graph.deck.startStop()
                    override fun nextTrack() = graph.deck.nextTrack()
                    override fun previousTrack() = graph.deck.previousTrack()

                    override fun updateSound(transform: (SoundSettings) -> SoundSettings) = graph.sound.update(transform)
                    override fun applyPreset(name: String) = graph.sound.applyPreset(name)
                    override fun importAutoEq() = autoEqPicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))

                    override fun share(album: Album) = shareAlbum(album)
                    override fun shareImage(file: File, title: String) = sharePicture(file, title)
                    override fun removeRecord(album: Album) = remove(album)
                    override fun shareMyCard() = shareCard()
                    override fun rename(name: String) = graph.social.rename(name)
                    override fun setAccent(accent: Int) = graph.social.setAccent(accent)
                    override fun removeFriend(id: String) = graph.social.removeFriend(id)

                    override fun refreshDrops() { graph.scope.launch { graph.drops.refresh() } }
                    override fun claim(item: DropItem) = claimDrop(item)
                    override suspend fun dropCover(item: DropItem): String? = graph.drops.thumbnail(item)?.absolutePath
                    override suspend fun dropTracks(item: DropItem) = graph.drops.preview(item).getOrNull()
                    override fun listen(url: String?) = listenTo(url)

                    override fun dismissMessage() { message.value = null }

                    override fun signInSoulseek(username: String, password: String, remember: Boolean) { graph.soulseek.signIn(username, password, remember) }
                    override fun signInSavedSoulseek() { graph.soulseek.signInSaved() }
                    override fun signOutSoulseek() { activeConversation.value = null; graph.soulseek.logout() }
                    override fun searchSoulseek(query: String) { graph.soulseek.search(query) }
                    override fun stopSoulseekSearch() { graph.soulseek.stopSearch() }
                    override fun clearSearch() { graph.soulseek.clearSearch(); graph.discovery.clear() }
                    override fun setAutoFlip(on: Boolean) = graph.deck.setAutoFlip(on)
                    override fun setAutoPlay(on: Boolean) = graph.deck.setAutoPlay(on)
                    override fun setSleepMode(on: Boolean) = graph.deck.setSleepMode(on)
                    override fun setTheme(theme: app.runout.ui.ThemeMode) = graph.look.setTheme(theme)
                    override fun setDeckSkin(deck: app.runout.ui.DeckSkin) = graph.look.setDeck(deck)
                    override fun setIntroOnLaunch(on: Boolean) = graph.look.setIntroOnLaunch(on)
                    override fun setDeckLights(on: Boolean) = graph.look.setDeckLights(on)
                    override fun setVinylOnly(on: Boolean) = graph.look.setVinylOnly(on)
                    override fun checkForUpdates() { graph.scope.launch { graph.updates.check() } }
                    override fun setAutoUpdates(enabled: Boolean) = graph.updates.setAuto(enabled)
                    override fun setUpdateChannel(channel: app.runout.update.UpdateChannel) = graph.updates.setChannel(channel)
                    override fun setBluetoothCodec(enabled: Boolean) {
                        graph.bluetoothCodecs.setEnabled(enabled)
                        if (!enabled) return
                        if (graph.bluetoothCodecs.needsPermission()) nearbyDevices.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                        else graph.bluetoothCodecs.refresh(askLink)
                    }
                    override fun openUpdate(page: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(page))) } }
                    override fun analyzeStorage() {
                        graph.scope.launch { graph.storage.analyze(graph.library.folder.value, graph.soulseek.destination.value, graph.shelf.value) }
                    }
                    override fun clearCache() { graph.scope.launch { graph.storage.clearCache() } }
                    override fun deleteRecordFolder(record: app.runout.library.RecordUsage) {
                        graph.scope.launch {
                            val album = graph.shelf.value.firstOrNull { it.id == record.albumId }
                            if (album != null && graph.deck.state.value.album?.id == album.id) graph.deck.eject()
                            graph.storage.delete(record).onSuccess {
                                when (album?.source) {
                                    Source.SOULSEEK -> graph.soulseek.removeAlbum(album.id)
                                    Source.DROP -> graph.drops.remove(album.id)
                                    else -> Unit
                                }
                                graph.library.rescan()
                                message.value = "Deleted “${record.title}”"
                                graph.storage.analyze(graph.library.folder.value, graph.soulseek.destination.value, graph.shelf.value)
                            }.onFailure { message.value = it.message ?: "Couldn't delete “${record.title}”" }
                        }
                    }
                    override fun setSleepTimer(minutes: Int?) = graph.deck.setSleepTimer(minutes)
                    override fun downloadSoulseek(result: SoulseekSearchResult) {
                        if(graph.soulseek.destination.value == null) {
                            pendingDownload = result; graph.soulseek.onExternalFlow(true); downloadPicker.launch(null)
                        } else graph.soulseek.download(result)
                    }
                    override fun downloadSoulseekFolder(result: SoulseekSearchResult) { graph.soulseek.downloadFolder(result) }
                    override fun pauseSoulseekTransfer(id: String) { graph.soulseek.pause(id) }
                    override fun pauseAllDownloads() { graph.soulseek.pauseAll() }
                    override fun resumeAllDownloads() { graph.soulseek.resumeAll() }
                    override fun setTurtleMode(enabled: Boolean) { graph.soulseek.setTurtle(enabled) }
                    override fun setSoulseekWifiOnly(enabled: Boolean) { graph.soulseek.setWifiOnly(enabled) }
                    override fun setSoulseekUploadSlots(slots: Int) { graph.soulseek.setUploadSlots(slots) }
                    override fun resumeSoulseekTransfer(id: String) { resumeDownload(id) }
                    override fun cancelSoulseekTransfer(id: String) { graph.soulseek.cancel(id) }
                    override fun removeSoulseekTransfer(id: String) { graph.soulseek.remove(id) }
                    override fun removeFailedDownloads() { graph.soulseek.removeFailed() }
                    override fun downloadSoulseekTracks(files: List<SoulseekSearchResult>, albumId: String?) { graph.soulseek.downloadTracks(files, albumId) }
                    override fun retrySoulseekTransfer(id: String) {
                        // Soulseek: the same peer again with a fresh round of automatic retries. Archive: its drop.
                        if (graph.soulseek.transfers.value.firstOrNull { it.id == id }?.provider == "Soulseek") graph.soulseek.retry(id) else resumeDownload(id)
                    }
                    override fun fetchMissingTracks(albumId: String) { graph.soulseek.fetchMissing(albumId) }
                    override fun pickDownloadFolder() { graph.soulseek.onExternalFlow(true); downloadPicker.launch(null) }
                    override fun pickSharedFolder() { graph.soulseek.onExternalFlow(true); sharePicker.launch(null) }
                    override fun answerShareSwap(change: Boolean) {
                        shareSwap.value = false
                        if (!change) return
                        // Its own picker, so Android asks for the shared folder's permission separately.
                        resumeSharing = graph.soulseek.session.value.username?.let(graph.soulseek.preferences::sharing) ?: false
                        pickSharedFolder()
                    }
                    override fun setSoulseekSharing(enabled: Boolean) { graph.soulseek.setSharing(enabled) }
                    override fun setSoulseekStayOnline(enabled: Boolean) {
                        if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            graph.soulseek.onExternalFlow(true)
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        else graph.soulseek.setStayOnline(enabled)
                    }
                    override fun addSoulseekContact(username: String) { graph.soulseek.addContact(username) }
                    override fun openSoulseekConversation(username: String) { activeConversation.value = username; graph.soulseek.openConversation(username) }
                    override fun closeSoulseekConversation() { activeConversation.value = null }
                    override fun sendSoulseekMessage(username: String, text: String) { graph.soulseek.sendMessage(username, text) }
                    override fun sendSoulseekMessage(username: String, text: String, onPersisted: () -> Unit) {
                        graph.soulseek.sendMessage(username, text) { graph.scope.launch(Dispatchers.Main.immediate) { onPersisted() } }
                    }
                    override fun markSoulseekRead(username: String) { graph.soulseek.markRead(username) }
                    override fun clearSoulseekError() { graph.soulseek.clearError() }
                    override fun reconnectSoulseek() { graph.soulseek.reconnect() }
                    override fun disconnectSoulseek() { graph.soulseek.disconnectFromNotification() }
                    override fun previewSoulseekFolder(result: SoulseekSearchResult) { graph.soulseek.previewFolder(result) }
                    override fun openSoulseekFolder(username: String, directory: String) { graph.soulseek.previewFolder(username, directory) }
                    override fun confirmSoulseekFolder() { graph.soulseek.confirmFolderDownload() }
                    override fun closeSoulseekFolder() { graph.soulseek.closeFolderPreview() }
                    override fun setSearchQuery(value: String) { graph.discovery.setQuery(value) }
                    override fun setSearchOptions(value: app.runout.discovery.DiscoveryOptions) { graph.discovery.setOptions(value) }
                    override fun broadenSearch() { graph.discovery.broaden() }
                    override fun finishOnboarding() { graph.soulseek.preferences.finishOnboarding() }
                    override fun setOnboardingStep(value: Int) { graph.soulseek.preferences.setStep(value) }
                    override suspend fun coverFor(item: ShelfItem): String? {
                        val id = "shelf-" + app.runout.library.LibraryScanner.stableId(item.artist.lowercase() + "|" + item.title.lowercase())
                        val online = graph.library.coverLookupEnabled.value
                        return (graph.artwork.cachedFile(id) ?: if (online) graph.artwork.lookupOnline(id, item.artist, item.title) else null)?.absolutePath
                    }
                }
            }

            RoomScreen(
                state = RoomState(
                    albums = albums,
                    hasFolder = folder != null,
                    scan = scan,
                    deck = deck,
                    crackleOn = crackle,
                    clock = clock,
                    report = report,
                    sound = sound,
                    output = output,
                    profile = profile,
                    friends = friends,
                    drops = drops,
                    claimed = claimed,
                    claiming = claimingNow,
                    message = note,
                    soulseekSession = session,
                    soulseekResults = results,
                    soulseekSearching = searching,
                    soulseekTransfers = transfers,
                    soulseekUploads = uploads,
                    soulseekContacts = contacts,
                    soulseekConversations = conversations,
                    soulseekConversation = selectedConversation,
                    downloadFolder = destination?.let(::folderLabel),
                    sharedFolder = sharedFolder?.let(::folderLabel),
                    // Signed out since the question came up: don't ask.
                    offerShareSwap = offerShareSwap && session.hasAccount,
                    soulseekSharing = sharing,
                    soulseekStayOnline = stayOnline,
                    soulseekShareConsent = shareConsent,
                    soulseekBackgroundConsent = backgroundConsent,
                    soulseekSavedUsername = savedUsername,
                    soulseekError = networkError,
                    discovery = discovery.copy(query = discoveryQuery),
                    soulseekSearchStatus = searchStatus,
                    folderPreview = folderPreview,
                    onboardingComplete = onboardingComplete,
                    onboardingStep = onboardingStep,
                    coverLookupEnabled = coverLookupEnabled,
                    coverLookupNoticePending = coverLookupNoticePending,
                    turtleMode = turtleMode,
                    newDrop = newDrop,
                    deckFlags = deckFlags,
                    look = look,
                    storage = storage,
                    update = update,
                    autoUpdates = autoUpdates,
                    updateChannel = updateChannel,
                    bluetoothCodecOn = bluetoothCodecOn,
                    bluetoothCodec = bluetoothCodec,
                    bluetoothCodecProblem = bluetoothCodecProblem,
                    sleepAt = sleepAt,
                    soulseekWifiOnly = wifiOnly,
                    soulseekUploadSlots = uploadSlots,
                    soulseekWaitingForWifi = waitingForWifi,
                    preview = listening,
                ),
                actions = actions,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Folder pickers and the share sheet can bring the system bars back; the room is full screen.
        immerse()
    }

    override fun onStop() {
        // A drop preview is a quick listen, not background music.
        listenTo(null)
        // The launcher icon takes the room's theme while the room is off screen.
        if (!isChangingConfigurations) runCatching { LauncherIcon.follow(this, app.runout.ui.Room.dark) }
        super.onStop()
    }

    /**
     * Upright, the status bar stays (clock, battery, notifications) in dark ink on the white room and
     * only the navigation bar hides; sideways the room fills the screen. Hidden bars appear when swiped in.
     */
    private fun immerse() {
        val sideways = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (sideways) hide(WindowInsetsCompat.Type.systemBars())
            else { show(WindowInsetsCompat.Type.statusBars()); hide(WindowInsetsCompat.Type.navigationBars()) }
            isAppearanceLightStatusBars = !app.runout.ui.Room.dark
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Light or dark (OLED black): the room's palette, the window behind it and the bar icons. */
    private fun applyTheme(dark: Boolean) {
        app.runout.ui.Room.dark = dark
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(app.runout.ui.Room.Background.toArgb()))
        immerse()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity keeps itself across turns: switch between upright and sideways bars here.
        immerse()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }


    /**
     * A music folder picked in Settings › Library, onboarding or the empty room. When it replaces
     * another folder, the record on the deck (a folder record is always from the old folder) goes
     * back first, since the old folder's read grant is released; the crate refills from the new
     * folder; and, signed in to Soulseek with a folder shared, Settings › Library asks whether the
     * shared folder should follow.
     */
    private fun changeFolder(uri: Uri) {
        val old = graph.library.folder.value
        if (old != null && old != uri && graph.deck.state.value.album?.source == Source.FOLDER) graph.deck.eject()
        graph.library.setFolder(uri, keepGrant = ::grantStillUsed)
        if (offersShareSwap(old?.toString(), uri.toString(), graph.soulseek.session.value.hasAccount, graph.soulseek.sharedFolder.value?.toString())) shareSwap.value = true
    }

    /**
     * Whether a folder grant is still needed after the music folder moves away from it: it is the
     * downloads folder or a shared folder (this account's, or one remembered for another account).
     */
    private fun grantStillUsed(uri: Uri): Boolean {
        val s = uri.toString()
        if (graph.soulseek.destination.value?.toString() == s || graph.soulseek.sharedFolder.value?.toString() == s) return true
        return listOf("soulseek", "soulseek-setup").any { name -> getSharedPreferences(name, MODE_PRIVATE).all.values.any { it == s } }
    }

    private fun folderLabel(uri: Uri): String = runCatching {
        androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name
    }.getOrNull() ?: "Selected music folder"

    /** A .vinyl file opened from a chat app, Files or Quick Share. */
    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        message.value = "Opening record…"
        graph.scope.launch {
            message.value = when (val r = graph.share.import(uri)) {
                is ImportResult.AlbumAdded -> "“${r.album.title}” from ${r.album.from} is in your crate"
                is ImportResult.CardAdded -> "${r.fromName} sent you “${r.album.title}” as a card · see Friends"
                is ImportResult.FriendAdded -> if (r.isNew) "${r.name} is now your friend · ${r.records} records" else "Updated ${r.name}'s shelf"
                is ImportResult.Failed -> "Couldn't open that file: ${r.reason}"
            }
        }
    }

    private fun shareAlbum(album: Album) {
        message.value = "Packing “${album.title}”…"
        graph.scope.launch {
            val file = runCatching { graph.share.exportAlbum(album) }.getOrElse {
                message.value = "Couldn't pack the record: ${it.message}"
                return@launch
            }
            launchShare(file, "Send “${album.title}”")
            message.value = if (album.audioShareable) "Sending “${album.title}” with its audio"
            else "Sending “${album.title}” as a card: sleeve and track list. Audio only travels for your own music and Creative Commons records."
        }
    }

    private fun shareCard() {
        graph.scope.launch {
            val file = runCatching { graph.share.exportFriendCard(graph.shelf.value) }.getOrElse {
                message.value = "Couldn't make your card: ${it.message}"
                return@launch
            }
            launchShare(file, "Send your friend card")
        }
    }

    /** A record's details as a PNG, to any app that takes a picture. */
    private fun sharePicture(file: File, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = android.content.ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share “$title”"))
    }

    private fun launchShare(file: File, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = VinylPackage.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, title))
    }

    private fun remove(album: Album) {
        if (graph.deck.state.value.album?.id == album.id) graph.deck.eject()
        when (album.source) {
            Source.RECEIVED -> graph.share.delete(album.id)
            Source.DROP -> graph.drops.remove(album.id)
            Source.PRESSING -> graph.pressing.delete(album.id)
            Source.SOULSEEK -> graph.soulseek.removeAlbum(album.id)
            Source.FOLDER -> return
        }
        message.value = "Took “${album.title}” off the shelf"
    }

    private fun claimDrop(item: DropItem) {
        if (item.identifier in claiming.value) return
        claiming.update { it + (item.identifier to 0f) }
        graph.scope.launch {
            try {
                val result = graph.soulseek.withArchiveDownload(item.identifier, item.title) { report ->
                    graph.drops.claim(item) { p ->
                        report((p * 1_000_000).toLong(), 1_000_000L)
                        claiming.update { m -> if (item.identifier in m) m + (item.identifier to p) else m }
                    }
                }
                message.value = result.fold({ "“${it.title}” is in your crate" }, { "Couldn't claim it: ${it.message}" })
            } finally { claiming.update { it - item.identifier } }
        }
    }

    private fun resumeDownload(id: String) {
        val transfer = graph.soulseek.transfers.value.firstOrNull { it.id == id } ?: return
        if (transfer.provider != "Internet Archive") { graph.soulseek.resume(id); return }
        graph.scope.launch {
            val week = (graph.drops.refresh() as? app.runout.drops.DropState.Ready)?.week
            val owner = graph.soulseek.session.value.username
            val item = week?.items?.firstOrNull { app.runout.soulseek.SoulseekPolicy.accountKey(owner.orEmpty(), "archive:${it.identifier}") == id }
            if (item != null) claimDrop(item)
            else message.value = "This album is outside the current Friday Drop. Completed FLAC files are still in your music folder."
        }
    }

    // A short listen to a Friday Drop before claiming it. It streams from archive.org and stops on
    // its own thirty seconds after the sound starts; it never touches the deck. It takes audio
    // focus for its moment, so a spinning record pauses and comes back after, and it stops when
    // headphones go, rather than carrying on out of the speaker. [previewState] is what the track
    // rows show; every way the listen ends goes through listenTo(null), which clears it and gives
    // the focus back.
    private val previewState = MutableStateFlow<app.runout.ui.PreviewState?>(null)
    private var preview: android.media.MediaPlayer? = null
    private var previewStop: kotlinx.coroutines.Job? = null
    private var previewFocus: android.media.AudioFocusRequest? = null
    private val previewAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val previewNoisy = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) listenTo(null)
        }
    }
    private var previewNoisyRegistered = false

    private fun listenTo(url: String?) {
        previewStop?.cancel()
        previewStop = null
        preview?.let { runCatching { it.stop() }; it.release() }
        preview = null
        previewState.value = null
        val audio = getSystemService(android.media.AudioManager::class.java)
        previewFocus?.let { runCatching { audio?.abandonAudioFocusRequest(it) } }
        previewFocus = null
        if (previewNoisyRegistered) { runCatching { unregisterReceiver(previewNoisy) }; previewNoisyRegistered = false }
        if (url == null) return
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(previewAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == android.media.AudioManager.AUDIOFOCUS_LOSS || change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) listenTo(null)
            }
            .build()
        if (audio?.requestAudioFocus(focus) != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            message.value = "Couldn't play a preview right now."
            return
        }
        previewFocus = focus
        androidx.core.content.ContextCompat.registerReceiver(
            this, previewNoisy, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        previewNoisyRegistered = true
        previewState.value = app.runout.ui.PreviewState(url, app.runout.ui.PreviewPhase.BUFFERING)
        val player = android.media.MediaPlayer()
        preview = player
        player.setAudioAttributes(previewAttributes)
        player.setOnPreparedListener { p ->
            if (preview !== p) return@setOnPreparedListener
            p.start()
            previewState.value = app.runout.ui.PreviewState(url, app.runout.ui.PreviewPhase.PLAYING)
            // The thirty seconds count from the first sound, not from the tap.
            previewStop?.cancel()
            previewStop = graph.scope.launch { kotlinx.coroutines.delay(PREVIEW_MS); if (preview === p) listenTo(null) }
        }
        // Done early: give the focus back, so the record comes back too. Posted, so the player
        // isn't released from inside its own callback.
        player.setOnCompletionListener { done -> window.decorView.post { if (preview === done) listenTo(null) } }
        player.setOnErrorListener { failed, _, _ ->
            window.decorView.post {
                if (preview === failed) { message.value = "Couldn't play a preview of that track."; listenTo(null) }
            }
            true
        }
        val started = runCatching { player.setDataSource(url); player.prepareAsync() }
        if (started.isFailure) {
            message.value = "Couldn't play a preview of that track."
            listenTo(null)
            return
        }
        // A slow archive: give up rather than hold the focus (and the paused record) in silence.
        previewStop = graph.scope.launch {
            kotlinx.coroutines.delay(PREVIEW_BUFFER_MS)
            if (preview === player && previewState.value?.phase == app.runout.ui.PreviewPhase.BUFFERING) {
                message.value = "Couldn't load a preview right now."
                listenTo(null)
            }
        }
    }

    private fun importAutoEq(uri: Uri) {
        graph.scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().take(256_000).toByteArray().toString(Charsets.UTF_8) } }.getOrNull()
            }
            message.value = if (text != null && graph.sound.importAutoEq(text)) "AutoEq profile loaded" else "That file isn't an AutoEq ParametricEQ.txt"
        }
    }

    private fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
}

/** A drop listen plays this long from its first sound. */
private const val PREVIEW_MS = 30_000L
/** How long a drop listen may take to start before it gives up. */
private const val PREVIEW_BUFFER_MS = 15_000L

/**
 * After the music folder moves from [old] to [new]: ask whether the Soulseek shared folder should
 * follow only when the folder really changed (not the first pick, not the same folder again), the
 * person is signed in to Soulseek (never offline or without an account) and something is shared.
 */
internal fun offersShareSwap(old: String?, new: String, signedIn: Boolean, shared: String?): Boolean =
    old != null && old != new && signedIn && shared != null
