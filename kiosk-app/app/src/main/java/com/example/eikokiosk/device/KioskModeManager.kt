package com.example.eikokiosk.device

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log

/**
 * Manages Kiosk (Lock Task) mode using DevicePolicyManager.
 *
 * This class wraps all interactions with [DevicePolicyManager] to:
 * - Enter and exit Lock Task mode (pins the app to the screen)
 * - Set lock task features (disables status bar, notifications, home, overview, etc.)
 * - Apply user restrictions (disallow safe boot, factory reset, adding users, etc.)
 *
 * Requires the app to be provisioned as Device Owner.
 */
class KioskModeManager(private val context: Context) {

    companion object {
        private const val TAG = "KioskModeManager"
    }

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent = KioskDeviceAdminReceiver.getComponentName(context)

    /**
     * Returns true if this app is the Device Owner.
     */
    fun isDeviceOwner(): Boolean {
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Enters full kiosk mode:
     * 1. Registers this package for Lock Task
     * 2. Sets lock task features to disable system UI
     * 3. Applies user restrictions
     * 4. Starts lock task on the current activity (if provided)
     */
    fun enterKioskMode(activity: Activity? = null) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot enter kiosk mode — app is not Device Owner")
            return
        }

        try {
            // Step 1: Allow this package to enter Lock Task mode
            dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))

            // Step 2: Configure which system UI features are allowed in Lock Task
            // Hide the system status bar completely since we are building a custom one
            dpm.setLockTaskFeatures(
                adminComponent,
                DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            )

            // Step 3: Apply user restrictions to prevent escape
            setUserRestrictions(true)

            // Step 4: Start Lock Task on the activity if provided
            activity?.startLockTask()

            Log.i(TAG, "Kiosk mode entered successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to enter kiosk mode: ${e.message}", e)
        }
    }

    /**
     * Exits kiosk mode — should only be called after Master PIN verification.
     */
    fun exitKioskMode(activity: Activity? = null) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot exit kiosk mode — app is not Device Owner")
            return
        }

        try {
            // Stop lock task
            activity?.stopLockTask()

            // Remove user restrictions
            setUserRestrictions(false)

            // Clear lock task packages
            dpm.setLockTaskPackages(adminComponent, arrayOf())

            Log.i(TAG, "Kiosk mode exited successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to exit kiosk mode: ${e.message}", e)
        }
    }

    /**
     * Adds approved packages to the Lock Task allowlist so they can be launched
     * from within kiosk mode without escaping the lockdown.
     */
    fun setAllowedLockTaskPackages(packages: List<String>) {
        if (!isDeviceOwner()) return

        val allPackages = (packages + context.packageName).toTypedArray()
        dpm.setLockTaskPackages(adminComponent, allPackages)
        Log.i(TAG, "Lock task packages set: ${allPackages.joinToString()}")
    }

    /**
     * Applies or clears user restrictions to prevent device escape.
     */
    private fun setUserRestrictions(enable: Boolean) {
        val restrictions = arrayOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_NETWORK_RESET
        )

        for (restriction in restrictions) {
            if (enable) {
                dpm.addUserRestriction(adminComponent, restriction)
            } else {
                dpm.clearUserRestriction(adminComponent, restriction)
            }
        }
        
        // Explicitly clear WiFi and Bluetooth config restrictions just in case 
        // they were set by a previous installation of the Kiosk app
        if (enable) {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_BLUETOOTH)
        }
    }
}
