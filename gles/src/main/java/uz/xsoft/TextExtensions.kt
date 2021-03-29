package uz.xsoft

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint


fun String.asBitmap(textSize: Float = 20f, textColor: Int = Color.RED): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.textSize = textSize
    paint.color = textColor
    paint.textAlign = Paint.Align.LEFT
    val baseline = -paint.ascent() // ascent() is negative
    val width = (paint.measureText(this) + 0.5f).toInt() // round
    val height = (baseline + paint.descent() + 0.5f).toInt()
    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(image)
    canvas.drawText(this, 0f, baseline, paint)
//    canvas.drawRect(0f,0f,200f,200f, Paint().apply { color = Color.RED })
    return image
}