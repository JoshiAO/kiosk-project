package com.example.eikokiosk.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED broadcast to re-enter kiosk mode after device restart.
 *
 * When the device boots, this receiver:
 * 1. Checks if the app is Device Owner
 * 2. Re-enters Lock Task mode
 * 3. Launches the Lock Screen activity
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompleted"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — re-entering kiosk mode")

        val kioskManager = KioskModeManager(context)
        if (kioskManager.isDeviceOwner()) {
            kioskManager.enterKioskMode()

            // Launch the lock screen
            val lockScreenIntent = Intent().apply {
                setClassName(
                    context.packageName,
                    "com.example.eikokiosk.lockscreen.LockScreenActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(lockScreenIntent)
        } else {
            Log.w(TAG, "App is not Device Owner — skipping kiosk mode")
        }
    }
}
