package xyz.alumos.lumosreader.reader.nativeview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import xyz.alumos.lumosreader.core.LumosSession
import uniffi.lumos_core.Book
import uniffi.lumos_core.ComicPage
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ComicReaderController(
    private val book: Book,
    private val view: BitmapPageView,
    private val eink: EInkController,
    private val onPosition: (Int, Int) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private var pages: List<ComicPage> = emptyList()
    private var requested = 0
    private val generation = AtomicInteger()
    private val prefetched = object : LinkedHashMap<Int, Bitmap>(3, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean {
            if (size <= 2) return false
            eldest?.value?.takeIf { !it.isRecycled }?.recycle()
            return true
        }
    }
    private val prefetching = mutableSetOf<Int>()

    fun start() {
        LumosSession.comicPages(book.id) { result ->
            if (closed.get()) return@comicPages
            result.onSuccess {
                if (closed.get()) return@onSuccess
                pages = it
                requested = ((pages.size - 1) * book.progress).toInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                Log.i("LumosComic", "manifest pages=${pages.size} initial=$requested")
                show(requested)
            }.onFailure { Log.e("LumosComic", "manifest failed", it) }
        }
    }

    fun next() { Log.i("LumosComic", "next requested=$requested target=${requested + 1}"); show(requested + 1) }
    fun previous() { Log.i("LumosComic", "previous requested=$requested target=${requested - 1}"); show(requested - 1) }

    fun close() {
        closed.set(true)
        generation.incrementAndGet()
        prefetched.values.forEach { it.takeIf { bitmap -> !bitmap.isRecycled }?.recycle() }
        prefetched.clear()
        prefetching.clear()
    }

    private fun show(target: Int) {
        if (closed.get()) return
        if (target !in pages.indices) { Log.w("LumosComic", "ignored target=$target pages=${pages.size}"); return }
        requested = target
        val run = generation.incrementAndGet()
        val cached = prefetched.remove(target)
        if (cached != null) {
            Log.i("LumosComic", "memory hit target=$target")
            commit(target, cached)
            preloadAround(target)
            return
        }
        Log.i("LumosComic", "load target=$target generation=$run")
        val temporary = File.createTempFile("lumos-page-", ".img", view.context.cacheDir)
        LumosSession.download(pages[target].url, temporary.absolutePath) { result ->
            if (generation.get() != run) { temporary.delete(); return@download }
            result.onSuccess { LumosSession.task({ decoded ->
                temporary.delete()
                if (generation.get() != run) {
                    decoded.getOrNull()?.takeIf { !it.isRecycled }?.recycle()
                    return@task
                }
                decoded.onSuccess { bitmap -> commit(target, bitmap); preloadAround(target) }
                    .onFailure { Log.e("LumosComic", "decode failed target=$target", it) }
            }) { decode(temporary) } }.onFailure { temporary.delete(); Log.e("LumosComic", "download failed target=$target", it) }
        }
    }

    private fun commit(target: Int, bitmap: Bitmap) {
        requested = target
        view.swapPage(bitmap)
        Log.i("LumosComic", "commit target=$target display=${target + 1}/${pages.size}")
        eink.onPageTurn(view)
        val position = if (pages.size <= 1) 1.0 else target.toDouble() / (pages.size - 1)
        LumosSession.saveProgress(book.id, position, target.toString())
        onPosition(target + 1, pages.size)
    }

    private fun preloadAround(current: Int) {
        preload(current + 1)
        preload(current - 1)
    }

    private fun preload(target: Int) {
        if (target !in pages.indices || target in prefetched || !prefetching.add(target)) return
        val temporary = File.createTempFile("lumos-page-", ".img", view.context.cacheDir)
        LumosSession.download(pages[target].url, temporary.absolutePath) { result ->
            if (closed.get()) { temporary.delete(); prefetching.remove(target); return@download }
            result.onSuccess { LumosSession.task({ decoded ->
                temporary.delete()
                prefetching.remove(target)
                if (closed.get()) {
                    decoded.getOrNull()?.takeIf { !it.isRecycled }?.recycle()
                    return@task
                }
                decoded.onSuccess { bitmap ->
                    prefetched.put(target, bitmap)?.takeIf { !it.isRecycled }?.recycle()
                    Log.d("LumosComic", "prefetched target=$target")
                }.onFailure { Log.w("LumosComic", "prefetch decode failed target=$target", it) }
            }) { decode(temporary) } }.onFailure { temporary.delete(); prefetching.remove(target); Log.w("LumosComic", "prefetch failed target=$target") }
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
