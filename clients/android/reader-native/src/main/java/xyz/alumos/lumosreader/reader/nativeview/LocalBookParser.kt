package xyz.alumos.lumosreader.reader.nativeview

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.net.URLDecoder
import java.util.zip.ZipFile
import java.util.zip.Inflater
import java.io.StringReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.LinkedHashMap
import android.os.SystemClock
import android.util.Log

data class NativeChapter(val title: String, val text: String, val imageEntry: String? = null)
class NativeDocument(val chapters: List<NativeChapter>, private val chapterLoader: ((Int) -> NativeChapter)? = null, private val resourceLoader: ((String) -> ByteArray)? = null) {
    val isLazy: Boolean get() = chapterLoader != null
    private val loaded = object : LinkedHashMap<Int, NativeChapter>(CHAPTER_CACHE_SIZE, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, NativeChapter>?) = size > CHAPTER_CACHE_SIZE
    }
    private val loading = ConcurrentHashMap<Int, FutureTask<NativeChapter>>()
    fun isChapterLoaded(index: Int) = chapters[index].text.isNotBlank() || synchronized(loaded) { loaded.containsKey(index) }
    fun chapterAt(index: Int): NativeChapter {
        require(index in chapters.indices)
        val placeholder = chapters[index]
        if (placeholder.text.isNotBlank() || chapterLoader == null) return placeholder
        synchronized(loaded) { loaded[index] }?.let { return it }
        val candidate = FutureTask {
            val loadedChapter = chapterLoader.invoke(index)
            loadedChapter
        }
        val task = loading.putIfAbsent(index, candidate) ?: candidate.also(FutureTask<NativeChapter>::run)
        return try {
            val chapter = task.get()
            synchronized(loaded) { loaded[index] ?: chapter.also { loaded[index] = it } }
        } finally {
            loading.remove(index, task)
        }
    }
    fun resource(path: String): ByteArray = resourceLoader?.invoke(path) ?: error("远程资源不可用")

    private companion object { const val CHAPTER_CACHE_SIZE = 5 }
}

object LocalBookParser {
    private const val INDEX_MAGIC = 0x4c455049
    private const val INDEX_VERSION = 1
    private const val MAX_INDEX_ENTRIES = 1_000_000
    private const val MAX_INDEX_STRING_BYTES = 1024 * 1024
    fun parse(
        file: File,
        format: String,
        cachedEpubIndex: ByteArray? = null,
        onEpubIndex: (ByteArray) -> Unit = {},
    ): NativeDocument = when (format.lowercase()) {
        "epub" -> parseEpub(file, cachedEpubIndex, onEpubIndex)
        "mobi", "azw3" -> parseMobi(file)
        "txt" -> NativeDocument(listOf(NativeChapter("正文", decodeText(file.readBytes()))))
        else -> error("暂不支持本地解析 ${format.uppercase()}")
    }

