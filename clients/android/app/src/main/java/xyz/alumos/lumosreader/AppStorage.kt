package xyz.alumos.lumosreader

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import android.os.SystemClock
import android.util.Log

data class LocalFont(val name: String, val size: Long, val uri: Uri)

class FontStorage(private val context: Context) {
    private val legacyDirectory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LumosReader/Fonts")

    fun list(): List<LocalFont> = if (Build.VERSION.SDK_INT >= 29) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.RELATIVE_PATH),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("%LumosReader/Fonts/%"),
            "${MediaStore.Downloads.DISPLAY_NAME} COLLATE NOCASE",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    val path = cursor.getString(3).orEmpty().replace('\\', '/').trim('/')
                    if (path.endsWith("LumosReader/Fonts", ignoreCase = true) && name.substringAfterLast('.', "").lowercase() in EXTENSIONS) {
                        add(LocalFont(name, cursor.getLong(2), ContentUris.withAppendedId(collection, cursor.getLong(0))))
                    }
                }
            }
        }.orEmpty().distinctBy { it.name.lowercase() }
    } else {
        legacyDirectory.listFiles()?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }?.map { LocalFont(it.name, it.length(), Uri.fromFile(it)) }?.distinctBy { it.name.lowercase() }.orEmpty()
    }

    fun save(name: String, bytes: ByteArray) {
        val safe = File(name).name
        require(safe == name && safe.substringAfterLast('.', "").lowercase() in EXTENSIONS)
        delete(safe)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Downloads.MIME_TYPE, mime(safe))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        } else {
            legacyDirectory.mkdirs()
            File(legacyDirectory, safe).writeBytes(bytes)
        }
    }

    fun delete(name: String): Boolean {
        val font = list().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        return if (font.uri.scheme == "file") File(requireNotNull(font.uri.path)).delete()
        else context.contentResolver.delete(font.uri, null, null) > 0
    }

    fun open(font: LocalFont) = if (font.uri.scheme == "file") ParcelFont(File(requireNotNull(font.uri.path))) else ParcelFont(context.contentResolver.openFileDescriptor(font.uri, "r"))

    class ParcelFont private constructor(private val parcel: android.os.ParcelFileDescriptor?, private val file: File?) : AutoCloseable {
        constructor(parcel: android.os.ParcelFileDescriptor?) : this(parcel, null)
        constructor(file: File) : this(null, file)
        val descriptor get() = parcel?.fileDescriptor
        fun typeface(): android.graphics.Typeface = file?.let(android.graphics.Typeface::createFromFile)
            ?: if (Build.VERSION.SDK_INT >= 26) modernTypeface(requireNotNull(descriptor)) else error("此系统无法打开内容字体")
        @androidx.annotation.RequiresApi(26)
        private fun modernTypeface(descriptor: java.io.FileDescriptor) = android.graphics.Typeface.Builder(descriptor).build()
        override fun close() { parcel?.close() }
    }

    companion object {
        const val RELATIVE_PATH = "Download/LumosReader/Fonts/"
        val EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")
        private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "otf" -> "font/otf"; "woff" -> "font/woff"; "woff2" -> "font/woff2"; else -> "font/ttf"
        }
    }
}

class ReaderCache(private val context: Context) {
    private val prefs = context.getSharedPreferences("lumos_reader", Context.MODE_PRIVATE)
    private val rangeLocks = ConcurrentHashMap<String, Any>()
    val directory = File(context.cacheDir, "reader").apply { mkdirs() }
    var limitBytes: Long
        get() = prefs.getLong("cache_limit", 500L * MB)
        set(value) { prefs.edit().putLong("cache_limit", value).apply(); trim() }

