package com.example.eikokiosk

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * Application class for the Eiko Kiosk app.
 * Initializes core services on startup.
 */
class KioskApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize WorkManager with default configuration
        val workConfig = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

        try {
            WorkManager.initialize(this, workConfig)
        } catch (_: IllegalStateException) {
            // Already initialized (e.g., by AndroidX startup)
        }
    }
}
