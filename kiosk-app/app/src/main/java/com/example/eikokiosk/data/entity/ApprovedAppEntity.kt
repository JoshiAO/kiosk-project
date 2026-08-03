package com.example.eikokiosk.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents an approved app from Firebase.
 * Cached locally so the kiosk works offline.
 */
@Entity(tableName = "approved_apps")
data class ApprovedAppEntity(
    @PrimaryKey
    val id: String,
    val appName: String,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val remoteConfig: String?, // JSON string
    val isActive: Boolean,
    val updatedAt: Long // epoch millis
)
