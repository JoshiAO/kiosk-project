package com.example.eikokiosk.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * Manages Master PIN and User PIN with secure storage and verification.
 *
 * - Master PIN: Fetched from Firebase (config/master document) and cached locally.
 *   Used for Admin access.
 * - User PIN: Set locally by the Admin. Used for daily unlock.
 *
 * All PINs are stored as SHA-256 hashes in EncryptedSharedPreferences.
 */
class PinManager(private val context: Context) {

    companion object {
        private const val TAG = "PinManager"
        private const val PREFS_FILE = "pin_prefs"
        private const val KEY_MASTER_PIN_HASH = "master_pin_hash"
        private const val KEY_USER_PIN_HASH = "user_pin_hash"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 60_000L // 60 seconds
    }

    private val firestore = FirebaseFirestore.getInstance()

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
     * Fetches the Master PIN hash from Firebase and caches it locally.
     * Returns true if successful.
     */
    suspend fun fetchMasterPin(): Boolean {
        return try {
            val doc = firestore.collection("config").document("master").get().await()
            val hash = doc.getString("masterPinHash")
            if (hash != null) {
                prefs.edit().putString(KEY_MASTER_PIN_HASH, hash).apply()
                Log.i(TAG, "Master PIN hash fetched and cached")
                true
            } else {
                Log.w(TAG, "Master PIN hash not found in Firebase")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch master PIN: ${e.message}", e)
            false
        }
    }

    /**
     * Verifies the input against the Master PIN hash.
     * Falls back to the cached hash if offline.
     */
    fun verifyMasterPin(input: String): Boolean {
        val storedHash = prefs.getString(KEY_MASTER_PIN_HASH, null) ?: return false
        return hashPin(input) == storedHash
    }

    /**
     * Sets the User PIN (called by the Admin from the dashboard).
     */
    fun setUserPin(pin: String) {
        val hash = hashPin(pin)
        prefs.edit().putString(KEY_USER_PIN_HASH, hash).apply()
        Log.i(TAG, "User PIN set")
    }

    /**
     * Returns true if a User PIN has been configured.
     */
    fun isUserPinSet(): Boolean {
        return prefs.getString(KEY_USER_PIN_HASH, null) != null
    }

    /**
     * Verifies the input against the User PIN hash.
     * Handles lockout after MAX_FAILED_ATTEMPTS.
     */
    fun verifyUserPin(input: String): PinResult {
        // Check lockout
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        if (System.currentTimeMillis() < lockoutUntil) {
            val remainingMs = lockoutUntil - System.currentTimeMillis()
            return PinResult.LockedOut(remainingSeconds = (remainingMs / 1000).toInt())
        }

        val storedHash = prefs.getString(KEY_USER_PIN_HASH, null)
            ?: return PinResult.NoPinSet

        val isCorrect = hashPin(input) == storedHash

        if (isCorrect) {
            // Reset failed attempts on success
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0)
                .apply()
            return PinResult.Success
        }

        // Increment failed attempts
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            val lockoutEnd = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            editor.putLong(KEY_LOCKOUT_UNTIL, lockoutEnd)
            editor.apply()
            Log.w(TAG, "PIN lockout triggered after $attempts failed attempts")
            return PinResult.LockedOut(remainingSeconds = (LOCKOUT_DURATION_MS / 1000).toInt())
        }

        editor.apply()
        return PinResult.Incorrect(attemptsRemaining = MAX_FAILED_ATTEMPTS - attempts)
    }

    /**
     * Hashes a PIN using SHA-256.
     */
    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Result of a PIN verification attempt.
 */
sealed class PinResult {
    data object Success : PinResult()
    data class Incorrect(val attemptsRemaining: Int) : PinResult()
    data class LockedOut(val remainingSeconds: Int) : PinResult()
    data object NoPinSet : PinResult()
}
