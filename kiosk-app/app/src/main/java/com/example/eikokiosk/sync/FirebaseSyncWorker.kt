package com.example.eikokiosk.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.eikokiosk.data.LocalDatabase
import com.example.eikokiosk.data.entity.ApprovedAppEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that syncs the approved apps list from Firestore.
 *
 * Schedule: Every 60 minutes (configurable via config/sync_settings).
 * Constraints: Requires network connectivity.
 *
 * On each run:
 * 1. Fetches approved_apps collection from Firestore
 * 2. Diffs against local Room database
 * 3. Updates local cache
 * 4. Enqueues SilentInstaller for new/updated apps
 */
class FirebaseSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "FirebaseSyncWorker"
        private const val WORK_NAME = "firebase_sync_periodic"

        /**
         * Enqueues the periodic sync worker.
         * Call this once during app initialization.
         */
        fun enqueue(context: Context, intervalMinutes: Long = 60) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<FirebaseSyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.i(TAG, "Periodic sync enqueued (every ${intervalMinutes}min)")
        }

        /**
         * Triggers an immediate one-time sync.
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<FirebaseSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Immediate sync requested")
        }
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val db = LocalDatabase.getInstance(applicationContext)

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting Firebase sync...")

        return try {
            // Step 1: Fetch approved apps from Firestore
            val snapshot = firestore.collection("approved_apps").get().await()

            val remoteApps = snapshot.documents.mapNotNull { doc ->
                try {
                    ApprovedAppEntity(
                        id = doc.id,
                        appName = doc.getString("appName") ?: return@mapNotNull null,
                        packageName = doc.getString("packageName") ?: return@mapNotNull null,
                        versionCode = (doc.getLong("versionCode") ?: 0).toInt(),
                        versionName = doc.getString("versionName") ?: "0.0.0",
                        apkUrl = doc.getString("downloadUrl") ?: "",
                        remoteConfig = doc.get("remoteConfig")?.toString(),
                        isActive = doc.getBoolean("isActive") ?: true,
                        updatedAt = doc.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse app doc ${doc.id}: ${e.message}")
                    null
                }
            }

            Log.i(TAG, "Fetched ${remoteApps.size} apps from Firestore")

            // Step 2: Get current local state
            val localApps = db.approvedAppDao().getActiveAppsList()
            val localMap = localApps.associateBy { it.id }

            // Step 3: Update local cache
            db.approvedAppDao().insertAll(remoteApps)

            // Remove apps that were removed from Firebase
            val remoteIds = remoteApps.map { it.id }
            db.approvedAppDao().deleteRemovedApps(remoteIds)

            // Step 4: Detect new or updated apps for installation
            val pm = applicationContext.packageManager
            for (app in remoteApps) {
                if (!app.isActive) continue

                val local = localMap[app.id]
                val isActuallyInstalled = try {
                    pm.getPackageInfo(app.packageName, 0)
                    true
                } catch (e: Exception) {
                    false
                }

                if (local == null || local.versionCode < app.versionCode || !isActuallyInstalled) {
                    Log.i(TAG, "New/updated app detected (or missing): ${app.packageName} v${app.versionCode}")
                    if (app.apkUrl.isNotEmpty()) {
                        com.example.eikokiosk.installer.SilentInstaller(applicationContext).downloadAndInstall(app.packageName, app.versionCode, app.apkUrl)
                    } else {
                        Log.i(TAG, "App ${app.packageName} has no APK URL. Assuming it is a native system app.")
                    }
                }
            }

            // Step 5: Sync master PIN
            try {
                val masterDoc = firestore.collection("config").document("master").get().await()
                val masterHash = masterDoc.getString("masterPinHash")
                if (masterHash != null) {
                    Log.i(TAG, "Master PIN hash synced")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync master PIN: ${e.message}")
            }

            Log.i(TAG, "Firebase sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Firebase sync failed: ${e.message}", e)
            Result.retry()
        }
    }
}
