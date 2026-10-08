package com.mediacontrol.remote.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.mediacontrol.remote.data.MediaRemoteRepository
import com.mediacontrol.remote.lyrics.HttpGet
import com.mediacontrol.remote.lyrics.Lyrics
import com.mediacontrol.remote.lyrics.LyricsCodec
import com.mediacontrol.remote.lyrics.LyricsProvider
import com.mediacontrol.remote.lyrics.LyricsQuery
import com.mediacontrol.remote.lyrics.forLyricsSearch
import com.mediacontrol.remote.lyrics.lyricsTrackKey
import com.mediacontrol.remote.lyrics.resolveLyrics
import com.mediacontrol.remote.relay.RelayProtocol
import android.os.SystemClock
import com.mediacontrol.remote.relay.LyricsSyncMsg
import com.mediacontrol.remote.relay.encodeLyricsSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

private const val TAG = "LyricsService"
private const val SYNC_INTERVAL_MS = 1500L
private const val NODE_CACHE_MS = 10_000L
private const val SYNC_RETRY_MS = 10_000L
private const val CACHE_MAX = 20
private const val MAX_RTT_MS = 10_000L

/** Plain `HttpURLConnection` GET; null on any non-200, IO error or timeout. */
private object UrlHttpGet : HttpGet {
    override suspend fun get(url: String, headers: Map<String, String>): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MediaControl-Watch/1.0")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode != 200) return@withContext null
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "GET failed $url: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * Phone half of the watch lyrics feature: scrapes lyrics on request, publishes them as a
 * compact Asset, and streams the live playback position while the watch's lyrics page is open.
 */
class LyricsService(
    private val context: Context,
    private val repo: MediaRemoteRepository,
    private val scope: CoroutineScope,
) {
    /** Results (including misses) per track+provider so re-opening a page does not re-scrape. */
    private val cache = object : LinkedHashMap<String, Lyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > CACHE_MAX
    }

    @Volatile private var fetchJob: Job? = null
    @Volatile private var syncJob: Job? = null
    private var cachedNodeIds: List<String> = emptyList()
    private var nodesFetchedAt = 0L
    /** Smoothed one-way beacon delivery time (half the watch's ack round trip), in ms. */
    @Volatile private var latencyMs = 0L

    /** Watch echoed a beacon stamp: fold the round trip into [latencyMs]. */
    fun onAck(stamp: Long) {
        val rtt = SystemClock.elapsedRealtime() - stamp
        if (rtt < 0 || rtt > MAX_RTT_MS) return
        val oneWay = rtt / 2
        latencyMs = if (latencyMs == 0L) oneWay else (latencyMs * 7 + oneWay * 3) / 10
        Log.d(TAG, "beacon rtt=${rtt}ms -> one-way estimate ${latencyMs}ms")
    }

    fun request(provider: LyricsProvider) {
        fetchJob?.cancel()
        val s = repo.activeSession.value
        val title = s?.title?.takeIf { it.isNotBlank() }
        if (s == null || title == null) {
            fetchJob = scope.launch(Dispatchers.IO) { publish("", provider, null) }
            return
        }
        val trackKey = lyricsTrackKey(s.title, s.artist, s.durationMs)
        val query = LyricsQuery(title.forLyricsSearch(), s.artist.orEmpty().forLyricsSearch(), s.durationMs)
        fetchJob = scope.launch(Dispatchers.IO) {
            val cacheKey = "$trackKey#${provider.name}"
            val hit = synchronized(cache) { if (cache.containsKey(cacheKey)) cache[cacheKey] to true else null to false }
            val result = if (hit.second) hit.first else {
                resolveLyrics(provider, query, UrlHttpGet).also { synchronized(cache) { cache[cacheKey] = it } }
            }
            Log.i(TAG, "lyrics for '$trackKey' via $provider -> ${result?.provider} ${result?.sync} lines=${result?.lines?.size}")
            publish(trackKey, provider, result)
        }
    }

    private suspend fun publish(trackKey: String, requested: LyricsProvider, result: Lyrics?) {
        try {
            val req = PutDataMapRequest.create(RelayProtocol.PATH_LYRICS)
            req.dataMap.putString(RelayProtocol.KEY_LYRICS_TRACK, trackKey)
            req.dataMap.putString(RelayProtocol.KEY_LYRICS_REQUESTED, requested.name)
            req.dataMap.putLong(RelayProtocol.KEY_LYRICS_TS, System.currentTimeMillis())
            if (result != null) {
                req.dataMap.putString(RelayProtocol.KEY_LYRICS_PROVIDER, result.provider.name)
                req.dataMap.putAsset(
                    RelayProtocol.KEY_LYRICS_PAYLOAD,
                    Asset.createFromBytes(LyricsCodec.encode(result)),
                )
            }
            Wearable.getDataClient(context).putDataItem(req.asPutDataRequest().setUrgent()).await()
        } catch (e: Exception) {
            Log.e(TAG, "publish failed: ${e.message}")
        }
    }

    /**
     * Starts/stops position beacons. Idempotent. Stopping also cancels any in-flight fetch:
     * the watch only reports "inactive" when its lyrics screen is gone, so a download nobody
     * is looking at must not carry on.
     */
    fun setActive(active: Boolean) {
        syncJob?.cancel()
        syncJob = null
        if (!active) {
            fetchJob?.cancel()
            fetchJob = null
            return
        }
        syncJob = scope.launch(Dispatchers.IO) {
            // Seeks, pauses and track changes republish the session; push those immediately.
            launch { repo.activeSession.drop(1).collect { sendSync() } }
            while (isActive) {
                // Back off while the watch is unreachable instead of polling every beat.
                delay(if (sendSync()) SYNC_INTERVAL_MS else SYNC_RETRY_MS)
            }
        }
    }

    /** @return false when the beacon could not be delivered (no watch connected, or a send failed). */
    private suspend fun sendSync(): Boolean {
        try {
            val s = repo.activeSession.value ?: return true
            val ids = nodeIds()
            if (ids.isEmpty()) return false
            val stamp = SystemClock.elapsedRealtime()
            val pos = repo.livePositionMs() ?: s.positionMs
            val key = lyricsTrackKey(s.title, s.artist, s.durationMs)
            val bytes = encodeLyricsSync(LyricsSyncMsg(pos, s.isPlaying, latencyMs, stamp, key))
            val client = Wearable.getMessageClient(context)
            for (id in ids) client.sendMessage(id, RelayProtocol.PATH_LYRICS_SYNC, bytes).await()
            Log.d(TAG, "sent /lyrics-sync pos=$pos playing=${s.isPlaying}")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cachedNodeIds = emptyList()
            Log.w(TAG, "sync failed: ${e.message}")
            return false
        }
    }

    private suspend fun nodeIds(): List<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cachedNodeIds.isEmpty() || now - nodesFetchedAt > NODE_CACHE_MS) {
            cachedNodeIds = Wearable.getNodeClient(context).connectedNodes.await().map { it.id }
            nodesFetchedAt = now
        }
        return cachedNodeIds
    }
}
