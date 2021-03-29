package uz.xsoft

import android.os.Handler
import android.os.Message
import uz.xsoft.CircularEncoder

class MainHandler(val callback: HandlerCallback) : Handler(), CircularEncoder.Callback {
    companion object {
        val MSG_BLINK_TEXT = 0
        val MSG_FRAME_AVAILABLE = 1
        val MSG_FILE_SAVE_COMPLETE = 2
        val MSG_BUFFER_STATUS = 3
    }

    override fun fileSaveComplete(status: Int) {
        sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null))
    }

    override fun bufferStatus(totalTimeMsec: Long) {
        sendMessage(obtainMessage(MSG_BUFFER_STATUS, (totalTimeMsec shr 32).toInt(), totalTimeMsec.toInt()))
    }

    override fun handleMessage(msg: Message) {
        when (msg.what) {
            MSG_BLINK_TEXT -> {
            }
            MSG_FRAME_AVAILABLE -> callback.onFrameDraw()
            MSG_FILE_SAVE_COMPLETE -> callback.onSaveCompleted(msg.arg1)
            MSG_BUFFER_STATUS -> {
                val duration = msg.arg1.toLong() shl 32 or (msg.arg2.toLong() and 0xffffffffL)
                callback.onUpdateBuffer(duration)
            }
        }
    }
}

interface HandlerCallback {
    fun onSaveCompleted(status: Int)
    fun onFrameDraw()
    fun onUpdateBuffer(duration: Long)
}