    /** Reads only the ZIP central directory and requested EPUB entries over HTTP ranges. */
    fun parseRemoteEpub(
        size: Long,
        cachedEpubIndex: ByteArray? = null,
        onEpubIndex: (ByteArray) -> Unit = {},
        readRange: (Long, Long) -> ByteArray,
    ): NativeDocument {
        val startedAt = SystemClock.elapsedRealtime()
        fun trace(stage: String) = Log.i("LumosOpen", "parser +${SystemClock.elapsedRealtime() - startedAt}ms $stage")
        require(size > 0) { "EPUB 大小无效" }
        val archive = RemoteZip(readRange, size)
        trace("central directory entries=${archive.entries.size}")
        val entries = archive.entries
        fun entryBytes(path: String) = archive.read(path)
        decodeIndex(cachedEpubIndex)?.takeIf { index -> index.isNotEmpty() && index.all { entries.containsKey(it.path) } }?.let { index ->
            trace("persistent index hit spine=${index.size}")
            return remoteDocument(index, entries) { path -> entryBytes(path) }
        }
        val container = entryBytes("META-INF/container.xml").toString(Charsets.UTF_8)
        trace("container")
        val packagePath = Regex("full-path\\s*=\\s*[\"']([^\"']+)").find(container)?.groupValues?.get(1)
            ?: error("EPUB 未声明内容包")
        val packageXml = entryBytes(packagePath)
        val document = secureDocuments().newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(packageXml.inputStream())
        trace("package parsed bytes=${packageXml.size}")
        var navigationHref = ""
        var ncxHref = ""
        val manifest = buildMap {
            val nodes = document.getElementsByTagNameNS("*", "item")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val href = node.attributes.getNamedItem("href")?.nodeValue.orEmpty()
                val id = node.attributes.getNamedItem("id")?.nodeValue.orEmpty()
                put(id, href)
                if (node.attributes.getNamedItem("properties")?.nodeValue.orEmpty().split(' ').contains("nav")) navigationHref = href
                if (node.attributes.getNamedItem("media-type")?.nodeValue == "application/x-dtbncx+xml") ncxHref = href
            }
        }
        val base = packagePath.substringBeforeLast('/', "")
        val navigationTitles = if (navigationHref.isNotBlank()) {
            val navPath = normalizeEntry(base, navigationHref)
            val navHtml = entryBytes(navPath).toString(Charsets.UTF_8)
            Regex("<a[^>]+href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(navHtml).associate { match ->
                normalizeEntry(navPath.substringBeforeLast('/', ""), match.groupValues[1]) to htmlText(match.groupValues[2]).trim()
            }
        } else if (ncxHref.isNotBlank()) {
            val ncxPath = normalizeEntry(base, ncxHref)
            val ncx = entryBytes(ncxPath).toString(Charsets.UTF_8)
            parseNcxNavigation(ncx, ncxPath)
        } else emptyMap()
        trace("navigation titles=${navigationTitles.size}")
        val refs = buildList {
            val spine = document.getElementsByTagNameNS("*", "itemref")
            for (index in 0 until spine.length) {
                val id = spine.item(index).attributes.getNamedItem("idref")?.nodeValue ?: continue
                val href = manifest[id]?.takeIf(String::isNotBlank) ?: continue
                add(normalizeEntry(base, href) to href)
            }
        }
        val index = refs.map { (path, _) -> EpubIndexEntry(path, navigationTitles[path].orEmpty()) }
        runCatching { onEpubIndex(encodeIndex(index)) }
        trace("metadata ready spine=${index.size}")
        return remoteDocument(index, entries) { path -> entryBytes(path) }
    }

    private fun remoteDocument(index: List<EpubIndexEntry>, entries: Map<String, RemoteZip.Entry>, entryBytes: (String) -> ByteArray): NativeDocument {
        val metadata = index.mapIndexed { chapterIndex, item -> NativeChapter(indexTitle(item, chapterIndex), "") }
        return NativeDocument(metadata, { chapterIndex ->
            val item = index[chapterIndex]
            val path = item.path
            val html = entryBytes(path).toString(Charsets.UTF_8)
            val parsed = parseHtmlChapter(html, path, entries)
            parsed.copy(title = item.title.ifBlank { parsed.title })
        }, { path -> entryBytes(path) })
    }

    private fun parseHtmlChapter(html: String, href: String, entries: Map<String, RemoteZip.Entry>): NativeChapter {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let(::htmlText)?.trim().orEmpty()
            .ifBlank { href.substringAfterLast('/').substringBeforeLast('.') }
        val body = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1) ?: html
        val text = htmlText(body).replace(Regex("\\n{3,}"), "\\n\\n").trim()
        val imageHref = Regex("<(img|image)[^>]+(?:src|href|xlink:href)\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(2)
        val imageEntry = imageHref?.let { normalizeEntry(href.substringBeforeLast('/', ""), it).takeIf(entries::containsKey) }
        return NativeChapter(title, text, imageEntry)
    }

    /**
     * Opens only the EPUB package and navigation metadata. Chapter XHTML is read
     * on demand so a cached multi-thousand-chapter novel does not block its first
     * page on parsing the entire archive.
     */
    private fun parseEpub(file: File, cachedEpubIndex: ByteArray?, onEpubIndex: (ByteArray) -> Unit): NativeDocument = ZipFile(file).use { archive ->
        val archiveEntries = archive.entries().asSequence().associate { it.name to RemoteZip.Entry(0, it.compressedSize, it.size, 0) }
        decodeIndex(cachedEpubIndex)?.takeIf { index -> index.isNotEmpty() && index.all { archive.getEntry(it.path) != null } }?.let { index ->
            return localDocument(file, index, archiveEntries)
        }
        val container = archive.getEntry("META-INF/container.xml") ?: error("EPUB 缺少 container.xml")
        val containerXML = archive.getInputStream(container).use { it.readBytes().toString(Charsets.UTF_8) }
        val packagePath = Regex("full-path\\s*=\\s*[\"']([^\"']+)").find(containerXML)?.groupValues?.get(1)
            ?: error("EPUB 未声明内容包")
        val packageEntry = archive.getEntry(packagePath) ?: error("EPUB 内容包不存在")
        val document = archive.getInputStream(packageEntry).use { input ->
            secureDocuments().newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(input)
        }
        var navigationHref = ""
        var ncxHref = ""
        val manifest = buildMap {
            val nodes = document.getElementsByTagNameNS("*", "item")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val id = node.attributes.getNamedItem("id")?.nodeValue.orEmpty()
                val href = node.attributes.getNamedItem("href")?.nodeValue.orEmpty()
                put(id, href)
                if (node.attributes.getNamedItem("properties")?.nodeValue.orEmpty().split(' ').contains("nav")) navigationHref = href
                if (node.attributes.getNamedItem("media-type")?.nodeValue == "application/x-dtbncx+xml") ncxHref = href
            }
        }
        val base = packagePath.substringBeforeLast('/', "")
        val navigationTitles = when {
            navigationHref.isNotBlank() -> {
                val navPath = normalizeEntry(base, navigationHref)
                val nav = archive.getEntry(navPath)?.let { archive.getInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8) } }.orEmpty()
                Regex("<a[^>]+href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(nav).associate { match ->
                    normalizeEntry(navPath.substringBeforeLast('/', ""), match.groupValues[1]) to htmlText(match.groupValues[2]).trim()
                }
            }
            ncxHref.isNotBlank() -> {
                val ncxPath = normalizeEntry(base, ncxHref)
                val ncx = archive.getEntry(ncxPath)?.let { archive.getInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8) } }.orEmpty()
                parseNcxNavigation(ncx, ncxPath)
            }
            else -> emptyMap()
        }
        val spine = document.getElementsByTagNameNS("*", "itemref")
        val refs = buildList {
            for (index in 0 until spine.length) {
                val id = spine.item(index).attributes.getNamedItem("idref")?.nodeValue ?: continue
                val href = manifest[id]?.takeIf(String::isNotBlank) ?: continue
                val path = normalizeEntry(base, href)
                if (archive.getEntry(path) != null) add(path to href)
            }
        }
        require(refs.isNotEmpty()) { "EPUB 没有可阅读正文" }
        val index = refs.map { (path, _) ->
            EpubIndexEntry(path, navigationTitles[path].orEmpty())
        }
        runCatching { onEpubIndex(encodeIndex(index)) }
        localDocument(file, index, archiveEntries)
    }

    private fun localDocument(file: File, index: List<EpubIndexEntry>, entries: Map<String, RemoteZip.Entry>): NativeDocument {
        val metadata = index.mapIndexed { chapterIndex, item -> NativeChapter(indexTitle(item, chapterIndex), "") }
        return NativeDocument(metadata, { chapterIndex ->
            val item = index[chapterIndex]
            val path = item.path
            val html = ZipFile(file).use { current ->
                val entry = current.getEntry(path) ?: error("EPUB 缺少章节：$path")
                current.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val parsed = parseHtmlChapter(html, path, entries)
            parsed.copy(title = item.title.ifBlank { parsed.title })
        }, { entry -> image(file, entry) })
    }

    private data class EpubIndexEntry(val path: String, val title: String)
    private fun indexTitle(item: EpubIndexEntry, index: Int) = item.title.ifBlank {
        item.path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "第 ${index + 1} 章" }
    }

    private fun encodeIndex(index: List<EpubIndexEntry>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(INDEX_MAGIC)
            output.writeInt(INDEX_VERSION)
            output.writeInt(index.size)
            index.forEach { item -> output.writeSized(item.path); output.writeSized(item.title) }
        }
        bytes.toByteArray()
    }

    private fun decodeIndex(bytes: ByteArray?): List<EpubIndexEntry>? = runCatching {
        if (bytes == null) return null
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == INDEX_MAGIC && input.readInt() == INDEX_VERSION)
            val count = input.readInt()
            require(count in 1..MAX_INDEX_ENTRIES)
            List(count) { EpubIndexEntry(input.readSized(), input.readSized()) }
        }
    }.getOrNull()

    private fun DataOutputStream.writeSized(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size); write(bytes)
    }

    private fun DataInputStream.readSized(): String {
        val size = readInt()
        require(size in 0..MAX_INDEX_STRING_BYTES)
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun parseMobi(file: File): NativeDocument {
        val bytes = file.readBytes()
        require(bytes.size >= 100) { "MOBI 文件不完整" }
        val records = ushort(bytes, 76)
        val offsets = (0 until records).map { uint(bytes, 78 + it * 8) }.plus(bytes.size)
        val header = offsets.first()
        val compression = ushort(bytes, header)
        val textRecords = ushort(bytes, header + 8)
        if (compression !in setOf(1, 2)) error("此 MOBI/AZW3 使用暂不支持的 HuffDic 或加密压缩")
        val mobi = header + 16
        val encoding = if (ascii(bytes, mobi, 4) == "MOBI") uint(bytes, mobi + 28) else 65001
        val charset = if (encoding == 1252) Charset.forName("windows-1252") else Charsets.UTF_8
        val content = buildList<Byte> {
            for (index in 1..textRecords.coerceAtMost(records - 1)) {
                val raw = bytes.copyOfRange(offsets[index], offsets[index + 1])
                addAll((if (compression == 2) decompressPalmDoc(raw) else raw).toList())
            }
        }.toByteArray().toString(charset)
        val text = htmlText(content).replace(Regex("\\n{3,}"), "\n\n").trim()
        return NativeDocument(listOf(NativeChapter("正文", text.ifBlank { error("MOBI/AZW3 没有可阅读正文") })))
    }

    private fun decompressPalmDoc(source: ByteArray): ByteArray {
        val output = ArrayList<Byte>(source.size * 2)
        var index = 0
        while (index < source.size) {
            val value = source[index].toInt() and 0xff; index++
            when {
                value == 0 -> output += 0.toByte()
                value in 1..8 -> repeat(minOf(value, source.size - index)) { output += source[index++] }
                value in 9..0x7f -> output += value.toByte()
                value >= 0xc0 -> { output += 0x20.toByte(); output += (value xor 0x80).toByte() }
                index < source.size -> {
                    val pair = (value shl 8) or (source[index++].toInt() and 0xff)
                    val distance = (pair shr 3) and 0x7ff
                    val length = (pair and 7) + 3
                    repeat(length) { output += output[output.size - distance] }
                }
            }
        }
        return output.toByteArray()
    }

    private fun htmlText(value: String): String {
        val spaced = value
            .replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<(br|/p|/div|/h[1-6]|/li)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
        return Regex("&(#x[\\da-f]+|#\\d+|amp|lt|gt|quot|apos|nbsp);", RegexOption.IGNORE_CASE).replace(spaced) { match ->
            when (val entity = match.groupValues[1].lowercase()) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> runCatching { Character.toChars(if (entity.startsWith("#x")) entity.drop(2).toInt(16) else entity.drop(1).toInt()).concatToString() }.getOrDefault(match.value)
            }
        }
    }

    /**
     * Parses EPUB 2 navigation in one pass. A DOTALL expression over thousands
     * of navPoint elements repeatedly backtracks across the remaining document;
     * a 6,000 chapter novel can otherwise spend seconds here before reading a
     * single chapter.
     */
    private fun parseNcxNavigation(ncx: String, ncxPath: String): Map<String, String> {
        if (ncx.isBlank()) return emptyMap()
        data class Point(val title: StringBuilder = StringBuilder(), var source: String = "", var readingTitle: Boolean = false)
        val points = ArrayDeque<Point>()
        val result = LinkedHashMap<String, String>()
        val handler = object : DefaultHandler() {
            private fun name(localName: String, qName: String) = (localName.ifBlank { qName }).substringAfter(':').lowercase()

            override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
                when (name(localName, qName)) {
                    "navpoint" -> points.addLast(Point())
                    "text" -> points.lastOrNull()?.readingTitle = true
                    "content" -> points.lastOrNull()?.source = attributes.getValue("src").orEmpty()
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                points.lastOrNull()?.takeIf(Point::readingTitle)?.title?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String, qName: String) {
                when (name(localName, qName)) {
                    "text" -> points.lastOrNull()?.readingTitle = false
                    "navpoint" -> {
                        val point = points.removeLastOrNull() ?: return
                        if (point.source.isNotBlank()) {
                            val path = normalizeEntry(ncxPath.substringBeforeLast('/', ""), point.source)
                            result[path] = htmlText(point.title.toString()).trim()
                        }
                    }
                }
            }
        }
        secureSax().newSAXParser().xmlReader.apply {
            contentHandler = handler
            entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(ncx)))
        return result
    }

    private fun secureSax() = javax.xml.parsers.SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }

    private fun decodeText(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    fun image(file: File, entry: String): ByteArray = ZipFile(file).use { archive ->
        archive.getInputStream(archive.getEntry(entry) ?: error("EPUB 图片页不存在")).use { it.readBytes() }
    }
    private fun normalizeEntry(base: String, href: String): String {
        val decoded = URLDecoder.decode(href.substringBefore('#').substringBefore('?'), "UTF-8")
        val parts = ArrayDeque<String>()
        "$base/$decoded".split('/').forEach { part -> when (part) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeLast(); else -> parts.addLast(part) } }
        return parts.joinToString("/")
    }
    private fun secureDocuments() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Android vendors ship different XML implementations. Unsupported hardening
        // features must not make every EPUB unreadable; the entity resolver above is
        // the final guard against external entity access.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { isXIncludeAware = false }
        setExpandEntityReferences(false)
    }

    private class RemoteZip(private val readRange: (Long, Long) -> ByteArray, private val size: Long, provided: Map<String, Entry>? = null) {
        data class Entry(val method: Int, val compressed: Long, val uncompressed: Long, val localOffset: Long)
        val entries: Map<String, Entry> = provided ?: readCentralDirectory()

        fun read(name: String): ByteArray {
            val entry = entries[name] ?: error("EPUB 缺少条目：$name")
            val header = readRange(entry.localOffset, entry.localOffset + 30 - 1)
            require(le(header, 0) == 0x04034b50) { "EPUB ZIP 头无效" }
            val nameLength = leShort(header, 26); val extraLength = leShort(header, 28)
            val start = entry.localOffset + 30 + nameLength + extraLength
            val raw = readRange(start, start + entry.compressed - 1)
            return when (entry.method) {
                0 -> raw
                8 -> inflate(raw, entry.uncompressed)
                else -> error("EPUB 压缩格式暂不支持")
            }
        }

        private fun readCentralDirectory(): Map<String, Entry> {
            val tailSize = minOf(size, 131072L)
            val tailStart = size - tailSize
            val tail = readRange(tailStart, size - 1)
            var eocd = -1
            for (i in tail.size - 22 downTo 0) if (le(tail, i) == 0x06054b50) { eocd = i; break }
            require(eocd >= 0) { "EPUB ZIP 目录未找到" }
            val centralSize = leInt(tail, eocd + 12).toLong() and 0xffffffffL
            val centralOffset = leInt(tail, eocd + 16).toLong() and 0xffffffffL
            val central = if (centralOffset >= tailStart && centralOffset + centralSize <= size) tail.copyOfRange((centralOffset - tailStart).toInt(), (centralOffset - tailStart + centralSize).toInt()) else readRange(centralOffset, centralOffset + centralSize - 1)
            val result = LinkedHashMap<String, Entry>(); var p = 0
            while (p + 46 <= central.size && le(central, p) == 0x02014b50) {
                val method = leShort(central, p + 10); val compressed = leInt(central, p + 20).toLong() and 0xffffffffL; val uncompressed = leInt(central, p + 24).toLong() and 0xffffffffL
                val nameLength = leShort(central, p + 28); val extraLength = leShort(central, p + 30); val commentLength = leShort(central, p + 32); val offset = leInt(central, p + 42).toLong() and 0xffffffffL
                val name = central.copyOfRange(p + 46, p + 46 + nameLength).toString(Charsets.UTF_8)
                result[name] = Entry(method, compressed, uncompressed, offset); p += 46 + nameLength + extraLength + commentLength
            }
            require(result.isNotEmpty()) { "EPUB ZIP 目录为空" }; return result
        }

        private fun inflate(raw: ByteArray, expected: Long): ByteArray {
            val inflater = Inflater(true).apply { setInput(raw) }; val output = java.io.ByteArrayOutputStream(expected.coerceAtMost(16L * 1024 * 1024).toInt())
            val buffer = ByteArray(8192)
            while (!inflater.finished()) { val count = inflater.inflate(buffer); if (count == 0 && inflater.needsInput()) break; output.write(buffer, 0, count) }
            inflater.end(); return output.toByteArray()
        }
        private fun le(bytes: ByteArray, offset: Int) = leInt(bytes, offset)
        private fun leShort(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
        private fun leInt(bytes: ByteArray, offset: Int) = leShort(bytes, offset) or (leShort(bytes, offset + 2) shl 16)
    }
    private fun ushort(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    private fun uint(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun ascii(bytes: ByteArray, offset: Int, count: Int) = bytes.copyOfRange(offset, offset + count).toString(Charsets.US_ASCII)
}
