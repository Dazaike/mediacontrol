package com.mediacontrol.remote.relay

/**
 * Wire contract between the watch and phone apps over the Wearable Data Layer.
 * Frozen shape: /media-state is phone→watch state (DataItem), /media-cmd is
 * watch→phone commands (Message). Phone state is read-only display source; watch
 * commands always win on arrival (no merge).
 */
object RelayProtocol {
    const val PATH_MEDIA_STATE = "/media-state"
    const val PATH_MEDIA_CMD = "/media-cmd"

    const val KEY_HAS_SESSION = "hasSession"
    const val KEY_PKG = "pkg"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_PLAYING = "playing"
    const val KEY_POS = "pos"
    const val KEY_DUR = "dur"

    // Volume sync from phone to watch
    const val KEY_VOLUME = "volume"
    const val KEY_MAX_VOLUME = "maxVolume"

    // Queue sync from phone to watch
    const val KEY_QUEUE = "queue"
    const val KEY_QUEUE_ARTISTS = "queueArtists"
    const val KEY_QUEUE_IDS = "queueIds"
    const val KEY_QUEUE_TITLE = "queueTitle"
    // Bluetooth audio device sync from phone to watch
    const val KEY_BT_DEVICE = "btDevice"
    const val KEY_BT_CONNECTED = "btConnected"
    // Album art Asset sync from phone to watch
    const val KEY_ART_ASSET = "artAsset"
    // Every live audible session on the phone (not just the ranked-top one), so the
    // watch's Players screen can list apps beyond the fixed known-player presets.
    const val KEY_LIVE_PKGS = "livePkgs"
    const val KEY_LIVE_LABELS = "liveLabels"
    const val KEY_LIVE_PLAYING = "livePlaying"
}

/**
 * Commands the watch sends the phone at [RelayProtocol.PATH_MEDIA_CMD]. Encoded as a
 * plain `name|arg` byte payload.
 */
sealed class RelayCommand {
    object Play : RelayCommand()
    object Pause : RelayCommand()
    object Toggle : RelayCommand()
    object Next : RelayCommand()
    object Previous : RelayCommand()
    data class Seek(val posMs: Long) : RelayCommand()
    data class Select(val pkg: String) : RelayCommand()
    object VolumeUp : RelayCommand()
    object VolumeDown : RelayCommand()
    data class SetVolume(val volume: Int) : RelayCommand()
    data class SkipToQueueItem(val queueId: Long) : RelayCommand()

    companion object {
        fun decode(bytes: ByteArray): RelayCommand? = try {
            val text = String(bytes, Charsets.UTF_8)
            val separator = text.indexOf('|')
            val name = if (separator >= 0) text.substring(0, separator) else text
            val arg = if (separator >= 0) text.substring(separator + 1) else null
            when (name) {
                "play" -> Play
                "pause" -> Pause
                "toggle" -> Toggle
                "next" -> Next
                "previous" -> Previous
                "seek" -> arg?.toLongOrNull()?.let { Seek(it) }
                "select" -> arg?.takeIf { it.isNotEmpty() }?.let { Select(it) }
                "volUp" -> VolumeUp
                "volDown" -> VolumeDown
                "setVol" -> arg?.toIntOrNull()?.let { SetVolume(it) }
                "skipQueue" -> arg?.toLongOrNull()?.let { SkipToQueueItem(it) }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

fun RelayCommand.encode(): ByteArray {
    val text = when (this) {
        is RelayCommand.Play -> "play"
        is RelayCommand.Pause -> "pause"
        is RelayCommand.Toggle -> "toggle"
        is RelayCommand.Next -> "next"
        is RelayCommand.Previous -> "previous"
        is RelayCommand.Seek -> "seek|$posMs"
        is RelayCommand.Select -> "select|$pkg"
        is RelayCommand.VolumeUp -> "volUp"
        is RelayCommand.VolumeDown -> "volDown"
        is RelayCommand.SetVolume -> "setVol|$volume"
        is RelayCommand.SkipToQueueItem -> "skipQueue|$queueId"
    }
    return text.toByteArray(Charsets.UTF_8)
}
