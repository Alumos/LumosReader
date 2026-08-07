package xyz.alumos.lumosreader.reader.nativeview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class BitmapPageView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var page: Bitmap? = null
    var onPrevious: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onCenter: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)
        isHapticFeedbackEnabled = false
    }

    fun swapPage(next: Bitmap) {
        val old = page
        page = next
        invalidate()
        if (old !== next && old?.isRecycled == false) old.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = page ?: return
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) * .5f
        val top = (height - drawHeight) * .5f
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        when {
            event.x < width * .34f -> onPrevious?.invoke()
            event.x > width * .66f -> onNext?.invoke()
            else -> onCenter?.invoke()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        page?.takeIf { !it.isRecycled }?.recycle()
        page = null
        super.onDetachedFromWindow()
    }
}
