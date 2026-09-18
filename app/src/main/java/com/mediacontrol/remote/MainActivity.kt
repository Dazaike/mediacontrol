package com.mediacontrol.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mediacontrol.remote.relay.RelayCommand
import com.mediacontrol.remote.ui.NavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MediaRemoteApp
        app.phoneRelay.sendCommand(RelayCommand.Subscribe)
        setContent {
            NavGraph(mediaSource = app.mediaSource)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as MediaRemoteApp).phoneRelay.sendCommand(RelayCommand.Unsubscribe)
    }
}
