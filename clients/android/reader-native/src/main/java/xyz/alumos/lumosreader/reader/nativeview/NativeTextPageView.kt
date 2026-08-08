package xyz.alumos.lumosreader.reader.nativeview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class NativeTextPageView(context: Context) : View(context) {
    data class Style(
        val textSizeSp: Float,
        val lineSpacing: Float,
        val leftPaddingDp: Int,
        val rightPaddingDp: Int,
        val topPaddingDp: Int,
        val bottomPaddingDp: Int,
        val alignment: String,
        val letterSpacing: Float,
        val wordSpacing: Float,
        val paragraphSpacingDp: Float,
        val indentCharacters: Int,
        val textTypeface: Typeface?,
        val titleTypeface: Typeface?,
        val backgroundColor: Int,
        val textColor: Int,
    )

    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var content = ""
    private var chapterTitle = ""
    private var layout: StaticLayout? = null
    private var starts = intArrayOf(0)
    private var contentLeft = 0
    private var contentRight = 0
    var page = 0; private set
    val pageCount get() = starts.size
    var onPageChanged: ((Int, Int) -> Unit)? = null
    var onStartReached: (() -> Unit)? = null
    var onEndReached: (() -> Unit)? = null
    var onCenter: (() -> Unit)? = null

    private var style = Style(20f, 1.75f, 28, 28, 24, 24, "left", 0f, 1f, 0f, 0, null, null, Color.WHITE, Color.BLACK)

    init { setBackgroundColor(Color.WHITE); isHapticFeedbackEnabled = false }

    fun setText(value: String, fraction: Double = 0.0) {
        chapterTitle = ""
        content = value
        rebuild(fraction)
    }

    fun setChapter(title: String, value: String, fraction: Double = 0.0) {
        chapterTitle = title.trim()
        val trimmed = value.trimStart()
        content = if (chapterTitle.isNotEmpty() && trimmed.startsWith(chapterTitle)) {
            trimmed.removePrefix(chapterTitle).trimStart('\r', '\n', ' ')
        } else value
        rebuild(fraction)
    }

    fun applyStyle(value: Style) {
        if (style == value) return
        style = value
        setBackgroundColor(value.backgroundColor)
        paint.color = value.textColor
        rebuild()
    }

    fun previous(): Boolean {
        if (page <= 0) { onStartReached?.invoke(); return false }
        show(page - 1)
        return true
    }
    fun next(): Boolean {
        if (page + 1 !in starts.indices) { onEndReached?.invoke(); return false }
        show(page + 1)
        return true
    }
    fun seek(fraction: Double) = show(((pageCount - 1) * fraction.coerceIn(0.0, 1.0)).toInt())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) =
        rebuild(if (pageCount <= 1) 0.0 else page.toDouble() / (pageCount - 1))

    private fun rebuild(fraction: Double = if (pageCount <= 1) 0.0 else page.toDouble() / (pageCount - 1)) {
        if (width <= 0 || height <= 0 || (content.isEmpty() && chapterTitle.isEmpty())) return
        val density = resources.displayMetrics.density
        val left = (style.leftPaddingDp * density).roundToInt().coerceAtLeast(0)
        val right = (style.rightPaddingDp * density).roundToInt().coerceAtLeast(0)
        contentLeft = left
        contentRight = right
        paint.textSize = style.textSizeSp * resources.displayMetrics.scaledDensity
        paint.typeface = style.textTypeface
        paint.color = style.textColor
        paint.letterSpacing = style.letterSpacing
        val body = content.lineSequence().joinToString("\n") { line ->
            val spaced = if (style.wordSpacing > 1f) line.replace(" ", " ".repeat(style.wordSpacing.toInt().coerceIn(1, 4))) else line
            if (style.indentCharacters > 0 && spaced.isNotBlank()) "　".repeat(style.indentCharacters.coerceIn(0, 8)) + spaced else spaced
        }
        val renderedText = if (chapterTitle.isBlank()) body else "$chapterTitle\n\n$body"
        val rendered = SpannableString(renderedText).apply {
            if (chapterTitle.isNotBlank()) setSpan(TitleSpan(style.titleTypeface), 0, chapterTitle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val contentWidth = (width - contentLeft - contentRight).coerceAtLeast(1)
        val builder = StaticLayout.Builder.obtain(rendered, 0, rendered.length, paint, contentWidth)
            .setAlignment(when (style.alignment) {
                "center" -> Layout.Alignment.ALIGN_CENTER
                "right" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            })
            .setLineSpacing(style.paragraphSpacingDp * density, style.lineSpacing)
            .setIncludePad(false)
        applyJustification(builder)
        layout = builder.build()
        val top = (style.topPaddingDp * density).roundToInt().coerceAtLeast(0)
        val bottom = (style.bottomPaddingDp * density).roundToInt().coerceAtLeast(0)
        val pageHeight = (height - top - bottom).coerceAtLeast(1)
        val current = requireNotNull(layout)
        val pages = mutableListOf(0)
        var line = 0
        while (line < current.lineCount) {
            val pageTop = current.getLineTop(line)
            var next = line + 1
            while (next < current.lineCount && current.getLineBottom(next) <= pageTop + pageHeight) next++
            if (next >= current.lineCount) break
            pages += next
            line = next
        }
        starts = pages.toIntArray()
        page = ((starts.size - 1) * fraction).toInt().coerceIn(starts.indices)
        invalidate()
        onPageChanged?.invoke(page, pageCount)
    }

    @SuppressLint("WrongConstant", "NewApi")
    private fun applyJustification(builder: StaticLayout.Builder) {
        if (Build.VERSION.SDK_INT >= 26 && style.alignment == "justify") {
            // Chinese prose normally has no spaces. INTER_WORD leaves the unused
            // fraction of a character at every line end, which looks like a wider
            // right margin even though both numeric paddings are identical.
            // Android 15 added the correct inter-character justification mode.
            val mode = if (Build.VERSION.SDK_INT >= 35) {
                Layout.JUSTIFICATION_MODE_INTER_CHARACTER
            } else {
                Layout.JUSTIFICATION_MODE_INTER_WORD
            }
            builder.setJustificationMode(mode)
        }
    }

    private class TitleSpan(private val face: Typeface?) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) = apply(paint)
        override fun updateMeasureState(paint: TextPaint) = apply(paint)
        private fun apply(paint: TextPaint) {
            paint.typeface = face ?: Typeface.DEFAULT_BOLD
            paint.textSize *= 1.22f
        }
    }

    private fun show(target: Int) {
        if (target !in starts.indices || target == page) return
        page = target
        invalidate()
        onPageChanged?.invoke(page, pageCount)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = layout ?: return
        val density = resources.displayMetrics.density
        val pageTop = current.getLineTop(starts[page]).toFloat()
        val pageBottom = starts.getOrNull(page + 1)?.let(current::getLineTop)?.toFloat() ?: current.height.toFloat()
        canvas.save()
        val top = (style.topPaddingDp * density).roundToInt().toFloat()
        val bottom = (style.bottomPaddingDp * density).roundToInt().toFloat()
        // Clip in device coordinates first, then translate the layout into that
        // exact viewport. This keeps the two horizontal margins truly symmetric.
        canvas.clipRect(contentLeft.toFloat(), top, (width - contentRight).toFloat(), height - bottom)
        canvas.translate(contentLeft.toFloat(), top - pageTop)
        canvas.clipRect(0f, pageTop, current.width.toFloat(), pageBottom)
        current.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        when {
            event.x < width * .3f -> previous()
            event.x > width * .7f -> next()
            else -> onCenter?.invoke()
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
