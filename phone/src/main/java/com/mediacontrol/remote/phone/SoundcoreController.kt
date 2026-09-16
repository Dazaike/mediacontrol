package com.mediacontrol.remote.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.mediacontrol.remote.data.BluetoothAudioMonitor
import com.mediacontrol.remote.soundcore.CMD_SET_SOUND_MODES
import com.mediacontrol.remote.soundcore.CMD_SOUND_MODE_UPDATE
import com.mediacontrol.remote.soundcore.CMD_STATE
import com.mediacontrol.remote.soundcore.SoundcoreFrame
import com.mediacontrol.remote.soundcore.SoundcoreFrameReader
import com.mediacontrol.remote.soundcore.SoundcoreMode
import com.mediacontrol.remote.soundcore.SoundcoreProfiles
import com.mediacontrol.remote.soundcore.SoundcoreStatus
import com.mediacontrol.remote.soundcore.buildPacket
import com.mediacontrol.remote.soundcore.legacyV1Body
import com.mediacontrol.remote.soundcore.requestStatePacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

private const val TAG = "SoundcoreController"

/** Soundcore vendor RFCOMM service; only the last 20 bits vary between models. */
private val VENDOR_BASE = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e700000")
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
private const val VENDOR_LSB_MASK = -0x100000L

private const val STATE_TIMEOUT_MS = 1_500L
private const val ACK_TIMEOUT_MS = 1_500L
private const val ACK_RETRY_TIMEOUT_MS = 1_000L

/**
 * Drives the ambient-sound mode on connected Soundcore earbuds directly over RFCOMM —
 * no third-party app involved. Reads the device's current sound-mode struct first so
 * the user's ANC strength / adaptive settings survive the write.
 */
class SoundcoreController(
    private val context: Context,
    private val btMonitor: BluetoothAudioMonitor,
) {
    // Rapid taps serialize instead of racing one socket.
    private val mutex = Mutex()

    suspend fun apply(mode: SoundcoreMode): SoundcoreStatus = mutex.withLock {
        withContext(Dispatchers.IO) { applyLocked(mode) }
    }

    @SuppressLint("MissingPermission")
    private fun applyLocked(mode: SoundcoreMode): SoundcoreStatus {
        if (!hasBtConnect()) return SoundcoreStatus(null, "Bluetooth permission needed")

        val device = btMonitor.connectedDevices().firstOrNull { SoundcoreProfiles.isSoundcore(deviceName(it)) }
            ?: return SoundcoreStatus(null, "Earbuds not connected")
        val name = deviceName(device)
        val profile = SoundcoreProfiles.forName(name)
        Log.i(TAG, "apply $mode to $name profile=${profile?.label ?: "legacy"}")

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        try {
            adapter?.cancelDiscovery()
        } catch (e: Exception) {
            // Discovery may not be running; connecting is what matters.
        }

        val vendorUuid = vendorUuidFor(device)
        var socket: BluetoothSocket? = null
        try {
            socket = connect(device, vendorUuid)
                ?: return SoundcoreStatus(null, "Couldn't connect to earbuds")

            val input = socket.inputStream
            val output = socket.outputStream
            val reader = SoundcoreFrameReader()

            output.write(requestStatePacket())
            output.flush()
            val stateFrame = awaitFrame(input, reader, STATE_TIMEOUT_MS) { it.isCommand(CMD_STATE) }

            val body = stateFrame?.body
                ?.let { state -> profile?.extract(state)?.let { profile.patched(it, mode) } }
                ?: legacyV1Body(mode)

            val packet = buildPacket(CMD_SET_SOUND_MODES, body)
            output.write(packet)
            output.flush()
            var ack = awaitFrame(input, reader, ACK_TIMEOUT_MS) { isAck(it, mode) }
            if (ack == null) {
                output.write(packet)
                output.flush()
                ack = awaitFrame(input, reader, ACK_RETRY_TIMEOUT_MS) { isAck(it, mode) }
            }
            return if (ack != null) {
                SoundcoreStatus(mode, "")
            } else {
                SoundcoreStatus(null, "Earbuds didn't respond")
            }
        } catch (e: Exception) {
            Log.e(TAG, "apply failed: ${e.message}")
            return SoundcoreStatus(null, "Couldn't talk to earbuds")
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Best-effort release.
            }
        }
    }

    private fun isAck(frame: SoundcoreFrame, mode: SoundcoreMode): Boolean =
        frame.isCommand(CMD_SET_SOUND_MODES) ||
            (frame.isCommand(CMD_SOUND_MODE_UPDATE) && frame.body.firstOrNull() == mode.value)

    /**
     * Blocking reads would overrun the deadline, so poll [InputStream.available] instead;
     * unsolicited battery/TWS/codec frames simply fail [match] and are discarded.
     */
    private fun awaitFrame(
        input: InputStream,
        reader: SoundcoreFrameReader,
        timeoutMs: Long,
        match: (SoundcoreFrame) -> Boolean,
    ): SoundcoreFrame? {
        val buf = ByteArray(1024)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (input.available() > 0) {
                val read = input.read(buf)
                if (read <= 0) continue
                reader.append(buf, read).firstOrNull(match)?.let { return it }
            } else {
                Thread.sleep(30)
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, vendorUuid: UUID?): BluetoothSocket? {
        if (vendorUuid != null) {
            try {
                return device.createRfcommSocketToServiceRecord(vendorUuid).also { it.connect() }
            } catch (e: Exception) {
                Log.w(TAG, "Vendor UUID connect failed (${e.message}); falling back to SPP")
            }
        }
        return try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: Exception) {
            Log.e(TAG, "SPP connect failed: ${e.message}")
            null
        }
    }

    /** Android's SDP cache often omits the vendor record; null then means "use SPP". */
    @SuppressLint("MissingPermission")
    private fun vendorUuidFor(device: BluetoothDevice): UUID? = try {
        device.uuids?.map { it.uuid }?.firstOrNull { uuid ->
            uuid.mostSignificantBits == VENDOR_BASE.mostSignificantBits &&
                (uuid.leastSignificantBits and VENDOR_LSB_MASK) ==
                (VENDOR_BASE.leastSignificantBits and VENDOR_LSB_MASK)
        }
    } catch (e: Exception) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: Exception) {
        null
    }

    private fun hasBtConnect(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
