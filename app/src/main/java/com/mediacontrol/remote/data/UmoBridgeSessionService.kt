package com.mediacontrol.remote.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.mediacontrol.remote.MainActivity
import com.mediacontrol.remote.MediaRemoteApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Publishes a local [MediaSession] that mirrors phone relay state so Pixel UMO
 * (`com.kingm.pixel.media.sessions`) can control the phone through this app.
 */
class UmoBridgeSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var session: MediaSession? = null
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastPlaying: Boolean? = null
    private var lastArt: ByteArray? = null
    private var lastBitmap: android.graphics.Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val media = MediaSession(this, SESSION_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    source().send(RelayPlay)
                }
                override fun onPause() {
                    source().send(RelayPause)
                }
                override fun onSkipToNext() {
                    source().send(RelayNext)
                }
                override fun onSkipToPrevious() {
                    source().send(RelayPrev)
                }
                override fun onSeekTo(pos: Long) {
                    source().mediaSource.let { src ->
                        scope.launch { src.seekTo(pos) }
                    }
                }
            })
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            isActive = true
        }
        session = media
        startForeground(NOTIF_ID, buildNotification(media, null, null))
        collectJob = scope.launch(Dispatchers.Default) {
            val src = source().mediaSource
            combine(src.activeSession, src.artworkBytes) { sess, art -> sess to art }
                .collect { (sess, art) ->
                    applySession(sess, art)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        collectJob?.cancel()
        lastBitmap = null
        lastArt = null
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun source(): MediaRemoteApp = application as MediaRemoteApp

    private suspend fun applySession(sess: ControlledSession?, art: ByteArray?) {
        val media = session ?: return
        if (sess == null) {
            withContext(Dispatchers.Main.immediate) {
                media.setMetadata(MediaMetadata.Builder().build())
                media.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(ACTIONS)
                        .setState(PlaybackState.STATE_NONE, 0L, 0f)
                        .build(),
                )
                stopForegroundCompat()
            }
            stopSelf()
            return
        }

        val title = sess.title ?: sess.appLabel
        val artist = sess.artist
        val playing = sess.isPlaying
        val artSame = art === lastArt || (art != null && lastArt != null && art.contentEquals(lastArt))
        val metaSame = title == lastTitle && artist == lastArtist && artSame
        val playingSame = playing == lastPlaying
        if (metaSame && playingSame) return

        val bitmap = if (artSame) lastBitmap else art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        lastTitle = title
        lastArtist = artist
        lastPlaying = playing
        lastArt = art
        lastBitmap = bitmap

        withContext(Dispatchers.Main.immediate) {
            if (!metaSame) {
                media.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist ?: sess.appLabel)
                        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, sess.durationMs)
                        .apply {
                            if (bitmap != null) {
                                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                            }
                        }
                        .build(),
                )
            }
            val state = if (sess.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            media.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(ACTIONS)
                    .setState(state, sess.positionMs, if (sess.isPlaying) 1f else 0f, SystemClock.elapsedRealtime())
                    .build(),
            )
            if (!metaSame || !playingSame) {
                startForeground(NOTIF_ID, buildNotification(media, sess, bitmap))
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(
        media: MediaSession,
        sess: ControlledSession?,
        art: android.graphics.Bitmap?,
    ): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(sess?.title ?: "Media Remote")
            .setContentText(sess?.artist ?: "Phone player")
            .setContentIntent(launch)
            .setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (art != null) b.setLargeIcon(art)
        return b.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "UMO bridge", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun MediaRemoteApp.send(kind: Int) {
        val src = mediaSource
        scope.launch {
            when (kind) {
                RelayPlay -> src.play()
                RelayPause -> src.pause()
                RelayNext -> src.next()
                RelayPrev -> src.previous()
            }
        }
    }

    companion object {
        private const val SESSION_TAG = "umo_bridge"
        private const val CHANNEL_ID = "umo_bridge"
        private const val NOTIF_ID = 42
        private const val RelayPlay = 1
        private const val RelayPause = 2
        private const val RelayNext = 3
        private const val RelayPrev = 4
        private const val ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO
    }
}
