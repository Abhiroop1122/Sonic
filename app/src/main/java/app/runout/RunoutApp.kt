package app.runout

import app.runout.ui.LookPreferences
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.runout.drops.DropsRepository
import app.runout.discovery.DiscoveryRepository
import app.runout.library.ArtworkRepository
import app.runout.library.LibraryRepository
import app.runout.library.LibraryScanner
import app.runout.library.StorageAnalyzer
import app.runout.model.Album
import app.runout.model.Source
import app.runout.net.DefaultHttp
import app.runout.update.Updates
import app.runout.sound.BluetoothCodecs
import app.runout.net.Http
import app.runout.playback.Deck
import app.runout.playback.VinylSfx
import app.runout.pressing.PressingRepository
import app.runout.sharing.ShareRepository
import app.runout.social.SocialRepository
import app.runout.sound.SoundEngine
import app.runout.soulseek.SoulseekRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RunoutApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { graph.soulseek.onForegroundChanged(true); graph.drops.checkWeek() }
            override fun onStop(owner: LifecycleOwner) { graph.soulseek.onForegroundChanged(false) }
        })
        val connectivity = getSystemService(ConnectivityManager::class.java)
        // Wi-Fi or Ethernet, for Settings › soulseek › share on Wi-Fi only.
        fun wifi(caps: NetworkCapabilities?) = caps != null &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            /** The default network Soulseek last heard about. */
            @Volatile private var current: Network? = null
            override fun onAvailable(network: Network) {
                current = network
                graph.soulseek.onNetworkChanged(true, wifi(connectivity.getNetworkCapabilities(network)))
            }
            override fun onLost(network: Network) {
                val active = connectivity.activeNetwork
                current = active
                graph.soulseek.onNetworkChanged(active != null, wifi(active?.let(connectivity::getNetworkCapabilities)))
            }
            // Mobile data reports capability changes (signal, bandwidth) all the time. On the same
            // network they only update Wi-Fi or not; a reconnect at once is for a new network.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                val fresh = network != current
                current = network
                graph.soulseek.onNetworkChanged(true, wifi(caps), newNetwork = fresh)
            }
        })
    }
}

/** Everything the app shares, created once. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: Http = DefaultHttp
    val artwork = ArtworkRepository(app)
    val library = LibraryRepository(app, LibraryScanner(app), artwork, scope)
    val sound = SoundEngine(app)
    val sfx = VinylSfx(scope)
    val deck = Deck(app, sfx, scope, sound)
    val look = LookPreferences(app)
    val storage = StorageAnalyzer(app)
    val updates = Updates(app, http, BuildConfig.VERSION_NAME)
    val bluetoothCodecs = BluetoothCodecs(app)
    val social = SocialRepository(app)
    val share = ShareRepository(app, social)
    val pressing = PressingRepository(app)
    val soulseek = SoulseekRepository(app, scope, artwork = artwork, coverLookup = { library.coverLookupEnabled.value })
    val drops = DropsRepository(app, http, canDownload = soulseek::canDownload, publishDownload = soulseek::publishArchiveDownload)

    /** One crate: your folder, records friends sent, Friday Drops you claimed and your own pressings. */
    val shelf: StateFlow<List<Album>> = combine(library.albums, share.received, drops.albums, pressing.records, soulseek.downloadAlbums) { lib, rx, dr, pr, sl ->
        mergeShelf(lib, rx, dr, pr, sl)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val discovery = DiscoveryRepository(shelf, soulseek, scope)

    init {
        // Pure mode also switches off the turntable's own effects.
        scope.launch {
            sound.settings.map { it.pure }.distinctUntilChanged().drop(1).collect { deck.onPureChanged(it) }
        }
        // Keep portable display identity independent of Soulseek login credentials.
        // Legacy Nostr keys and friend cards stay on disk; no relays are started.
    }

    companion object {
        fun mergeShelf(vararg sources: List<Album>): List<Album> {
            val albums = sources.flatMap { it }.distinctBy { it.id }
            val downloaded = albums.filter { it.source == Source.DROP || it.source == Source.SOULSEEK }
                .flatMap { it.tracks }.map { fileIdentity(it.uri) }.toSet()
            return LibraryRepository.sortShelf(albums.mapNotNull { album ->
                if (album.source != Source.FOLDER) album else {
                    val tracks = album.tracks.filterNot { fileIdentity(it.uri) in downloaded }
                    if (tracks.isEmpty()) null else album.copy(tracks = tracks)
                }
            })
        }

        private fun fileIdentity(value: String): String = runCatching {
            val uri = android.net.Uri.parse(value)
            if (uri.scheme == "content") runCatching {
                "${uri.authority}:${android.provider.DocumentsContract.getDocumentId(uri)}"
            }.getOrElse { uri.normalizeScheme().toString() } else uri.normalizeScheme().toString()
        }.getOrDefault(value)
    }
}
