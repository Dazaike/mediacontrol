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

private const val TAG = "PhoneRelaySource"

/** An installed media app on the phone, offered on the watch's Add-players screen. */
data class PhoneAppInfo(val packageName: String, val label: String)

/**
 * Watch-side half of the phone companion: receives `/media-state` DataItem updates
 * (session, volume, and queue) and sends `/media-cmd` messages to the phone.
 */
class PhoneRelaySource(private val appContext: Context) : DataClient.OnDataChangedListener {

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
                prefetch(RelayProtocol.PATH_MEDIA_STATE) { processDataMap(it) }
                prefetch(RelayProtocol.PATH_MEDIA_APPS) { processApps(it) }
                prefetch(RelayProtocol.PATH_APP_ICONS) { processIcons(it) }
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
                    }
                }
            }
        }
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
