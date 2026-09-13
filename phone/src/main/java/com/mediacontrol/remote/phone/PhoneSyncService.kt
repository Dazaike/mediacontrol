package com.mediacontrol.remote.phone

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.mediacontrol.remote.relay.RelayCommand
import com.mediacontrol.remote.relay.RelayProtocol

/** Receives `/media-cmd` messages from the watch and dispatches them to [PhoneApp]. */
class PhoneSyncService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != RelayProtocol.PATH_MEDIA_CMD) return
        val cmd = RelayCommand.decode(event.data) ?: return
        (application as PhoneApp).handleCommand(cmd)
    }
}
