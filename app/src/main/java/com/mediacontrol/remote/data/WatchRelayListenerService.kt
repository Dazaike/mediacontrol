package com.mediacontrol.remote.data

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.mediacontrol.remote.MediaRemoteApp

/** Forwards `/media-state` DataItem changes from the phone into [PhoneRelaySource]. */
class WatchRelayListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        (application as MediaRemoteApp).phoneRelay.onDataChanged(dataEvents)
    }
}
