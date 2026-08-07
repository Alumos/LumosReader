package xyz.alumos.lumosreader

import android.app.Activity
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import xyz.alumos.lumosreader.core.LumosSession
import xyz.alumos.lumosreader.reader.nativeview.BitmapPageView
import xyz.alumos.lumosreader.reader.nativeview.ComicReaderController
import xyz.alumos.lumosreader.reader.nativeview.EInkControllers
import uniffi.lumos_core.Book
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.LinkedHashMap

class ReaderActivity : Activity() {
    private val book: Book by lazy { requireNotNull(LumosSession.selectedBook) }
    private val eink by lazy { EInkControllers.create(getSharedPreferences("lumos_connection", MODE_PRIVATE).getBoolean("force_eink", false)) }
    private lateinit var stage: FrameLayout
    private lateinit var pageView: BitmapPageView
    private lateinit var status: TextView
    private var comic: ComicReaderController? = null
    private var pdf: PdfController? = null
    private var startedAt = 0L
    private var textPosition = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        val root = FrameLayout(this)
        stage = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status = TextView(this).apply { setTextColor(Color.BLACK); textSize = 11f; setPadding(dp(12), dp(6), dp(12), dp(6)); setBackgroundColor(Color.WHITE) }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        val back = Button(this).apply { text = "书库"; setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE); setOnClickListener { finish() } }
        root.addView(back, FrameLayout.LayoutParams(dp(70), dp(46), Gravity.TOP or Gravity.START))
        setContentView(root)
        startedAt = System.currentTimeMillis()
        when (book.format) {
            "cbz" -> openComic()
            "pdf" -> openPdf()
            "txt" -> openText()
            else -> finish()
        }
    }

    private fun openComic() {
        pageView = BitmapPageView(this)
        stage.addView(pageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        comic = ComicReaderController(book, pageView, eink) { current, total -> status.text = "$current / $total" }.also { controller ->
            if (book.pageDirection == "rtl") {
                pageView.onPrevious = controller::next
                pageView.onNext = controller::previous
            } else {
                pageView.onPrevious = controller::previous
                pageView.onNext = controller::next
            }
            pageView.onCenter = { eink.onMenu(stage) }
            controller.start()
        }
    }

    private fun openPdf() {
        pageView = BitmapPageView(this)
        stage.addView(pageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                val source = RangePdfSource(this, "/api/books/${book.id}/content", book.size.toLong())
                attachPdf(PdfController(source.descriptor, null, source, pageView, eink, ::savePdfPosition))
            }.onFailure { downloadPdfFallback() }
            return
        }
        downloadPdfFallback()
    }

    private fun downloadPdfFallback() {
        val file = File.createTempFile("lumos-pdf-", ".pdf", cacheDir)
        LumosSession.download("/api/books/${book.id}/content", file.absolutePath) { result ->
            result.onSuccess {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                attachPdf(PdfController(descriptor, file, null, pageView, eink, ::savePdfPosition))
            }.onFailure { file.delete() }
        }
    }

    private fun attachPdf(controller: PdfController) {
        pdf = controller
        pageView.onPrevious = controller::previous
        pageView.onNext = controller::next
        controller.show((book.progress * (controller.count - 1)).toInt())
    }

    private fun savePdfPosition(current: Int, total: Int) {
        status.text = "$current / $total"
        LumosSession.saveProgress(book.id, if (total <= 1) 1.0 else (current - 1).toDouble() / (total - 1), (current - 1).toString())
    }

    private fun openText() {
        val text = TextView(this).apply {
            setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE); textSize = 20f; setLineSpacing(0f, 1.55f); setPadding(dp(28), dp(54), dp(28), dp(40))
        }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(text) }
        stage.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        LumosSession.bytes("/api/books/${book.id}/content") { result -> result.onSuccess {
            text.text = decodeText(it)
            scroll.post {
                val range = (text.height - scroll.height).coerceAtLeast(0)
                scroll.scrollTo(0, (range * book.progress).toInt())
            }
        } }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val range = (text.height - scroll.height).coerceAtLeast(1)
            textPosition = (scroll.scrollY.toDouble() / range).coerceIn(0.0, 1.0)
            status.text = "${(textPosition * 100).toInt()}%"
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { comic?.previous() ?: pdf?.previous(); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { comic?.next() ?: pdf?.next(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        comic?.close()
        pdf?.close()
        if (book.format == "txt") LumosSession.saveProgress(book.id, textPosition)
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceIn(1, 300)
        LumosSession.addReadingTime(book.id, seconds)
        super.onDestroy()
    }

    private fun decodeText(bytes: ByteArray): String = runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private class PdfController(
    private val descriptor: ParcelFileDescriptor,
    private val file: File?,
    private val rangeSource: AutoCloseable?,
    private val view: BitmapPageView,
    private val eink: xyz.alumos.lumosreader.reader.nativeview.EInkController,
    private val position: (Int, Int) -> Unit,
) : AutoCloseable {
    private val renderer = PdfRenderer(descriptor)
    val count get() = renderer.pageCount
    private var index = 0
    private var requested = 0
    private val generation = AtomicInteger()
    fun previous() = show(requested - 1)
    fun next() = show(requested + 1)
    fun show(target: Int) {
        if (target !in 0 until count) return
        requested = target
        val run = generation.incrementAndGet()
        val width = view.width.coerceAtLeast(1200)
        LumosSession.task({ result -> result.onSuccess { bitmap ->
            if (generation.get() != run) { bitmap.recycle(); return@onSuccess }
            index = target; view.swapPage(bitmap); eink.onPageTurn(view); position(index + 1, count)
        } }) {
            synchronized(renderer) {
                renderer.openPage(target).use { page ->
                    val height = (width * page.height.toFloat() / page.width).toInt()
                    android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565).also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        }
    }
    override fun close() {
        generation.incrementAndGet()
        synchronized(renderer) { renderer.close() }
        descriptor.close()
        rangeSource?.close()
        file?.delete()
    }
}

@androidx.annotation.RequiresApi(26)
private class RangePdfSource(
    activity: Activity,
    private val path: String,
    private val size: Long,
) : ProxyFileDescriptorCallback(), AutoCloseable {
    private val thread = HandlerThread("lumos-pdf-range").apply { start() }
    private val chunks = object : LinkedHashMap<Long, ByteArray>(5, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 4
    }
    val descriptor: ParcelFileDescriptor = activity.getSystemService(StorageManager::class.java)
        .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, this, Handler(thread.looper))

    override fun onGetSize(): Long = size

    @Synchronized
    override fun onRead(offset: Long, requested: Int, data: ByteArray): Int {
        if (offset >= size) return 0
        val count = minOf(requested.toLong(), size - offset).toInt()
        var copied = 0
        try {
            while (copied < count) {
                val absolute = offset + copied
                val chunkStart = absolute / CHUNK * CHUNK
                val chunk = chunks[chunkStart] ?: LumosSession.rangeBlocking(path, chunkStart, minOf(size - 1, chunkStart + CHUNK - 1)).also { chunks[chunkStart] = it }
                val inside = (absolute - chunkStart).toInt()
                val amount = minOf(count - copied, chunk.size - inside)
                if (amount <= 0) break
                chunk.copyInto(data, copied, inside, inside + amount)
                copied += amount
            }
            return copied
        } catch (_: Exception) {
            throw ErrnoException("pdf range read", OsConstants.EIO)
        }
    }

    override fun onRelease() { chunks.clear() }
    override fun close() { thread.quitSafely(); chunks.clear() }

    companion object { private const val CHUNK = 64L * 1024L }
}
