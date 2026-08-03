package com.example.eikokiosk.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks consecutive crash counts for each approved app.
 * Used by CrashDetector to determine if auto-rollback should trigger.
 */
@Entity(tableName = "crash_log")
data class CrashLogEntity(
    @PrimaryKey
    val packageName: String,
    val crashCount: Int,
    val lastCrashAt: Long, // epoch millis
    val rolledBack: Boolean
)
