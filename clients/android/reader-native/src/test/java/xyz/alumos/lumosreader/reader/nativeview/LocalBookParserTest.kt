package xyz.alumos.lumosreader.reader.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class LocalBookParserTest {
    @Test fun parsesEpubSpineAndRelativePaths() {
        val file = File.createTempFile("lumos-parser-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
            entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""")
            entry("OPS/package.opf", """<package xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><manifest><item id="one" href="text/../text/one.xhtml"/><item id="two" href="text/two.xhtml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""")
            entry("OPS/text/one.xhtml", "<html><head><title>第一章</title></head><body><h1>标题</h1><p>你好&amp;世界</p></body></html>")
            entry("OPS/text/two.xhtml", "<html><head><title>第二章</title></head><body><p>结尾</p></body></html>")
        }
        val parsed = LocalBookParser.parse(file, "epub")
        assertTrue(parsed.isLazy)
        assertEquals("第一章", parsed.chapterAt(0).title)
        assertEquals("第二章", parsed.chapterAt(1).title)
        assertTrue(parsed.chapterAt(0).text.contains("你好&世界"))
        file.delete()
    }

    @Test fun extractsFixedLayoutImagePage() {
        val file = File.createTempFile("lumos-image-parser-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
            entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""".toByteArray())
            entry("OPS/package.opf", """<package><manifest><item id="page" href="page.xhtml"/></manifest><spine><itemref idref="page"/></spine></package>""".toByteArray())
            entry("OPS/page.xhtml", "<html><body><img src=\"images/page.jpg\"/></body></html>".toByteArray())
            entry("OPS/images/page.jpg", byteArrayOf(1, 2, 3, 4))
        }
        val entry = LocalBookParser.parse(file, "epub").chapterAt(0).imageEntry
        assertEquals(4, LocalBookParser.image(file, requireNotNull(entry)).size)
        file.delete()
    }

    @Test fun usesEpub2NcxChapterTitlesInsteadOfFileNumbers() {
        val file = File.createTempFile("lumos-ncx-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
            entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles></container>""")
            entry("OPS/content.opf", """<package><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="c1" href="001.xhtml"/><item id="c2" href="002.xhtml"/></manifest><spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
            entry("OPS/toc.ncx", """<ncx><navMap><navPoint><navLabel><text>第一章 开始</text></navLabel><content src="001.xhtml"/></navPoint><navPoint><navLabel><text>第二章 继续</text></navLabel><content src="002.xhtml"/></navPoint></navMap></ncx>""")
            entry("OPS/001.xhtml", "<html><head><title>001</title></head><body>正文一</body></html>")
            entry("OPS/002.xhtml", "<html><head><title>002</title></head><body>正文二</body></html>")
        }
        val parsed = LocalBookParser.parse(file, "epub")
        assertEquals(listOf("第一章 开始", "第二章 继续"), parsed.chapters.map(NativeChapter::title))
        file.delete()
    }

    @Test fun parsesLargeEpub2NavigationInLinearTime() {
        val count = 6_100
        val file = File.createTempFile("lumos-large-ncx-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
            val manifest = buildString {
                append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
                repeat(count) { append("<item id=\"c$it\" href=\"$it.xhtml\"/>") }
            }
            val spine = buildString { repeat(count) { append("<itemref idref=\"c$it\"/>") } }
            val ncx = buildString {
                append("<ncx><navMap>")
                repeat(count) { append("<navPoint><navLabel><text>Chapter $it</text></navLabel><content src=\"$it.xhtml\"/></navPoint>") }
                append("</navMap></ncx>")
            }
            entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles></container>""")
            entry("OPS/content.opf", "<package><manifest>$manifest</manifest><spine toc=\"ncx\">$spine</spine></package>")
            entry("OPS/toc.ncx", ncx)
            repeat(count) { entry("OPS/$it.xhtml", "<html><body>Body $it</body></html>") }
        }
        lateinit var parsed: NativeDocument
        var index: ByteArray? = null
        val elapsed = measureTimeMillis { parsed = LocalBookParser.parse(file, "epub", onEpubIndex = { index = it }) }
        assertEquals(count, parsed.chapters.size)
        assertEquals("Chapter 6099", parsed.chapters.last().title)
        assertTrue("large NCX parse took ${elapsed}ms", elapsed < 3_000)
        val indexedElapsed = measureTimeMillis { parsed = LocalBookParser.parse(file, "epub", index) }
        assertEquals("Chapter 6099", parsed.chapters.last().title)
        assertTrue("cached index parse took ${indexedElapsed}ms", indexedElapsed < elapsed)
        file.delete()
    }

    @Test fun rejectsUnsupportedFormat() {
        val file = File.createTempFile("lumos-parser-", ".bin")
        val error = runCatching { LocalBookParser.parse(file, "docx") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("暂不支持"))
        file.delete()
    }

    @Test fun lazyDocumentCoalescesConcurrentReadsOfTheSameChapter() {
        val loads = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val document = NativeDocument(listOf(NativeChapter("第一章", "")), chapterLoader = {
            loads.incrementAndGet()
            started.countDown()
            release.await()
            NativeChapter("第一章", "正文")
        })
        val results = arrayOfNulls<NativeChapter>(2)
        val first = Thread { results[0] = document.chapterAt(0) }.apply { start() }
        started.await()
        val second = Thread { results[1] = document.chapterAt(0) }.apply { start() }
        release.countDown()
        first.join(); second.join()
        assertEquals(1, loads.get())
        assertEquals("正文", results[0]?.text)
        assertEquals(results[0], results[1])
    }
}
