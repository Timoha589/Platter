package com.cappielloantonio.tempo.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.IntentCompat
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.cappielloantonio.tempo.util.Preferences

/*
 * The device BluetoothResume waits for has come back, but the process it was
 * waiting in may be long gone. This brings the player back up with the queue
 * it had - which is what the app's own screen would do on opening - and holds
 * it open long enough for the device's audio to arrive. BluetoothResume, alive
 * again inside the player, sees the device and plays.
 *
 * It needs the Bluetooth permission on Android 12 and later; without it the
 * broadcast never comes, and the music resumes only while the app is running.
 */
class BluetoothResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!Preferences.isBluetoothResume()) return
        val awaited = Preferences.getBluetoothResumeDevice() ?: return

        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
        val key = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else device.name
        } catch (e: SecurityException) {
            return
        }
        if (!BluetoothResume.sameDevice(key, awaited)) return

        val pending = goAsync()
        val browser = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, MediaService::class.java)))
                .buildAsync()
        MediaManager.check(browser)

        // The link comes up before the audio does; once playing, the player keeps itself alive.
        Handler(Looper.getMainLooper()).postDelayed({
            MediaBrowser.releaseFuture(browser)
            pending.finish()
        }, HOLD_MS)
    }

    private companion object {
        const val HOLD_MS = 20_000L
    }
}
