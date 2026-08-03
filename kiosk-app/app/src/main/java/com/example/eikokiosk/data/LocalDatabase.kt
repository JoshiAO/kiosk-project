package com.example.eikokiosk.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.eikokiosk.data.dao.ApprovedAppDao
import com.example.eikokiosk.data.dao.CrashLogDao
import com.example.eikokiosk.data.dao.InstallHistoryDao
import com.example.eikokiosk.data.entity.ApprovedAppEntity
import com.example.eikokiosk.data.entity.CrashLogEntity
import com.example.eikokiosk.data.entity.InstallHistoryEntity

/**
 * Local Room database for the Kiosk app.
 *
 * Caches approved apps, install history, and crash logs so the device
 * can function fully when offline.
 */
@Database(
    entities = [
        ApprovedAppEntity::class,
        InstallHistoryEntity::class,
        CrashLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LocalDatabase : RoomDatabase() {

    abstract fun approvedAppDao(): ApprovedAppDao
    abstract fun installHistoryDao(): InstallHistoryDao
    abstract fun crashLogDao(): CrashLogDao

    companion object {
        @Volatile
        private var INSTANCE: LocalDatabase? = null

        fun getInstance(context: Context): LocalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalDatabase::class.java,
                    "kiosk_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
