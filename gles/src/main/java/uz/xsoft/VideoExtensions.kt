package uz.xsoft

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


@SuppressLint("SimpleDateFormat")
fun Context.getDuration(videoFile: File): String {
    val retriever = MediaMetadataRetriever()
    //use one of overloaded setDataSource() functions to set your data source
    retriever.setDataSource(this, Uri.fromFile(videoFile))
    val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
    val timeInMilliSec = time?.toLong() ?: 0L
    retriever.release()
    return SimpleDateFormat("mm:ss").format(Date(timeInMilliSec))
}