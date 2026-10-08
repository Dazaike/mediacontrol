package com.mediacontrol.remote.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.SystemClock
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.mediacontrol.remote.lyrics.LyricsClock
import com.mediacontrol.remote.lyrics.LyricsCodec
import com.mediacontrol.remote.lyrics.LyricsProvider
import com.mediacontrol.remote.lyrics.lyricsTrackKey
import com.mediacontrol.remote.relay.decodeLyricsSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.mediacontrol.remote.relay.RelayCommand
import com.mediacontrol.remote.relay.RelayProtocol
import com.mediacontrol.remote.relay.encode
import com.mediacontrol.remote.soundcore.SoundcoreMode
import com.mediacontrol.remote.soundcore.SoundcoreStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

private const val LYRICS_TIMEOUT_MS = 45_000L
private const val MAX_BEACON_LATENCY_MS = 3_000L
private const val TAG = "PhoneRelaySource"

/** An installed media app on the phone, offered on the watch's Add-players screen. */
data class PhoneAppInfo(val packageName: String, val label: String)

/**
 * Watch-side half of the phone companion: receives `/media-state` DataItem updates
 * (session, volume, and queue) and sends `/media-cmd` messages to the phone.
 */
class PhoneRelaySource(private val appContext: Context) :
    DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private val _relaySession = MutableStateFlow<ControlledSession?>(null)
    val relaySession: StateFlow<ControlledSession?> = _relaySession.asStateFlow()

    private val _volume = MutableStateFlow(7)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _maxVolume = MutableStateFlow(15)
    val maxVolume: StateFlow<Int> = _maxVolume.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueTrack>>(emptyList())
    val queue: StateFlow<List<QueueTrack>> = _queue.asStateFlow()

    private val _queueTitle = MutableStateFlow("Queue")
    val queueTitle: StateFlow<String> = _queueTitle.asStateFlow()

    private val _btAudioState = MutableStateFlow(BtAudioState(null, false, null))
    val btAudioState: StateFlow<BtAudioState> = _btAudioState.asStateFlow()
    private val _artworkBytes = MutableStateFlow<ByteArray?>(null)
    val artworkBytes: StateFlow<ByteArray?> = _artworkBytes.asStateFlow()
    private val _liveSessions = MutableStateFlow<List<SessionCandidate>>(emptyList())
    val liveSessions: StateFlow<List<SessionCandidate>> = _liveSessions.asStateFlow()
    private val _soundcore = MutableStateFlow(SoundcoreStatus(null, ""))
    val soundcore: StateFlow<SoundcoreStatus> = _soundcore.asStateFlow()
    private val _phoneApps = MutableStateFlow<List<PhoneAppInfo>>(emptyList())
    val phoneApps: StateFlow<List<PhoneAppInfo>> = _phoneApps.asStateFlow()

    private val _sessionActions = MutableStateFlow<List<SessionAction>>(emptyList())
    /** The active player's own controls (favourite, shuffle, ...) mirrored from the phone. */
    val sessionActions: StateFlow<List<SessionAction>> = _sessionActions.asStateFlow()

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()
    /** Position beacon from the phone; anchored on receipt. See [LyricsClock]. */
    val lyricsClock = LyricsClock()
    private val _lyricsSyncTrackKey = MutableStateFlow<String?>(null)
    /** Track key the latest beacon belongs to, so a stale clock is not applied to a new track. */
    val lyricsSyncTrackKey: StateFlow<String?> = _lyricsSyncTrackKey.asStateFlow()
    @Volatile private var requestedLyricsKey: String? = null
    @Volatile private var requestedLyricsProvider: LyricsProvider? = null
    private var lyricsWatchdog: Job? = null
    private var lastSession: ControlledSession? = null
    private var lastSessionAt = 0L
    @Volatile private var iconAssets: Map<String, Asset> = emptyMap()
    private val iconCache = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var cachedNodeId: String? = null

    init {
        Log.i(TAG, "Initializing PhoneRelaySource")
        // Registering the listener is a GMS binder call; done inline it lands on the
        // main thread during Application.onCreate and delays the first frame. The
        // prefetch runs after registration, so no update can slip through the gap.
        scope.launch {
            stateMutex.withLock {
                try {
                    Wearable.getDataClient(appContext).addListener(this@PhoneRelaySource)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register DataClient listener: ${e.message}")
                }
                try {
                    Wearable.getMessageClient(appContext).addListener(this@PhoneRelaySource)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register MessageClient listener: ${e.message}")
                }
                prefetch(RelayProtocol.PATH_MEDIA_STATE) { processDataMap(it) }
                prefetch(RelayProtocol.PATH_MEDIA_APPS) { processApps(it) }
                prefetch(RelayProtocol.PATH_APP_ICONS) { processIcons(it) }
                prefetch(RelayProtocol.PATH_MEDIA_ACTIONS) { map -> scope.launch { processActions(map) } }
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val frozen = ArrayList<DataEvent>(dataEvents.count)
        for (event in dataEvents) {
            frozen += event.freeze()
        }
        scope.launch {
            stateMutex.withLock {
                for (event in frozen) {
                    val path = event.dataItem.uri.path
                    val removed = event.type != DataEvent.TYPE_CHANGED
                    when (path) {
                        RelayProtocol.PATH_MEDIA_STATE -> {
                            if (removed) {
                                lastSession = null
                                _relaySession.value = null
                            } else {
                                processDataMap(DataMapItem.fromDataItem(event.dataItem).dataMap)
                            }
                        }
                        RelayProtocol.PATH_MEDIA_APPS -> {
                            if (removed) _phoneApps.value = emptyList()
                            else processApps(DataMapItem.fromDataItem(event.dataItem).dataMap)
                        }
                        RelayProtocol.PATH_APP_ICONS -> {
                            if (!removed) processIcons(DataMapItem.fromDataItem(event.dataItem).dataMap)
                        }
                        RelayProtocol.PATH_MEDIA_ACTIONS -> {
                            if (removed) _sessionActions.value = emptyList()
                            else processActions(DataMapItem.fromDataItem(event.dataItem).dataMap)
                        }
                        RelayProtocol.PATH_LYRICS -> {
                            if (!removed) processLyrics(DataMapItem.fromDataItem(event.dataItem).dataMap)
                        }
                    }
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != RelayProtocol.PATH_LYRICS_SYNC) return
        val msg = decodeLyricsSync(event.data) ?: return
        // Beacons spend some time in flight; the phone measures it from our acks and tells us.
        val lead = if (msg.playing) msg.latencyMs.coerceIn(0L, MAX_BEACON_LATENCY_MS) else 0L
        lyricsClock.anchor(msg.posMs + lead, msg.playing, SystemClock.elapsedRealtime())
        sendCommand(RelayCommand.LyricsAck(msg.stamp))
        if (_lyricsSyncTrackKey.value != msg.trackKey) _lyricsSyncTrackKey.value = msg.trackKey
    }

    /** Asks the phone for lyrics of the current track; the answer arrives via [lyricsState]. */
    fun requestLyrics(provider: LyricsProvider) {
        val s = _relaySession.value
        val key = lyricsTrackKey(s?.title, s?.artist, s?.durationMs ?: 0L)
        lyricsWatchdog?.cancel()
        if (s == null || s.title.isNullOrBlank()) {
            requestedLyricsKey = null
            _lyricsState.value = LyricsUiState.None(key)
            return
        }
        requestedLyricsKey = key
        requestedLyricsProvider = provider
        _lyricsState.value = LyricsUiState.Loading(key)
        // The phone answers every request; this only covers a disconnected phone.
        lyricsWatchdog = scope.launch {
            delay(LYRICS_TIMEOUT_MS)
            if (requestedLyricsKey == key && _lyricsState.value is LyricsUiState.Loading) {
                _lyricsState.value = LyricsUiState.None(key)
            }
        }
        sendCommand(RelayCommand.RequestLyrics(provider))
    }

    /**
     * Tells the phone whether a lyrics screen is live. Going inactive makes the phone drop any
     * unfinished fetch, so a pending request is forgotten here too; reopening asks again.
     */
    fun setLyricsActive(active: Boolean) {
        if (!active && _lyricsState.value is LyricsUiState.Loading) {
            lyricsWatchdog?.cancel()
            requestedLyricsKey = null
            _lyricsState.value = LyricsUiState.Idle
        }
        sendCommand(RelayCommand.LyricsActive(active))
    }

    /** Requests lyrics unless this exact track+provider is already loaded or in flight. */
    fun ensureLyrics(provider: LyricsProvider) {
        val s = _relaySession.value
        val key = lyricsTrackKey(s?.title, s?.artist, s?.durationMs ?: 0L)
        val state = _lyricsState.value
        val covered = requestedLyricsKey == key && requestedLyricsProvider == provider &&
            (state is LyricsUiState.Ready || state is LyricsUiState.Loading)
        if (!covered) requestLyrics(provider)
    }

    private suspend fun processActions(map: DataMap) {
        val ids = map.getStringArrayList(RelayProtocol.KEY_ACT_IDS) ?: return
        val labels = map.getStringArrayList(RelayProtocol.KEY_ACT_LABELS) ?: return
        if (ids.size != labels.size) return
        val next = ids.indices.map { i ->
            val icon = map.getAsset(RelayProtocol.KEY_ACT_ICON_PREFIX + i)?.let { asset ->
                try {
                    Wearable.getDataClient(appContext).getFdForAsset(asset).await().inputStream.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
            }
            SessionAction(ids[i], labels[i], icon)
        }
        if (next != _sessionActions.value) _sessionActions.value = next
    }

    private suspend fun processLyrics(map: DataMap) {
        val key = map.getString(RelayProtocol.KEY_LYRICS_TRACK) ?: return
        val requested = map.getString(RelayProtocol.KEY_LYRICS_REQUESTED) ?: return
        if (key != requestedLyricsKey || requested != requestedLyricsProvider?.name) {
            Log.d(TAG, "ignoring stale lyrics '$key' via $requested")
            return
        }
        val asset = map.getAsset(RelayProtocol.KEY_LYRICS_PAYLOAD)
        val lyrics = if (asset == null) null else try {
            val fd = Wearable.getDataClient(appContext).getFdForAsset(asset).await()
            LyricsCodec.decode(fd.inputStream.use { it.readBytes() })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load lyrics asset: ${e.message}")
            null
        }
        lyricsWatchdog?.cancel()
        _lyricsState.value = if (lyrics == null) LyricsUiState.None(key) else LyricsUiState.Ready(key, lyrics)
    }

    private suspend fun prefetch(path: String, handle: (DataMap) -> Unit) {
        try {
            val uri = Uri.parse("wear://*$path")
            val items: DataItemBuffer = Wearable.getDataClient(appContext).getDataItems(uri).await()
            Log.i(TAG, "Initial getDataItems $path count: ${items.count}")
            for (item in items) handle(DataMapItem.fromDataItem(item).dataMap)
            items.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query initial DataItems $path: ${e.message}")
        }
    }

    private fun processApps(map: DataMap) {
        val pkgs = map.getStringArrayList(RelayProtocol.KEY_APP_PKGS) ?: return
        val labels = map.getStringArrayList(RelayProtocol.KEY_APP_LABELS) ?: return
        if (pkgs.size != labels.size) return
        val next = pkgs.indices.map { PhoneAppInfo(pkgs[it], labels[it]) }
        if (next != _phoneApps.value) _phoneApps.value = next
    }

    private fun processIcons(map: DataMap) {
        val assets = HashMap<String, Asset>()
        for (key in map.keySet()) {
            if (key == RelayProtocol.KEY_ICONS_REV) continue
            map.getAsset(key)?.let { assets[key] = it }
        }
        if (assets.isEmpty()) return
        iconAssets = assets
        iconCache.clear()
        Log.i(TAG, "received ${assets.size} app icons")
    }

    /**
     * PNG bytes for a phone package, fetched from its Asset on first use and cached.
     * Null while the catalog has not arrived, or for apps the phone had no icon for.
     */
    suspend fun appIcon(packageName: String): ByteArray? {
        iconCache[packageName]?.let { return it }
        val asset = iconAssets[packageName] ?: return null
        return try {
            val fd = Wearable.getDataClient(appContext).getFdForAsset(asset).await()
            val bytes = fd.inputStream.use { it.readBytes() }
            iconCache[packageName] = bytes
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load icon for $packageName: ${e.message}")
            null
        }
    }

    private fun processDataMap(map: DataMap) {
        val session = mapToSession(map)
        val now = SystemClock.elapsedRealtime()
        val prev = lastSession
        if (shouldPublishSession(prev, session, now - lastSessionAt)) {
            lastSession = session
            lastSessionAt = now
            _relaySession.value = session
        }

        val vol = map.getInt(RelayProtocol.KEY_VOLUME, -1)
        val maxVol = map.getInt(RelayProtocol.KEY_MAX_VOLUME, -1)
        if (vol >= 0) _volume.value = vol
        if (maxVol > 0) _maxVolume.value = maxVol

        val qTitles = map.getStringArrayList(RelayProtocol.KEY_QUEUE)
        val qArtists = map.getStringArrayList(RelayProtocol.KEY_QUEUE_ARTISTS)
        val qIds = map.getLongArray(RelayProtocol.KEY_QUEUE_IDS)
        if (qTitles != null && qIds != null && qTitles.size == qIds.size) {
            val next = qIds.indices.map { i ->
                val artist = qArtists?.getOrNull(i)?.takeIf { it.isNotEmpty() }
                QueueTrack(qIds[i], qTitles[i], artist)
            }
            if (next != _queue.value) _queue.value = next
        }
        val qTitle = map.getString(RelayProtocol.KEY_QUEUE_TITLE)
        if (!qTitle.isNullOrEmpty()) {
            _queueTitle.value = qTitle
        }

        val livePkgs = map.getStringArrayList(RelayProtocol.KEY_LIVE_PKGS)
        val liveLabels = map.getStringArrayList(RelayProtocol.KEY_LIVE_LABELS)
        val livePlaying = map.getIntegerArrayList(RelayProtocol.KEY_LIVE_PLAYING)
        if (livePkgs != null && liveLabels != null && livePkgs.size == liveLabels.size) {
            val next = livePkgs.indices.map { i ->
                SessionCandidate(
                    packageName = livePkgs[i],
                    appLabel = liveLabels[i],
                    isPlaying = livePlaying?.getOrNull(i) == 1,
                    updateTimeMs = 0L,
                )
            }
            if (next != _liveSessions.value) _liveSessions.value = next
        }

        val btName = map.getString(RelayProtocol.KEY_BT_DEVICE)?.takeIf { it.isNotEmpty() }
        val btConnected = map.getBoolean(RelayProtocol.KEY_BT_CONNECTED, false)
        val bt = BtAudioState(btName, btConnected, if (btConnected) "A2DP" else null)
        if (bt != _btAudioState.value) _btAudioState.value = bt

        val scMode = map.getString(RelayProtocol.KEY_SOUNDCORE_MODE).orEmpty()
        val scStatus = SoundcoreStatus(
            mode = runCatching { SoundcoreMode.valueOf(scMode) }.getOrNull(),
            error = map.getString(RelayProtocol.KEY_SOUNDCORE_ERROR).orEmpty(),
        )
        if (scStatus != _soundcore.value) _soundcore.value = scStatus

        val artAsset = map.getAsset(RelayProtocol.KEY_ART_ASSET)
        if (artAsset == null) {
            if (_artworkBytes.value != null) _artworkBytes.value = null
            return
        }
        val sameTrack = prev != null && session != null &&
            prev.packageName == session.packageName &&
            prev.title == session.title &&
            prev.artist == session.artist
        if (sameTrack && _artworkBytes.value != null) return
        scope.launch {
            try {
                val fd = Wearable.getDataClient(appContext).getFdForAsset(artAsset).await()
                val bytes = fd.inputStream.use { it.readBytes() }
                val current = _artworkBytes.value
                if (current == null || !current.contentEquals(bytes)) {
                    _artworkBytes.value = bytes
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load artwork asset: ${e.message}")
            }
        }
    }

    fun sendCommand(cmd: RelayCommand) {
        scope.launch {
            val payload = cmd.encode()
            var id = cachedNodeId
            if (id == null) {
                id = try {
                    Wearable.getNodeClient(appContext).connectedNodes.await().firstOrNull()?.id
                } catch (e: Exception) {
                    null
                }
                cachedNodeId = id
            }
            if (id == null) return@launch
            try {
                Wearable.getMessageClient(appContext)
                    .sendMessage(id, RelayProtocol.PATH_MEDIA_CMD, payload)
                    .await()
            } catch (e: Exception) {
                cachedNodeId = null
                val retry = try {
                    Wearable.getNodeClient(appContext).connectedNodes.await().firstOrNull()?.id
                } catch (e2: Exception) {
                    null
                }
                if (retry != null) {
                    cachedNodeId = retry
                    try {
                        Wearable.getMessageClient(appContext)
                            .sendMessage(retry, RelayProtocol.PATH_MEDIA_CMD, payload)
                            .await()
                    } catch (e3: Exception) {
                        Log.e(TAG, "Failed to send command: ${e3.message}")
                    }
                }
            }
        }
    }

    private fun mapToSession(map: DataMap): ControlledSession? {
        if (!map.getBoolean(RelayProtocol.KEY_HAS_SESSION, false)) return null
        val pkg = map.getString(RelayProtocol.KEY_PKG)?.takeIf { it.isNotEmpty() } ?: return null
        return ControlledSession(
            packageName = pkg,
            appLabel = pkg,
            title = map.getString(RelayProtocol.KEY_TITLE)?.takeIf { it.isNotEmpty() },
            artist = map.getString(RelayProtocol.KEY_ARTIST)?.takeIf { it.isNotEmpty() },
            artworkUri = null,
            isPlaying = map.getBoolean(RelayProtocol.KEY_PLAYING, false),
            positionMs = map.getLong(RelayProtocol.KEY_POS, 0L),
            durationMs = map.getLong(RelayProtocol.KEY_DUR, 0L),
        )
    }
}
