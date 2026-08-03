package com.example.eikokiosk.auth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class ActivationManager(private val context: Context) {
    companion object {
        private const val TAG = "ActivationManager"
        private const val PREFS_FILE = "activation_prefs"
        private const val KEY_LAST_ACTIVATION = "last_activation_time"
        
        // Licensing Configuration
        private const val PROJECT_ID = "joshiao-active-projects"
        // Securely loaded from local.properties via BuildConfig
        private val API_KEY = com.example.eikokiosk.BuildConfig.API_KEY
        private const val DOC_HASH = "796825893a2b3c16cd23d62051817780d8365315427f367cb8566dc7bcdad4a6"
        private const val EXPECTED_PROJECT_CODE = "EIKO-KISK-ANDR-PROD"
        private const val EXPECTED_PROJECT_NAME = "kiosk2026"
        
        // Grace period intervals
        private val GRACE_PERIOD_MS = TimeUnit.DAYS.toMillis(30)
        private val SILENT_RETRY_MS = TimeUnit.DAYS.toMillis(15)
    }

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    enum class ActivationState {
        ACTIVATED,          // Recently activated, all good
        REQUIRES_CHECK,     // Past 15 days, try silent check, but allow in if it fails
        LOCKED_DOWN         // Past 30 days, absolutely locked down
    }

    fun getActivationState(): ActivationState {
        val lastActivation = prefs.getLong(KEY_LAST_ACTIVATION, 0L)
        val now = System.currentTimeMillis()
        
        if (lastActivation == 0L) return ActivationState.LOCKED_DOWN
        
        val timeSinceLastActivation = now - lastActivation
        return when {
            timeSinceLastActivation > GRACE_PERIOD_MS -> ActivationState.LOCKED_DOWN
            timeSinceLastActivation > SILENT_RETRY_MS -> ActivationState.REQUIRES_CHECK
            else -> ActivationState.ACTIVATED
        }
    }

    suspend fun checkActivationOnline(activationCode: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val hashToCheck = if (activationCode != null) {
                // Compute SHA256 of the provided activation code
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val digest = md.digest(activationCode.toByteArray(Charsets.UTF_8))
                digest.joinToString("") { "%02x".format(it) }
            } else {
                prefs.getString("saved_hash", null)
            }

            if (hashToCheck == null) return@withContext false

            val urlString = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/projectid/$hashToCheck?key=$API_KEY"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val fields = json.getJSONObject("fields")
                
                val isActive = fields.optJSONObject("active")?.optBoolean("booleanValue") ?: false
                val projectCode = fields.optJSONObject("projectcode")?.optString("stringValue") ?: ""
                val projectName = fields.optJSONObject("projectname")?.optString("stringValue") ?: ""

                if (isActive && (activationCode == null || projectCode == activationCode) && projectName == EXPECTED_PROJECT_NAME) {
                    prefs.edit()
                        .putLong(KEY_LAST_ACTIVATION, System.currentTimeMillis())
                        .putString("saved_hash", hashToCheck)
                        .apply()
                    Log.i(TAG, "Online activation successful!")
                    return@withContext true
                } else {
                    Log.w(TAG, "Online activation failed: Mismatched credentials or disabled")
                    return@withContext false
                }
            } else {
                Log.e(TAG, "Online activation failed with HTTP ${connection.responseCode}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking activation: ${e.message}", e)
            return@withContext false
        }
    }
    
    fun forceClearActivation() {
        prefs.edit().putLong(KEY_LAST_ACTIVATION, 0L).apply()
    }
}
