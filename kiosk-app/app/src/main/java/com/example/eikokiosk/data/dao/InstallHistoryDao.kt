package com.example.eikokiosk.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.eikokiosk.data.entity.InstallHistoryEntity

@Dao
interface InstallHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: InstallHistoryEntity)

    @Query("SELECT * FROM install_history WHERE packageName = :packageName AND status = 'SUCCESS' ORDER BY installedAt DESC LIMIT 1")
    suspend fun getLastSuccessful(packageName: String): InstallHistoryEntity?

    @Query("SELECT * FROM install_history WHERE packageName = :packageName ORDER BY installedAt DESC")
    suspend fun getHistoryForPackage(packageName: String): List<InstallHistoryEntity>

    @Query("DELETE FROM install_history WHERE installedAt < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
}
