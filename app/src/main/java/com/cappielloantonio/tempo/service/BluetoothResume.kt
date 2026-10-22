package com.cappielloantonio.tempo.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.cappielloantonio.tempo.util.Preferences

/*
 * Picks the music up again when the phone comes back to the Bluetooth device
 * it was taken from - getting back into the car, say.
 *
 * Walking away from the device pauses the player, which treats it like pulled
 * headphones. This remembers which device that was and plays again when that
 * same device returns, screen off or not. Any other device that connects is
 * left alone, and so is a pause the listener made themselves: playing or
 * pausing by hand in between forgets the device.
 *
 * The device is kept in preferences, so it outlives the process; when the
 * process is gone by the time the device returns, BluetoothResumeReceiver
 * brings the player back up and this takes it from there.
 */
class BluetoothResume(
        context: Context,
        private val player: Player,
        // False while casting, when the local player is not the one being listened to.
        private val isLocal: () -> Boolean
) : Player.Listener {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var noisyAt = Long.MIN_VALUE / 2

    // The device came back before the queue did: play as soon as there is something to play.
    private var waitingForQueue = false

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val lost = removedDevices.firstOrNull(::isBluetoothSink) ?: return

            // The device can be reported gone before the player is told to pause, or after.
            if (player.playWhenReady || SystemClock.elapsedRealtime() - noisyAt < NOISY_WINDOW_MS) {
                Preferences.setBluetoothResumeDevice(keyOf(lost))
            }
        }

        // Also called with the devices already there when this is registered.
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val awaited = Preferences.getBluetoothResumeDevice() ?: return
            if (!Preferences.isBluetoothResume() || !isLocal()) return
            if (addedDevices.none { isBluetoothSink(it) && sameDevice(keyOf(it), awaited) }) return

            Preferences.setBluetoothResumeDevice(null)
            if (player.mediaItemCount > 0) resume() else waitingForQueue = true
        }
    }

    init {
        player.addListener(this)
        audioManager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
            noisyAt = SystemClock.elapsedRealtime()
        } else if (playWhenReady || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            // Played again some other way, or paused on purpose: nothing is left to pick up.
            Preferences.setBluetoothResumeDevice(null)
            waitingForQueue = false
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (waitingForQueue && !timeline.isEmpty && isLocal()) {
            waitingForQueue = false
            resume()
        }
    }

    private fun resume() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun release() {
        player.removeListener(this)
        audioManager.unregisterAudioDeviceCallback(devices)
    }

    companion object {
        // How long after the player is told the output went away that a lost device still counts as the cause.
        private const val NOISY_WINDOW_MS = 3_000L

        private fun isBluetoothSink(device: AudioDeviceInfo): Boolean {
            if (!device.isSink) return false
            return when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                else -> false
            }
        }

        // The device's address where the system gives one, its name before that.
        private fun keyOf(device: AudioDeviceInfo): String =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address
                else device.productName.toString()

        /*
         * Without the Bluetooth permission the system hands out addresses with
         * all but the last two bytes masked (XX:XX:XX:XX:12:34), so a masked
         * address is matched on what it shows.
         */
        @JvmStatic
        fun sameDevice(a: String?, b: String?): Boolean {
            if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
            if (a.equals(b, ignoreCase = true)) return true
            val masked = a.startsWith("XX", ignoreCase = true) || b.startsWith("XX", ignoreCase = true)
            return masked && a.length == b.length && a.takeLast(5).equals(b.takeLast(5), ignoreCase = true)
        }
    }
}
