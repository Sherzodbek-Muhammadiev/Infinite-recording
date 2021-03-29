package uz.xsoft.myapplication.source.local.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Query
import uz.xsoft.myapplication.source.local.entity.VideoData

@Dao
interface VideoDao : BaseDao<VideoData> {
    @Query("SELECT * FROM VideoData ORDER BY id DESC")
    fun getAll(): LiveData<List<VideoData>>
}