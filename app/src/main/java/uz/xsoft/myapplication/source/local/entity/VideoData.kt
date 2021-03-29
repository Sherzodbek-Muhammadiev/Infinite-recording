package uz.xsoft.myapplication.source.local.entity

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize


@Entity
@Parcelize
data class VideoData(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    val path: String,
    val date: Long
) : Parcelable