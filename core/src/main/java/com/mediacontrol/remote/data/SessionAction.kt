package com.mediacontrol.remote.data

/**
 * A player-specific control (favourite, shuffle, ...) a media session exposes as a
 * PlaybackState custom action. [icon] is a small PNG, or null when the app's resources
 * were not readable; [id] is the session's action string.
 */
class SessionAction(val id: String, val label: String, val icon: ByteArray?) {
    override fun equals(other: Any?): Boolean =
        other is SessionAction && id == other.id && label == other.label &&
            (icon === other.icon || (icon != null && other.icon != null && icon.contentEquals(other.icon)))

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + label.hashCode()) + (icon?.contentHashCode() ?: 0)

    override fun toString(): String = "SessionAction($id, $label, icon=${icon?.size ?: 0}B)"
}
