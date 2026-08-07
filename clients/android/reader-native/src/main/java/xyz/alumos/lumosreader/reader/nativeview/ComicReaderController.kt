package xyz.alumos.lumosreader.reader.nativeview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import xyz.alumos.lumosreader.core.LumosSession
import uniffi.lumos_core.Book
import uniffi.lumos_core.ComicPage
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class ComicReaderController(
    private val book: Book,
    private val view: BitmapPageView,
    private val eink: EInkController,
    private val onPosition: (Int, Int) -> Unit,
) {
    private var pages: List<ComicPage> = emptyList()
    private var index = 0
    private val generation = AtomicInteger()
    private var prefetched: Pair<Int, Bitmap>? = null

    fun start() {
        LumosSession.comicPages(book.id) { result ->
            result.onSuccess {
                pages = it
                index = ((pages.size - 1) * book.progress).toInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                show(index)
            }
        }
    }

    fun next() = show(index + 1)
    fun previous() = show(index - 1)

    fun close() {
        generation.incrementAndGet()
        prefetched?.second?.takeIf { !it.isRecycled }?.recycle()
        prefetched = null
    }

    private fun show(target: Int) {
        if (target !in pages.indices) return
        val run = generation.incrementAndGet()
        val cached = prefetched?.takeIf { it.first == target }
        if (cached != null) {
            prefetched = null
            commit(target, cached.second)
            preload(target)
            return
        }
        val temporary = File.createTempFile("lumos-page-", ".img", view.context.cacheDir)
        LumosSession.download(pages[target].url, temporary.absolutePath) { result ->
            if (generation.get() != run) { temporary.delete(); return@download }
            result.onSuccess { LumosSession.task({ decoded ->
                temporary.delete()
                if (generation.get() != run) {
                    decoded.getOrNull()?.takeIf { !it.isRecycled }?.recycle()
                    return@task
                }
                decoded.onSuccess { bitmap -> commit(target, bitmap); preload(target) }
            }) { decode(temporary) } }.onFailure { temporary.delete() }
        }
    }

    private fun commit(target: Int, bitmap: Bitmap) {
        index = target
        view.swapPage(bitmap)
        eink.onPageTurn(view)
        val position = if (pages.size <= 1) 1.0 else target.toDouble() / (pages.size - 1)
        LumosSession.saveProgress(book.id, position, target.toString())
        onPosition(target + 1, pages.size)
    }

    private fun preload(current: Int) {
        val target = current + 1
        if (target !in pages.indices) return
        val run = generation.get()
        val temporary = File.createTempFile("lumos-page-", ".img", view.context.cacheDir)
        LumosSession.download(pages[target].url, temporary.absolutePath) { result ->
            result.onSuccess { LumosSession.task({ decoded ->
                temporary.delete()
                if (generation.get() != run) {
                    decoded.getOrNull()?.takeIf { !it.isRecycled }?.recycle()
                    return@task
                }
                decoded.onSuccess { bitmap ->
                    prefetched?.second?.takeIf { !it.isRecycled }?.recycle()
                    prefetched = target to bitmap
                }
            }) { decode(temporary) } }.onFailure { temporary.delete() }
        }
    }

    private fun decode(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        val target = maxOf(view.width, view.height).coerceAtLeast(1600) * 2
        while (longest / sample > target) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (isLikelyMonochrome(file, bounds)) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: error("无法解码漫画页")
    }

    private fun isLikelyMonochrome(file: File, bounds: BitmapFactory.Options): Boolean {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 48) sample *= 2
        val probe = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return false
        var colored = 0
        var checked = 0
        for (y in 0 until probe.height step 2) for (x in 0 until probe.width step 2) {
            val pixel = probe.getPixel(x, y)
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            if (maxOf(red, green, blue) - minOf(red, green, blue) > 10) colored++
            checked++
        }
        probe.recycle()
        return checked > 0 && colored * 50 < checked
    }
}
