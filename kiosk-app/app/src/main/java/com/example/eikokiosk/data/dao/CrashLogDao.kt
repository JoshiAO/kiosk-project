package com.example.eikokiosk.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.eikokiosk.data.entity.CrashLogEntity

@Dao
interface CrashLogDao {

    @Query("SELECT * FROM crash_log WHERE packageName = :packageName LIMIT 1")
    suspend fun getCrashLog(packageName: String): CrashLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CrashLogEntity)

    @Query("UPDATE crash_log SET crashCount = 0, rolledBack = 0 WHERE packageName = :packageName")
    suspend fun resetCrashCount(packageName: String)

    @Query("DELETE FROM crash_log WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
