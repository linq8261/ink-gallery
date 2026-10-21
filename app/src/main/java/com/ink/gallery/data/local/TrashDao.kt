package com.ink.gallery.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashDao {

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashEntity>>

    @Query("SELECT COUNT(*) FROM trash")
    fun observeCount(): Flow<Int>

    @Query("SELECT IFNULL(SUM(size), 0) FROM trash")
    fun observeTotalSize(): Flow<Long>

    @Query("SELECT * FROM trash WHERE expiresAt <= :now")
    suspend fun findExpired(now: Long): List<TrashEntity>

    @Insert
    suspend fun insert(entity: TrashEntity): Long

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun deleteById(id: Long)
}
