package com.mediacontrol.remote.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "PhoneRelaySource"

/**
 * Watch-side half of the phone companion: receives `/media-state` DataItem updates
 * (session, volume, and queue) and sends `/media-cmd` messages to the phone.
 */
class PhoneRelaySource(private val appContext: Context) : DataClient.OnDataChangedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    private var lastSession: ControlledSession? = null
    private var lastSessionAt = 0L
    @Volatile private var cachedNodeId: String? = null

    init {
        Log.i(TAG, "Initializing PhoneRelaySource")
        try {
            Wearable.getDataClient(appContext).addListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register DataClient listener: ${e.message}")
        }
        // Fetch existing data item immediately:
        scope.launch {
            try {
                val uri = Uri.parse("wear://*${RelayProtocol.PATH_MEDIA_STATE}")
                val items: DataItemBuffer = Wearable.getDataClient(appContext).getDataItems(uri).await()
                Log.i(TAG, "Initial getDataItems count: ${items.count}")
                for (item in items) {
                    processDataMap(DataMapItem.fromDataItem(item).dataMap)
                }
                items.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query initial DataItems: ${e.message}")
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.dataItem.uri.path != RelayProtocol.PATH_MEDIA_STATE) continue
            if (event.type == DataEvent.TYPE_CHANGED) {
                processDataMap(DataMapItem.fromDataItem(event.dataItem).dataMap)
            } else {
                lastSession = null
                _relaySession.value = null
            }
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
