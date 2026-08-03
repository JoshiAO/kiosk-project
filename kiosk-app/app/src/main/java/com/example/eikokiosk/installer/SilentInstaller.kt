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
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

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

            val isZip = apkUrl.lowercase().let { it.contains(".zip") || it.contains(".apks") }
            val downloadFile = File(cacheDir, "${packageName}_v${versionCode}${if (isZip) ".zip" else ".apk"}")

            // Step 2: Backup current version for rollback
            backupCurrentVersion(packageName, cacheDir)

            // Step 3: Download APK or ZIP
            if (apkUrl.startsWith("gs://") || apkUrl.contains("firebasestorage.googleapis.com")) {
                val ref = storage.getReferenceFromUrl(apkUrl)
                ref.getFile(downloadFile).await()
            } else {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(apkUrl)
                    .header("User-Agent", "EikoKiosk/1.0 (Android; Device-Owner)")
                    .build()
                Log.i(TAG, "Downloading from URL: $apkUrl")
                val response = client.newCall(request).execute()
                Log.i(TAG, "HTTP response: ${response.code} from ${response.request.url}")
                if (!response.isSuccessful) throw Exception("HTTP Download failed with code: ${response.code} from ${response.request.url}")
                
                FileOutputStream(downloadFile).use { output ->
                    response.body?.byteStream()?.copyTo(output)
                        ?: throw Exception("Empty response body from URL")
                }
            }
            Log.i(TAG, "Downloaded to: ${downloadFile.absolutePath} (${downloadFile.length()} bytes)")

            // Step 3.5: Extract ZIP if needed
            val filesToInstall = mutableListOf<File>()
            if (isZip) {
                val extractDir = File(cacheDir, "${packageName}_v${versionCode}_splits")
                if (extractDir.exists()) extractDir.deleteRecursively()
                extractDir.mkdirs()

                ZipInputStream(FileInputStream(downloadFile)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                            val outFile = File(extractDir, File(entry.name).name)
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            filesToInstall.add(outFile)
                            Log.i(TAG, "Extracted split APK: ${outFile.name}")
                        }
                        entry = zis.nextEntry
                    }
                }
                if (filesToInstall.isEmpty()) throw Exception("No .apk files found inside the downloaded ZIP archive.")
            } else {
                filesToInstall.add(downloadFile)
            }

            // Step 4: Silent install via PackageInstaller
            val success = installApk(filesToInstall, packageName)

            // Step 5: Record in history
            val entry = InstallHistoryEntity(
                packageName = packageName,
                versionCode = versionCode,
                apkCachePath = downloadFile.absolutePath,
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
        } finally {
            // Cleanup: keep only latest 2 APKs per package to prevent unbounded cache growth
            cleanupOldApks(packageName)
        }
    }

    /**
     * Removes old APK cache files, keeping only the 2 most recent per package.
     */
    private fun cleanupOldApks(packageName: String) {
        try {
            val cacheDir = File(context.filesDir, APK_CACHE_DIR)
            val apkFiles = cacheDir.listFiles { file ->
                file.name.startsWith("${packageName}_v") && (file.name.endsWith(".apk") || file.name.endsWith(".zip") || file.isDirectory)
                        && !file.name.contains("rollback")
            }?.sortedByDescending { it.lastModified() } ?: return

            if (apkFiles.size > 2) {
                apkFiles.drop(2).forEach { oldFile ->
                    Log.i(TAG, "Cleaning up old cache: ${oldFile.name}")
                    if (oldFile.isDirectory) oldFile.deleteRecursively() else oldFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "APK cleanup failed: ${e.message}")
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
     * Installs one or more APKs silently using PackageInstaller API.
     * Supports both single monolithic APKs and split APK bundles.
     * Requires Device Owner privileges.
     * Waits for the actual install result via a BroadcastReceiver.
     */
    private fun installApk(apkFiles: List<File>, packageName: String): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
            
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(packageName)
                if (isDeviceOwner) {
                    setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_POLICY)
                }
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Write all APK splits to session
            apkFiles.forEachIndexed { index, apkFile ->
                val sessionName = "split_$index"
                session.openWrite(sessionName, 0, apkFile.length()).use { outputStream ->
                    FileInputStream(apkFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    session.fsync(outputStream)
                }
            }

            // Use a latch to wait for the install result
            val latch = java.util.concurrent.CountDownLatch(1)
            var installSuccess = false
            var installMessage = ""

            val resultReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown"
                    Log.i(TAG, "Install result for $packageName: status=$status message=$msg")
                    installSuccess = (status == PackageInstaller.STATUS_SUCCESS)
                    installMessage = msg
                    latch.countDown()
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                }
            }

            val filterAction = "com.example.eikokiosk.INSTALL_COMPLETE_$sessionId"
            context.registerReceiver(resultReceiver, android.content.IntentFilter(filterAction), Context.RECEIVER_EXPORTED)

            val intent = Intent(filterAction).apply {
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

            Log.i(TAG, "PackageInstaller session committed for $packageName, awaiting result...")
            
            // Wait up to 60 seconds for the install to complete
            val completed = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                Log.e(TAG, "Install timed out for $packageName after 60s")
                try { context.unregisterReceiver(resultReceiver) } catch (_: Exception) {}
                return false
            }

            if (!installSuccess) {
                Log.e(TAG, "Install FAILED for $packageName: $installMessage")
            } else {
                Log.i(TAG, "Install CONFIRMED for $packageName")
            }
            installSuccess
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
        val success = installApk(listOf(rollbackFile), packageName)

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
