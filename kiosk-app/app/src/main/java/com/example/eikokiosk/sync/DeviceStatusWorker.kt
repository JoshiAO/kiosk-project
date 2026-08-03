package com.example.eikokiosk.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.*
import com.example.eikokiosk.data.LocalDatabase
import com.example.eikokiosk.device.DeviceIdentityManager
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Sends periodic heartbeat status and event-driven writes to Firestore.
 *
 * Heartbeat Schedule: Every 6 hours.
 * Event-Driven: Immediately triggered for critical events.
 *
 * Payload includes battery level, installed app versions, online status,
 * and the latest event (if any).
 */
class DeviceStatusWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DeviceStatusWorker"
        private const val WORK_NAME_HEARTBEAT = "device_status_heartbeat"

        // Input data keys for event-driven writes
        const val KEY_EVENT_TYPE = "event_type"
        const val KEY_EVENT_DETAILS = "event_details"

        // Event types
        const val EVENT_INSTALL_SUCCESS = "INSTALL_SUCCESS"
        const val EVENT_INSTALL_FAILED = "INSTALL_FAILED"
        const val EVENT_CRASH_ROLLBACK = "CRASH_ROLLBACK"
        const val EVENT_UNAUTHORIZED_EXIT = "UNAUTHORIZED_EXIT"
        const val EVENT_PIN_LOCKOUT = "PIN_LOCKOUT"

        /**
         * Enqueues the periodic heartbeat worker (every 6 hours).
         */
        fun enqueueHeartbeat(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<DeviceStatusWorker>(
                6, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME_HEARTBEAT,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.i(TAG, "Heartbeat enqueued (every 6 hours)")
        }

        /**
         * Sends an immediate event-driven status update.
         */
        fun sendEvent(context: Context, eventType: String, details: String = "") {
            val data = workDataOf(
                KEY_EVENT_TYPE to eventType,
                KEY_EVENT_DETAILS to details
            )

            val request = OneTimeWorkRequestBuilder<DeviceStatusWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Event write enqueued: $eventType")
        }
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val db = LocalDatabase.getInstance(applicationContext)

    override suspend fun doWork(): Result {
        val identityManager = DeviceIdentityManager(applicationContext)
        val deviceId = identityManager.getDeviceId()

        return try {
            val statusMap = mutableMapOf<String, Any>(
                "deviceModel" to identityManager.getDeviceModel(),
                "androidVersion" to identityManager.getAndroidVersion(),
                "kioskAppVersion" to identityManager.getKioskAppVersion(),
                "serialNumber" to identityManager.getSerialNumber(),
                "imei" to identityManager.getImei(),
                "batteryLevel" to getBatteryLevel(),
                "isOnline" to true,
                "lastHeartbeat" to FieldValue.serverTimestamp()
            )

            // Include installed apps
            val approvedApps = db.approvedAppDao().getActiveAppsList()
            val installedApps = approvedApps.map { app ->
                mapOf(
                    "packageName" to app.packageName,
                    "versionCode" to app.versionCode
                )
            }
            statusMap["installedApps"] = installedApps

            // Collect Telemetry (Network & App Usage)
            val telemetry = TelemetryCollector.collectDailyStats(applicationContext)
            val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            
            // Push telemetry to a subcollection
            firestore.collection("devices").document(deviceId)
                .collection("telemetry").document(todayDate)
                .set(mapOf(
                    "date" to todayDate,
                    "wifiBytes" to telemetry.wifiBytes,
                    "mobileBytes" to telemetry.mobileBytes,
                    "appUsage" to telemetry.appUsage,
                    "lastUpdated" to FieldValue.serverTimestamp()
                ), com.google.firebase.firestore.SetOptions.merge())
                .await()

            // Include event data if this is an event-driven write
            val eventType = inputData.getString(KEY_EVENT_TYPE)
            if (eventType != null) {
                val eventDetails = inputData.getString(KEY_EVENT_DETAILS) ?: ""
                statusMap["lastEvent"] = mapOf(
                    "type" to eventType,
                    "details" to eventDetails,
                    "at" to FieldValue.serverTimestamp()
                )

                // Add to alerts array for critical events
                if (eventType in listOf(EVENT_CRASH_ROLLBACK, EVENT_UNAUTHORIZED_EXIT, EVENT_PIN_LOCKOUT)) {
                    statusMap["alerts"] = FieldValue.arrayUnion(
                        mapOf(
                            "type" to eventType,
                            "details" to eventDetails,
                            "at" to System.currentTimeMillis()
                        )
                    )
                }
            }

            // Write to Firestore
            firestore.collection("devices")
                .document(deviceId)
                .set(statusMap, com.google.firebase.firestore.SetOptions.merge())
                .await()

            Log.i(TAG, "Status written for device $deviceId" +
                    (if (eventType != null) " (event: $eventType)" else " (heartbeat)"))

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write device status: ${e.message}", e)
            Result.retry()
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryIntent: Intent? = applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }
}
