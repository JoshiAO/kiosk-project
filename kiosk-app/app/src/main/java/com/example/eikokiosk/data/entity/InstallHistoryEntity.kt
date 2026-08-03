package com.example.eikokiosk.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records each app installation attempt for audit and rollback purposes.
 */
@Entity(tableName = "install_history")
data class InstallHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val versionCode: Int,
    val apkCachePath: String?, // path to cached APK file
    val installedAt: Long, // epoch millis
    val status: String // SUCCESS, FAILED, ROLLED_BACK
)
