package com.example.eikokiosk.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

/**
 * Manages Firebase Authentication for the kiosk device.
 *
 * Uses a custom token flow:
 * 1. During provisioning, a custom token is supplied via ADB broadcast
 * 2. The device signs in with signInWithCustomToken()
 * 3. The custom token encodes a deviceId claim for Firestore security rules
 *
 * The token is stored in EncryptedSharedPreferences and refreshed automatically.
 */
class AuthManager(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val PREFS_FILE = "auth_prefs"
        private const val KEY_CUSTOM_TOKEN = "custom_token"
    }

    private val auth = FirebaseAuth.getInstance()

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
     * Returns the currently signed-in Firebase user, or null.
     */
    fun getCurrentUser(): FirebaseUser? = auth.currentUser

    /**
     * Returns true if the device is authenticated with Firebase.
     */
    fun isAuthenticated(): Boolean = auth.currentUser != null

    /**
     * Stores the custom token (received via ADB broadcast during provisioning).
     */
    fun storeToken(token: String) {
        prefs.edit().putString(KEY_CUSTOM_TOKEN, token).apply()
        Log.i(TAG, "Custom token stored securely")
    }

    /**
     * Signs in to Firebase using the stored custom token.
     * Returns true on success, false on failure.
     */
    suspend fun signIn(): Boolean {
        val token = prefs.getString(KEY_CUSTOM_TOKEN, null)
        if (token == null) {
            Log.w(TAG, "No custom token available — falling back to Anonymous Auth")
            return try {
                auth.signInAnonymously().await()
                Log.i(TAG, "Anonymous sign-in successful: uid=${auth.currentUser?.uid}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Anonymous sign-in failed: ${e.message}", e)
                false
            }
        }

        return try {
            auth.signInWithCustomToken(token).await()
            Log.i(TAG, "Firebase sign-in successful: uid=${auth.currentUser?.uid}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Firebase sign-in failed: ${e.message}", e)
            false
        }
    }

    /**
     * Signs out of Firebase.
     */
    fun signOut() {
        auth.signOut()
        Log.i(TAG, "Firebase signed out")
    }
}
