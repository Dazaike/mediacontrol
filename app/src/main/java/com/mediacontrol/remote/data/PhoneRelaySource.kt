package com.mediacontrol.remote.data

import android.content.Context
import android.net.Uri
import android.util.Log
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
        Log.i(TAG, "onDataChanged called with ${dataEvents.count} events")
        for (event in dataEvents) {
            if (event.dataItem.uri.path != RelayProtocol.PATH_MEDIA_STATE) continue
            if (event.type == DataEvent.TYPE_CHANGED) {
                processDataMap(DataMapItem.fromDataItem(event.dataItem).dataMap)
            } else {
                _relaySession.value = null
            }
        }
    }

    private fun processDataMap(map: DataMap) {
        val session = mapToSession(map)
        Log.i(TAG, "ProcessDataMap: session=${session?.title}, vol=${map.getInt(RelayProtocol.KEY_VOLUME)}")
        _relaySession.value = session

        val vol = map.getInt(RelayProtocol.KEY_VOLUME, -1)
        val maxVol = map.getInt(RelayProtocol.KEY_MAX_VOLUME, -1)
        if (vol >= 0) _volume.value = vol
        if (maxVol > 0) _maxVolume.value = maxVol

        val qTitles = map.getStringArrayList(RelayProtocol.KEY_QUEUE)
        val qArtists = map.getStringArrayList(RelayProtocol.KEY_QUEUE_ARTISTS)
        val qIds = map.getLongArray(RelayProtocol.KEY_QUEUE_IDS)
        if (qTitles != null && qIds != null && qTitles.size == qIds.size) {
            _queue.value = qIds.indices.map { i ->
                val artist = qArtists?.getOrNull(i)?.takeIf { it.isNotEmpty() }
                QueueTrack(qIds[i], qTitles[i], artist)
            }
        }
        val qTitle = map.getString(RelayProtocol.KEY_QUEUE_TITLE)
        if (!qTitle.isNullOrEmpty()) {
            _queueTitle.value = qTitle
        }

        val livePkgs = map.getStringArrayList(RelayProtocol.KEY_LIVE_PKGS)
        val liveLabels = map.getStringArrayList(RelayProtocol.KEY_LIVE_LABELS)
        val livePlaying = map.getIntegerArrayList(RelayProtocol.KEY_LIVE_PLAYING)
        if (livePkgs != null && liveLabels != null && livePkgs.size == liveLabels.size) {
            _liveSessions.value = livePkgs.indices.map { i ->
                SessionCandidate(
                    packageName = livePkgs[i],
                    appLabel = liveLabels[i],
                    isPlaying = livePlaying?.getOrNull(i) == 1,
                    updateTimeMs = 0L,
                )
            }
        }

        val btName = map.getString(RelayProtocol.KEY_BT_DEVICE)?.takeIf { it.isNotEmpty() }
        val btConnected = map.getBoolean(RelayProtocol.KEY_BT_CONNECTED, false)
        _btAudioState.value = BtAudioState(btName, btConnected, if (btConnected) "A2DP" else null)

        val artAsset = map.getAsset(RelayProtocol.KEY_ART_ASSET)
        if (artAsset != null) {
            scope.launch {
                try {
                    val fd = Wearable.getDataClient(appContext).getFdForAsset(artAsset).await()
                    val bytes = fd.inputStream.use { it.readBytes() }
                    _artworkBytes.value = bytes
                    Log.i(TAG, "Loaded artwork asset: ${bytes.size} bytes")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load artwork asset: ${e.message}")
                }
            }
        } else {
            _artworkBytes.value = null
        }
    }

    fun sendCommand(cmd: RelayCommand) {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(appContext).connectedNodes.await()
                Log.i(TAG, "Sending command $cmd to ${nodes.size} connected nodes")
                val payload = cmd.encode()
                nodes.forEach { node ->
                    try {
                        Wearable.getMessageClient(appContext)
                            .sendMessage(node.id, RelayProtocol.PATH_MEDIA_CMD, payload)
                            .await()
                        Log.i(TAG, "Sent command $cmd to node ${node.displayName} (${node.id})")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send command to node ${node.id}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query nodes: ${e.message}")
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
