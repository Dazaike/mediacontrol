package com.mediacontrol.remote.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.mediacontrol.remote.MainActivity
import com.mediacontrol.remote.MediaRemoteApp
import com.mediacontrol.remote.data.ControlledSession

/**
 * Play/pause glyph complication (SHORT_TEXT + MONOCHROMATIC_IMAGE). Tap opens Now
 * Playing. Single provider; no template stub existed to retire.
 */
class MediaComplicationService : ComplicationDataSourceService() {
    private fun mediaSource() = (application as MediaRemoteApp).mediaSource
    private fun tapAction(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val data = try {
            buildData(request.complicationType)
        } catch (e: Exception) {
            null
        }
        try {
            listener.onComplicationData(data)
        } catch (e: Exception) {
            // System gone; nothing to deliver to.
        }
    }

    private fun buildData(type: ComplicationType): ComplicationData? {
        val session = mediaSource().activeSession.value
        return when (type) {
            ComplicationType.SHORT_TEXT -> shortText(session)
            ComplicationType.MONOCHROMATIC_IMAGE -> icon(session)
            else -> null
        }
    }

    private fun shortText(session: ControlledSession?): ComplicationData {
        if (session == null) return NoDataComplicationData()
        val label = session.title ?: session.appLabel
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(if (session.isPlaying) "▶" else "⏸").build(),
            contentDescription = PlainComplicationText.Builder(label).build(),
        )
            .setTitle(PlainComplicationText.Builder(label).build())
            .setTapAction(tapAction())
            .build()
    }

    private fun icon(session: ControlledSession?): ComplicationData {
        val res = if (session?.isPlaying == true) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        return MonochromaticImageComplicationData.Builder(
            monochromaticImage = MonochromaticImage.Builder(Icon.createWithResource(this, res)).build(),
            contentDescription = PlainComplicationText.Builder(session?.title ?: "Media Remote").build(),
        )
            .setTapAction(tapAction())
            .build()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        try {
            when (type) {
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("▶").build(),
                    contentDescription = PlainComplicationText.Builder("Media Remote").build(),
                )
                    .setTitle(PlainComplicationText.Builder("Song").build())
                    .build()
                ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                    monochromaticImage = MonochromaticImage.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_play),
                    ).build(),
                    contentDescription = PlainComplicationText.Builder("Media Remote").build(),
                ).build()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
}
