package com.example.eikokiosk.device

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DeviceAdminReceiver for the Kiosk app.
 *
 * This receiver handles device administration events. When provisioned as
 * Device Owner via ADB, it enables the app to use privileged APIs like
 * Lock Task Mode and silent package installation.
 *
 * Provisioning command:
 *   adb shell dpm set-device-owner com.example.eikokiosk/.device.KioskDeviceAdminReceiver
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "KioskDeviceAdmin"

        /**
         * Returns the ComponentName for this receiver, used when calling
         * DevicePolicyManager APIs.
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, KioskDeviceAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning complete — entering kiosk mode")

        // Auto-enter kiosk mode after provisioning
        val kioskManager = KioskModeManager(context)
        kioskManager.enterKioskMode()
    }
}
