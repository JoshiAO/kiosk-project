package com.example.eikokiosk.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.eikokiosk.data.entity.ApprovedAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApprovedAppDao {

    @Query("SELECT * FROM approved_apps WHERE isActive = 1 ORDER BY appName ASC")
    fun getActiveApps(): Flow<List<ApprovedAppEntity>>

    @Query("SELECT * FROM approved_apps WHERE isActive = 1")
    suspend fun getActiveAppsList(): List<ApprovedAppEntity>

    @Query("SELECT * FROM approved_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackageName(packageName: String): ApprovedAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<ApprovedAppEntity>)

    @Query("DELETE FROM approved_apps WHERE id NOT IN (:activeIds)")
    suspend fun deleteRemovedApps(activeIds: List<String>)

    @Query("DELETE FROM approved_apps")
    suspend fun deleteAll()
}
