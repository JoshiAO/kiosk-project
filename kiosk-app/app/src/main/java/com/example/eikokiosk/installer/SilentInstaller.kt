package com.example.eikokiosk.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.example.eikokiosk.data.LocalDatabase
import com.example.eikokiosk.data.entity.InstallHistoryEntity
import com.example.eikokiosk.sync.DeviceStatusWorker
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Handles silent APK download and installation using PackageInstaller API.
 *
 * Because the app is a Device Owner, it can install packages silently
 * without any user prompts.
 *
 * Flow:
 * 1. Downloads APK from Firebase Storage to internal cache
 * 2. Backs up the previous version APK for rollback
 * 3. Creates a PackageInstaller session and installs silently
 * 4. Records the install in Room and sends a status event
 */
class SilentInstaller(private val context: Context) {

    companion object {
        private const val TAG = "SilentInstaller"
        private const val APK_CACHE_DIR = "apk_cache"
    }

    private val storage = FirebaseStorage.getInstance()
    private val db = LocalDatabase.getInstance(context)
    private val packageInstaller = context.packageManager.packageInstaller

    /**
     * Downloads and installs an APK silently.
     *
     * @param packageName The target package name
     * @param versionCode The expected version code
     * @param apkUrl The Firebase Storage URL (gs:// or https://)
     * @return true if installation was initiated successfully
     */
    suspend fun downloadAndInstall(
        packageName: String,
        versionCode: Int,
        apkUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting download for $packageName v$versionCode")

            // Step 1: Prepare cache directory
            val cacheDir = File(context.filesDir, APK_CACHE_DIR)
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val apkFile = File(cacheDir, "${packageName}_v${versionCode}.apk")

            // Step 2: Backup current version for rollback
            backupCurrentVersion(packageName, cacheDir)

            // Step 3: Download APK from Firebase Storage
            val ref = storage.getReferenceFromUrl(apkUrl)
            ref.getFile(apkFile).await()
            Log.i(TAG, "APK downloaded: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

            // Step 4: Silent install via PackageInstaller
            val success = installApk(apkFile, packageName)

            // Step 5: Record in history
            val entry = InstallHistoryEntity(
                packageName = packageName,
                versionCode = versionCode,
                apkCachePath = apkFile.absolutePath,
                installedAt = System.currentTimeMillis(),
                status = if (success) "SUCCESS" else "FAILED"
            )
            db.installHistoryDao().insert(entry)

            // Step 6: Send status event
            if (success) {
                DeviceStatusWorker.sendEvent(
                    context,
                    DeviceStatusWorker.EVENT_INSTALL_SUCCESS,
                    "$packageName v$versionCode"
                )
                Log.i(TAG, "Silent install SUCCESS: $packageName v$versionCode")
            } else {
                DeviceStatusWorker.sendEvent(
                    context,
                    DeviceStatusWorker.EVENT_INSTALL_FAILED,
                    "$packageName v$versionCode"
                )
                Log.e(TAG, "Silent install FAILED: $packageName v$versionCode")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed for $packageName: ${e.message}", e)
            DeviceStatusWorker.sendEvent(
                context,
                DeviceStatusWorker.EVENT_INSTALL_FAILED,
                "$packageName: ${e.message}"
            )
            false
        }
    }

    /**
     * Backs up the currently installed APK for rollback purposes.
     */
    private fun backupCurrentVersion(packageName: String, cacheDir: File) {
        try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val sourceApk = File(appInfo.sourceDir)
            val rollbackFile = File(cacheDir, "${packageName}_rollback.apk")

            sourceApk.copyTo(rollbackFile, overwrite = true)
            Log.i(TAG, "Backed up current APK for rollback: ${rollbackFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "No existing APK to backup for $packageName (first install)")
        }
    }

    /**
     * Installs an APK silently using PackageInstaller API.
     * Requires Device Owner privileges.
     */
    private fun installApk(apkFile: File, packageName: String): Boolean {
        return try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(packageName)
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Write APK to session
            session.openWrite("package", 0, apkFile.length()).use { outputStream ->
                FileInputStream(apkFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                session.fsync(outputStream)
            }

            // Commit the session
            val intent = Intent("com.example.eikokiosk.INSTALL_COMPLETE").apply {
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            session.commit(pendingIntent.intentSender)
            session.close()

            Log.i(TAG, "PackageInstaller session committed for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller failed: ${e.message}", e)
            false
        }
    }

    /**
     * Installs a rollback APK for the given package (used by CrashDetector).
     */
    suspend fun installRollback(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, APK_CACHE_DIR)
        val rollbackFile = File(cacheDir, "${packageName}_rollback.apk")

        if (!rollbackFile.exists()) {
            Log.w(TAG, "No rollback APK available for $packageName")
            return@withContext false
        }

        Log.i(TAG, "Installing rollback APK for $packageName")
        val success = installApk(rollbackFile, packageName)

        if (success) {
            val entry = InstallHistoryEntity(
                packageName = packageName,
                versionCode = -1, // rollback
                apkCachePath = rollbackFile.absolutePath,
                installedAt = System.currentTimeMillis(),
                status = "ROLLED_BACK"
            )
            db.installHistoryDao().insert(entry)
        }

        success
    }
}
