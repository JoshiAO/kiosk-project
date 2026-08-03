package com.example.eikokiosk.installer

import android.app.ApplicationExitInfo
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.eikokiosk.data.LocalDatabase
import com.example.eikokiosk.data.entity.CrashLogEntity
import com.example.eikokiosk.sync.DeviceStatusWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Monitors approved apps for crash loops and triggers auto-rollback.
 *
 * Detection Method:
 * - Uses ActivityManager.getHistoricalProcessExitReasons() (API 30+)
 *   to detect REASON_CRASH and REASON_ANR for target packages.
 *
 * Rollback Flow:
 * 1. After an app is launched, check its crash history
 * 2. Increment crash counter in Room
 * 3. If crashes >= threshold (default: 3), trigger SilentInstaller.installRollback()
 * 4. Send CRASH_ROLLBACK event to dashboard
 * 5. Reset crash counter
 */
class CrashDetector(private val context: Context) {

    companion object {
        private const val TAG = "CrashDetector"
        private const val DEFAULT_CRASH_THRESHOLD = 3
    }

    private val db = LocalDatabase.getInstance(context)
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /**
     * Checks if the given package has been crashing and handles rollback if needed.
     *
     * @param packageName The package to check
     * @param crashThreshold Number of crashes before rollback (default: 3)
     * @return true if a rollback was triggered
     */
    suspend fun checkAndHandleCrashes(
        packageName: String,
        crashThreshold: Int = DEFAULT_CRASH_THRESHOLD
    ): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "CrashDetector requires API 30+ — skipping for $packageName")
            return@withContext false
        }

        try {
            // Get recent exit reasons for this package
            val exitReasons = activityManager.getHistoricalProcessExitReasons(
                packageName,
                0, // all PIDs
                10 // last 10 exits
            )

            // Count recent crashes (REASON_CRASH or REASON_ANR)
            val recentCrashes = exitReasons.count { exitInfo ->
                exitInfo.reason == ApplicationExitInfo.REASON_CRASH ||
                exitInfo.reason == ApplicationExitInfo.REASON_ANR
            }

            if (recentCrashes == 0) {
                // No crashes — reset counter if it was previously set
                db.crashLogDao().resetCrashCount(packageName)
                return@withContext false
            }

            // Update crash log in Room
            val existing = db.crashLogDao().getCrashLog(packageName)
            val currentCount = (existing?.crashCount ?: 0) + 1

            db.crashLogDao().upsert(
                CrashLogEntity(
                    packageName = packageName,
                    crashCount = currentCount,
                    lastCrashAt = System.currentTimeMillis(),
                    rolledBack = false
                )
            )

            Log.w(TAG, "$packageName crash count: $currentCount/$crashThreshold")

            // Check if threshold reached
            if (currentCount >= crashThreshold) {
                Log.e(TAG, "Crash threshold reached for $packageName — triggering rollback")

                val installer = SilentInstaller(context)
                val rollbackSuccess = installer.installRollback(packageName)

                if (rollbackSuccess) {
                    // Mark as rolled back and reset counter
                    db.crashLogDao().upsert(
                        CrashLogEntity(
                            packageName = packageName,
                            crashCount = 0,
                            lastCrashAt = System.currentTimeMillis(),
                            rolledBack = true
                        )
                    )

                    // Alert the dashboard
                    DeviceStatusWorker.sendEvent(
                        context,
                        DeviceStatusWorker.EVENT_CRASH_ROLLBACK,
                        "$packageName crashed $currentCount times — rolled back to previous version"
                    )

                    Log.i(TAG, "Auto-rollback successful for $packageName")
                    return@withContext true
                } else {
                    Log.e(TAG, "Auto-rollback FAILED for $packageName — no rollback APK available")

                    DeviceStatusWorker.sendEvent(
                        context,
                        DeviceStatusWorker.EVENT_CRASH_ROLLBACK,
                        "$packageName crashed $currentCount times — rollback FAILED (no backup APK)"
                    )
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Crash detection failed for $packageName: ${e.message}", e)
            false
        }
    }
}
