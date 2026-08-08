package xyz.alumos.lumosreader.reader.nativeview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.util.Log
import kotlin.math.abs
import kotlin.math.min

class BitmapPageView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var page: Bitmap? = null
    var onPrevious: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onCenter: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

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
        destination.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val dx = event.x - downX
                val dy = event.y - downY
                // A short horizontal swipe is a page command too. This keeps paging
                // reliable on readers whose touch zones are narrower than expected.
                if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.15f) {
                    if (dx < 0f) onNext?.invoke() else onPrevious?.invoke()
                    Log.i("LumosComic", "bitmap swipe ${if (dx < 0f) "next" else "previous"} dx=$dx callback=${if (dx < 0f) onNext != null else onPrevious != null}")
                    performClick()
                    return true
                }
                if (abs(dx) > slop * 2 || abs(dy) > slop * 2) return true
                when {
                    downX < width * .42f -> { Log.i("LumosComic", "bitmap tap previous x=$downX width=$width callback=${onPrevious != null}"); onPrevious?.invoke() }
                    downX > width * .58f -> { Log.i("LumosComic", "bitmap tap next x=$downX width=$width callback=${onNext != null}"); onNext?.invoke() }
                    else -> { Log.i("LumosComic", "bitmap tap center x=$downX width=$width callback=${onCenter != null}"); onCenter?.invoke() }
                }
                performClick(); return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDetachedFromWindow() {
        page?.takeIf { !it.isRecycled }?.recycle()
        page = null
        super.onDetachedFromWindow()
    }
}
