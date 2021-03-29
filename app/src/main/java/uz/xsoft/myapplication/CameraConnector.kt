package uz.xsoft.myapplication

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope

interface CameraConnector {
    val context: Context
    val surfaceView: AutoFitSurfaceView?
    val scope: CoroutineScope
    val textTime: TextView?
}