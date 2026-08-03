package com.example.eikokiosk.device

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Manages the unique identity of this kiosk device.
 *
 * On first boot, generates a UUID and persists it securely.
 * Also collects device metadata (model, Android version, kiosk app version)
 * for registration in Firestore.
 */
class DeviceIdentityManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceIdentity"
        private const val PREFS_FILE = "device_identity_prefs"
        private const val KEY_DEVICE_UUID = "device_uuid"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Returns the device UUID. Generates and persists one on first call.
     */
    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_UUID, null)
        if (existing != null) return existing

        val newUuid = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_UUID, newUuid).apply()
        Log.i(TAG, "Generated new device UUID: $newUuid")
        return newUuid
    }

    /**
     * Returns the device model name (e.g., "Samsung SM-A125F").
     */
    fun getDeviceModel(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /**
     * Returns the Android version string (e.g., "12").
     */
    fun getAndroidVersion(): String {
        return Build.VERSION.RELEASE
    }

    fun getKioskAppVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Returns the hardware Serial Number. Requires READ_PHONE_STATE.
     * Allowed for Device Owner apps.
     */
    fun getSerialNumber(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Build.getSerial()
                } else {
                    "Permission Denied"
                }
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        } catch (e: Exception) {
            "Unknown/Restricted"
        }
    }

    /**
     * Returns the device IMEI. Requires READ_PHONE_STATE.
     */
    fun getImei(): String {
        return try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    telephonyManager.imei ?: "Unknown"
                } else {
                    @Suppress("DEPRECATION")
                    telephonyManager.deviceId ?: "Unknown"
                }
            } else {
                "Permission Denied"
            }
        } catch (e: Exception) {
            "Unknown/Restricted"
        }
    }
}