    fun sizeBytes(): Long = context.cacheDir.walkTopDown().filter(File::isFile).sumOf(File::length)
    private fun managedSizeBytes(): Long = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
    fun file(bookId: String, extension: String): File = File(directory, "${bookId.filter(Char::isLetterOrDigit)}.$extension")
    fun epubIndex(bookId: String, bookSize: Long): ByteArray? {
        val target = epubIndexFile(bookId, bookSize)
        return target.takeIf(File::isFile)?.let { runCatching { it.readBytes().also { touch(target) } }.getOrNull() }
    }
    fun putEpubIndex(bookId: String, bookSize: Long, bytes: ByteArray) {
        require(bytes.isNotEmpty())
        val target = epubIndexFile(bookId, bookSize)
        val part = File(target.parentFile, "${target.name}.part")
        part.delete(); part.writeBytes(bytes); target.delete()
        check(part.renameTo(target)) { "无法保存 EPUB 索引" }
        touch(target)
    }
    private fun epubIndexFile(bookId: String, bookSize: Long) = File(directory, "${bookId.filter(Char::isLetterOrDigit)}-$bookSize.epub-index")
    fun range(bookId: String, start: Long, endInclusive: Long, loader: () -> ByteArray): ByteArray {
        val ranges = File(directory, "${bookId.filter(Char::isLetterOrDigit)}.ranges").apply { mkdirs() }
        val expected = endInclusive - start + 1
        val target = File(ranges, "$start-$endInclusive.bin")
        val key = target.absolutePath
        // Locks live only for this ReaderCache instance. Keeping them avoids a
        // remove/recreate race while another thread is waiting for the same block.
        // ConcurrentHashMap.computeIfAbsent requires Android 7; retain API 23.
        val candidate = Any()
        val lock = rangeLocks.putIfAbsent(key, candidate) ?: candidate
        return synchronized(lock) {
            if (target.isFile && target.length() == expected) {
                val started = SystemClock.elapsedRealtime()
                val bytes = target.readBytes()
                Log.i("LumosOpen", "range cache hit start=$start bytes=$expected readMs=${SystemClock.elapsedRealtime() - started}")
                touch(target)
                return@synchronized bytes
            }
            val started = SystemClock.elapsedRealtime()
            val bytes = loader()
            Log.i("LumosOpen", "range network start=$start bytes=$expected loadMs=${SystemClock.elapsedRealtime() - started}")
            require(bytes.size.toLong() == expected) { "Range 响应长度无效" }
            val part = File(ranges, "${target.name}.part")
            part.delete(); part.writeBytes(bytes); target.delete()
            check(part.renameTo(target)) { "无法保存流式缓存" }
            // Avoid walking the entire cache on every streamed block. The cache
            // is trimmed when reading closes and whenever its limit changes.
            touch(target); bytes
        }
    }

    /**
     * Serves arbitrary EPUB ZIP reads from aligned blocks. ZIP metadata causes many
     * tiny adjacent reads; grouping them removes most HTTP round trips and makes
     * subsequent opens fully cache-backed without downloading the whole book.
     */
    fun blockedRange(
        bookId: String,
        fileSize: Long,
        start: Long,
        endInclusive: Long,
        loader: (Long, Long) -> ByteArray,
    ): ByteArray {
        require(start >= 0 && endInclusive >= start && endInclusive < fileSize)
        val requestedSize = endInclusive - start + 1
        require(requestedSize <= Int.MAX_VALUE) { "Range 请求过大" }
        val output = java.io.ByteArrayOutputStream(requestedSize.toInt())
        var position = start
        while (position <= endInclusive) {
            val blockStart = position / RANGE_BLOCK_BYTES * RANGE_BLOCK_BYTES
            val blockEnd = minOf(fileSize - 1, blockStart + RANGE_BLOCK_BYTES - 1)
            val block = range(bookId, blockStart, blockEnd) { loader(blockStart, blockEnd) }
            val from = (position - blockStart).toInt()
            val through = minOf(blockEnd, endInclusive)
            output.write(block, from, (through - position + 1).toInt())
            position = through + 1
        }
        return output.toByteArray()
    }
    fun touch(file: File) { file.setLastModified(System.currentTimeMillis()) }
    fun clear(): Boolean = context.cacheDir.listFiles()?.map { it.deleteRecursively() }?.all { it } != false
    @Synchronized fun trim() {
        var total = managedSizeBytes()
        directory.walkTopDown().filter(File::isFile).sortedBy(File::lastModified).forEach {
            if (total <= limitBytes) return
            val size = it.length()
            if (it.delete()) total -= size
        }
    }

    companion object {
        const val MB = 1024L * 1024L
        private const val RANGE_BLOCK_BYTES = 512L * 1024L
    }
}
