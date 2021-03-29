package uz.xsoft.myapplication.source.local.dao

import androidx.room.*

interface BaseDao<T> {
    @Update
    suspend fun update(data: T)

    @Delete
    suspend fun deleteAll(data: List<T>): Int

    @Delete
    suspend fun delete(data: T): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<T>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: T): Long

    @Update
    suspend fun updateAll(data: List<T>)
}