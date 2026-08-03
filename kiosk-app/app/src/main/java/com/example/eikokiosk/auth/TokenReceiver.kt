package com.example.eikokiosk.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the custom auth token via ADB broadcast during device provisioning.
 *
 * Usage:
 *   adb shell am broadcast -a com.example.eikokiosk.SET_TOKEN --es token "<custom_token>"
 */
class TokenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TokenReceiver"
        const val ACTION_SET_TOKEN = "com.example.eikokiosk.SET_TOKEN"
        const val EXTRA_TOKEN = "token"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_TOKEN) return

        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Received SET_TOKEN broadcast with no token")
            return
        }

        val authManager = AuthManager(context)
        authManager.storeToken(token)
        Log.i(TAG, "Auth token received and stored via ADB broadcast")
    }
}
