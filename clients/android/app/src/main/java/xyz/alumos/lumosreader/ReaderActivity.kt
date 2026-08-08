package xyz.alumos.lumosreader

import android.app.Activity
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import xyz.alumos.lumosreader.core.LumosSession
import xyz.alumos.lumosreader.reader.nativeview.BitmapPageView
import xyz.alumos.lumosreader.reader.nativeview.ComicReaderController
import xyz.alumos.lumosreader.reader.nativeview.EInkControllers
import xyz.alumos.lumosreader.reader.nativeview.LocalBookParser
import xyz.alumos.lumosreader.reader.nativeview.NativeDocument
import xyz.alumos.lumosreader.reader.nativeview.NativeTextPageView
import uniffi.lumos_core.Book
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

class ReaderActivity : androidx.activity.ComponentActivity() {
    private val openStartedAt = SystemClock.elapsedRealtime()
    private val book: Book by lazy { requireNotNull(LumosSession.selectedBook) }
    private val einkMode by lazy { isEInkDevice() }
    private val eink by lazy { EInkControllers.create(einkMode) }
    private val cache by lazy { ReaderCache(this) }
    private val settingsStore by lazy { ReaderSettingsStore(this) }
    private val fontStorage by lazy { FontStorage(this) }
    private val loadedTypefaces = mutableMapOf<String, Typeface>()
    private var settings by mutableStateOf(ReaderSettings(19, 1.75f, 28, ""))
    private lateinit var stage: FrameLayout
    private var pageView: BitmapPageView? = null
    private var textView: NativeTextPageView? = null
    private var documentImageView: BitmapPageView? = null
    private var comic: ComicReaderController? = null
    private var pdf: PdfController? = null
    private var document: NativeDocument? = null
    private var documentFile: File? = null
    private val fixedPageGeneration = AtomicInteger()
    private val fixedPageCache = object : LinkedHashMap<Int, android.graphics.Bitmap>(3, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, android.graphics.Bitmap>?): Boolean {
            if (size <= 2) return false
            eldest?.value?.takeIf { !it.isRecycled }?.recycle()
            return true
        }
    }
    private val fixedPageLoading = mutableSetOf<Int>()
    private var chapter = 0
    private var currentProgress = 0.0
    private var startedAt = 0L
    private var composeChrome by mutableStateOf(false)
    private var composeStatus by mutableStateOf("")
    private var composeProgress by mutableDoubleStateOf(0.0)
    private var readerPanel by mutableStateOf<ReaderPanel?>(null)
    private var chapterPage by mutableIntStateOf(0)
    private var chapterReverse by mutableStateOf(false)
    private var styleAdvanced by mutableStateOf(false)
    private var templateNameDraft by mutableStateOf("")
    private var templateRevision by mutableIntStateOf(0)
    private var deleteTemplateName by mutableStateOf<String?>(null)
    private var catalogKind by mutableStateOf(CatalogKind.CHAPTERS)
    private val textChapterLoading = mutableSetOf<Int>()
    @Volatile private var readerDestroyed = false

    private enum class ReaderPanel { CHAPTERS, STYLE }
    private enum class CatalogKind { VOLUMES, CHAPTERS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTrace("activity created")
        settings = settingsStore.load(einkMode)
        window.statusBarColor = Color.WHITE; window.navigationBarColor = Color.WHITE
        stage = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContent { ReaderComposeShell() }
        openTrace("compose installed")
        startedAt = System.currentTimeMillis()
        when (book.format) {
            "cbz" -> openComic()
            "pdf" -> openPdf()
            "txt", "epub", "mobi", "azw3" -> openDocument()
            else -> showFailure("暂不支持 ${book.format.uppercase()}")
        }
    }

    private fun toggleChrome() { composeChrome = !composeChrome; eink.onMenu(stage) }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun ReaderComposeShell() {
        val dark = settings.background == "black" && !einkMode
        val background = when { dark -> ComposeColor(0xFF121412); settings.background == "green" && !einkMode -> ComposeColor(0xFFE8F1E5); else -> ComposeColor.White }
        val foreground = if (dark) ComposeColor.White else ComposeColor.Black
        LumosTheme(einkMode, dark) {
            Box(Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
                AndroidView(factory = { stage }, modifier = Modifier.fillMaxSize())
                Text(
                    composeStatus,
                    Modifier.align(Alignment.BottomEnd).fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    color = foreground,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (composeChrome) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), shape = LumosShape, border = lumosBorder(einkMode), shadowElevation = if (einkMode) 0.dp else 8.dp, color = if (dark) ComposeColor(0xFF202320) else ComposeColor.White) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(book.title, color = foreground, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(composeStatus, Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = foreground)
                            Text("${(composeProgress * 100).toInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.labelMedium, color = foreground)
                        }
                        val progressInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Slider(
                            value = composeProgress.toFloat(),
                            onValueChange = { composeProgress = it.toDouble(); seekTo(it.toDouble()) },
                            onValueChangeFinished = ::saveCurrentProgress,
                            modifier = Modifier.fillMaxWidth(.82f).height(26.dp).align(Alignment.CenterHorizontally),
                            interactionSource = progressInteraction,
                            thumb = { androidx.compose.material3.SliderDefaults.Thumb(progressInteraction, Modifier.size(4.dp, 18.dp)) },
                            track = { androidx.compose.material3.SliderDefaults.Track(it, Modifier.height(2.dp)) },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button({ readerPanel = ReaderPanel.CHAPTERS; composeChrome = false }, Modifier.weight(1f).height(46.dp), shape = LumosShape, border = lumosBorder(einkMode), colors = lumosButtonColors(einkMode)) { Text("章节与卷册") }
                            Button({ readerPanel = ReaderPanel.STYLE; styleAdvanced = false; composeChrome = false }, Modifier.weight(1f).height(46.dp), enabled = textView?.visibility == View.VISIBLE, shape = LumosShape, border = lumosBorder(einkMode), colors = lumosButtonColors(einkMode)) { Text("样式排版") }
                        }
                    }
                }
                when (readerPanel) {
                    ReaderPanel.CHAPTERS -> ChapterPanel(Modifier.align(Alignment.Center))
                    ReaderPanel.STYLE -> StylePanel(Modifier.align(Alignment.Center))
                    null -> Unit
                }
            }
        }
    }

    @Composable
    private fun ReaderPanelFrame(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        Box(Modifier.fillMaxSize().background(if (einkMode) ComposeColor.Transparent else ComposeColor.Black.copy(alpha = .32f)).clickable { readerPanel = null }, contentAlignment = Alignment.Center) {
            Surface(modifier.fillMaxWidth(if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) .88f else .94f).fillMaxHeight(.88f).clickable(enabled = false) {}, shape = LumosShape, border = lumosBorder(einkMode), shadowElevation = if (einkMode) 0.dp else 8.dp) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f)); androidx.compose.material3.TextButton({ readerPanel = null }) { Text("关闭") } }
                    Spacer(Modifier.height(8.dp)); content()
                }
            }
        }
    }

    @Composable
    private fun ChapterPanel(modifier: Modifier) {
        val volumeBooks = LumosSession.selectedCollection.takeIf { it.size > 1 }.orEmpty()
        val volumes = volumeBooks.map { it.fileName.substringBeforeLast('.') }
        val chapters = document?.chapters?.map { it.title }.orEmpty()
        LaunchedEffect(volumes.size, chapters.size) {
            if (chapters.isEmpty() && volumes.isNotEmpty()) catalogKind = CatalogKind.VOLUMES
            else if (volumes.isEmpty()) catalogKind = CatalogKind.CHAPTERS
        }
        if (catalogKind == CatalogKind.VOLUMES && volumes.isEmpty()) catalogKind = CatalogKind.CHAPTERS
        val source = if (catalogKind == CatalogKind.VOLUMES) volumes else chapters
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val columns = if (landscape) 2 else 1
        val rows = if (landscape) 6 else 9
        val size = columns * rows
        val ordered = if (chapterReverse) source.asReversed() else source
        val pages = ((ordered.size + size - 1) / size).coerceAtLeast(1)
        chapterPage = chapterPage.coerceIn(0, pages - 1)
        var pageDraft by remember(chapterPage, pages) { mutableStateOf((chapterPage + 1).toString()) }
        val keyboard = LocalSoftwareKeyboardController.current
        fun jumpToDraft() {
            chapterPage = ((pageDraft.toIntOrNull() ?: chapterPage + 1) - 1).coerceIn(0, pages - 1)
            pageDraft = (chapterPage + 1).toString()
            keyboard?.hide()
        }
        ReaderPanelFrame("章节与卷册", modifier) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(catalogKind == CatalogKind.CHAPTERS, { catalogKind = CatalogKind.CHAPTERS; chapterPage = 0 }, { Text("章节 · ${chapters.size}") }, Modifier.weight(1f), border = lumosBorder(einkMode), colors = lumosFilterChipColors(einkMode))
                androidx.compose.material3.FilterChip(catalogKind == CatalogKind.VOLUMES, { if (volumes.isNotEmpty()) { catalogKind = CatalogKind.VOLUMES; chapterPage = 0 } }, { Text("卷册 · ${volumes.size}") }, Modifier.weight(1f), enabled = volumes.isNotEmpty(), border = lumosBorder(einkMode), colors = lumosFilterChipColors(einkMode))
            }
            Text(if (catalogKind == CatalogKind.CHAPTERS) "当前文件内部章节" else "当前作品目录下的独立卷册", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.FilterChip(chapterReverse, { chapterReverse = !chapterReverse; chapterPage = 0 }, { Text(if (chapterReverse) "倒序" else "正序") }, Modifier.weight(1f).height(44.dp), shape = LumosShape, border = lumosBorder(einkMode), colors = lumosFilterChipColors(einkMode))
                androidx.compose.material3.OutlinedButton({
                    val current = if (catalogKind == CatalogKind.CHAPTERS) chapter else volumeBooks.indexOfFirst { it.id == book.id }.coerceAtLeast(0)
                    val position = if (chapterReverse) source.lastIndex - current else current
                    chapterPage = (position / size).coerceAtLeast(0)
                }, Modifier.weight(1f).height(44.dp), shape = LumosShape, border = lumosBorder(einkMode), colors = lumosOutlinedButtonColors(einkMode), contentPadding = PaddingValues(horizontal = 14.dp)) { Text(if (catalogKind == CatalogKind.CHAPTERS) "定位此章节" else "定位此卷") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("第")
                BasicTextField(
                    pageDraft, { pageDraft = it.filter(Char::isDigit).take(4) },
                    Modifier.width(48.dp).height(32.dp), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { jumpToDraft() }),
                    decorationBox = { input ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { input() }
                            androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                )
                Text("/$pages 页")
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(rows) { row -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(columns) { column ->
                        val visual = chapterPage * size + row * columns + column
                        val label = ordered.getOrNull(visual)
                        val actual = if (chapterReverse) source.lastIndex - visual else visual
                        val current = if (catalogKind == CatalogKind.CHAPTERS) actual == chapter else volumeBooks.getOrNull(actual)?.id == book.id
                        Surface(Modifier.weight(1f).fillMaxHeight().clickable(enabled = label != null) {
                            if (catalogKind == CatalogKind.VOLUMES) { saveCurrentProgress(); LumosSession.selectedBook = volumeBooks[actual]; recreate() }
                            else { showChapter(actual); readerPanel = null }
                        }, shape = LumosShape, border = lumosBorder(einkMode), color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (label == null) "" else if (catalogKind == CatalogKind.VOLUMES) "卷 ${actual + 1}" else "${actual + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(8.dp)); Text(label.orEmpty(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                } }
            }
            Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedButton({ chapterPage-- }, Modifier.weight(1f), enabled = chapterPage > 0, shape = LumosShape, border = lumosBorder(einkMode), colors = lumosOutlinedButtonColors(einkMode)) { Text("上一页") }
                androidx.compose.material3.Button({ chapterPage++ }, Modifier.weight(1f), enabled = chapterPage + 1 < pages, shape = LumosShape, border = lumosBorder(einkMode), colors = lumosButtonColors(einkMode)) { Text("下一页") }
            }
        }
    }

    @Composable
    private fun StylePanel(modifier: Modifier) = ReaderPanelFrame(if (styleAdvanced) "更多设置" else "基础设置", modifier) {
        if (styleAdvanced) AdvancedStyleContent() else BasicStyleContent()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ColumnScope.BasicStyleContent() {
        val customTemplates = remember(templateRevision) { settingsStore.templates().take(3) }
        val templates = builtInTemplates().take(3).map { Triple(it.first, it.second, false) } +
            customTemplates.map { Triple(it.first, it.second, true) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("排版模板", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                templates.forEach { (name, value, custom) ->
                    Surface(
                        Modifier.height(44.dp).combinedClickable(
                            onClick = { settings = value.copy(templateName = name); applyAndSave() },
                            onLongClick = { if (custom) deleteTemplateName = name },
                        ),
                        shape = LumosShape, border = lumosBorder(einkMode),
                        color = if (settings.templateName == name) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    ) { Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { Text(name, maxLines = 1) } }
                }
            }
            ValueSlider("正文字号", settings.fontSize.toFloat(), 12f..28f, "${settings.fontSize} sp") { settings = settings.copy(fontSize = it.toInt()); applyAndSave() }
            Text("阅读背景", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("白色" to "white", "浅绿" to "green", "黑色" to "black").forEach { (label, value) -> androidx.compose.material3.FilterChip((if (einkMode) "white" else settings.background) == value, { if (!einkMode || value == "white") { settings = settings.copy(background = value); applyAndSave() } }, { Text(label) }, enabled = !einkMode || value == "white", border = lumosBorder(einkMode), colors = lumosFilterChipColors(einkMode)) } }
            val fonts = remember { FontStorage(this@ReaderActivity).list() }
            Text("字体", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            FontDropdown("标题字体", settings.titleFontName, fonts) { settings = settings.copy(titleFontName = it); applyAndSave() }
            FontDropdown("正文字体", settings.fontName, fonts) { settings = settings.copy(fontName = it); applyAndSave() }
            Text("自定义模板", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(templateNameDraft, { templateNameDraft = it }, Modifier.weight(1f).height(56.dp), label = { Text("新模板名称") }, singleLine = true, shape = LumosShape)
                androidx.compose.material3.Button({ val name = templateNameDraft.trim(); if (name.isNotEmpty() && customTemplates.size < 3) { settingsStore.saveTemplate(name, settings); settings = settings.copy(templateName = name); settingsStore.save(settings); templateNameDraft = ""; templateRevision++ } }, Modifier.width(96.dp).height(56.dp), enabled = customTemplates.size < 3, shape = LumosShape, border = lumosBorder(einkMode), colors = lumosButtonColors(einkMode)) { Text("保存") }
            }
        }
        Spacer(Modifier.height(12.dp)); androidx.compose.material3.Button({ styleAdvanced = true }, Modifier.fillMaxWidth().height(48.dp), shape = LumosShape, border = lumosBorder(einkMode), colors = lumosButtonColors(einkMode)) { Text("更多设置") }
        deleteTemplateName?.let { name ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deleteTemplateName = null },
                title = { Text("删除排版模板") }, text = { Text("确定删除“$name”吗？") },
                confirmButton = { androidx.compose.material3.TextButton({
                    settingsStore.deleteTemplate(name)
                    if (settings.templateName == name) { settings = builtInTemplates().first().second; applyAndSave() }
                    deleteTemplateName = null; templateRevision++
                }) { Text("删除") } },
                dismissButton = { androidx.compose.material3.TextButton({ deleteTemplateName = null }) { Text("取消") } },
                shape = LumosShape,
            )
        }
    }

    @Composable
    private fun ColumnScope.AdvancedStyleContent() {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ValueSlider("字符间距", settings.letterSpacing, -.1f.. .3f, "%.2f".format(settings.letterSpacing)) { settings = settings.copy(customSpacing = true, letterSpacing = it); applyAndSave() }
            ValueSlider("词间距", settings.wordSpacing, 0f..3f, "%.1f".format(settings.wordSpacing)) { settings = settings.copy(customSpacing = true, wordSpacing = it); applyAndSave() }
            ValueSlider("行间距", settings.lineSpacing, 1f..3f, "%.1f".format(settings.lineSpacing)) { settings = settings.copy(customSpacing = true, lineSpacing = it); applyAndSave() }
            ValueSlider("段间距", settings.paragraphSpacing, 0f..50f, "${settings.paragraphSpacing.toInt()} dp") { settings = settings.copy(customSpacing = true, paragraphSpacing = it); applyAndSave() }
            ValueSlider("段落首行缩进", settings.indent.toFloat(), 0f..8f, "${settings.indent} 字") { settings = settings.copy(indent = it.toInt()); applyAndSave() }
            ValueSlider("上边距", settings.topMargin.toFloat(), 0f..80f, "${settings.topMargin} dp") { settings = settings.copy(customMargins = true, topMargin = it.toInt()); applyAndSave() }
            ValueSlider("下边距", settings.bottomMargin.toFloat(), 0f..80f, "${settings.bottomMargin} dp") { settings = settings.copy(customMargins = true, bottomMargin = it.toInt()); applyAndSave() }
            ValueSlider("左右边距", settings.leftMargin.toFloat(), 0f..80f, "${settings.leftMargin} dp") { settings = settings.copy(customMargins = true, leftMargin = it.toInt(), rightMargin = it.toInt()); applyAndSave() }
            Text("对齐方式", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("居中" to "center", "两端" to "justify", "左" to "left", "右" to "right").forEach { (label, value) -> androidx.compose.material3.FilterChip(settings.alignment == value, { settings = settings.copy(alignment = value); applyAndSave() }, { Text(label) }, border = lumosBorder(einkMode), colors = lumosFilterChipColors(einkMode)) } }
        }
        androidx.compose.material3.OutlinedButton({ styleAdvanced = false }, Modifier.fillMaxWidth(), shape = LumosShape, border = lumosBorder(einkMode), colors = lumosOutlinedButtonColors(einkMode)) { Text("返回基础设置") }
    }

    @Composable private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, changed: (Float) -> Unit) { Row { Text(label, modifier = Modifier.weight(1f)); Text(shown) }; androidx.compose.material3.Slider(value.coerceIn(range.start, range.endInclusive), changed, valueRange = range) }

    @Composable private fun FontDropdown(label: String, selected: String, fonts: List<LocalFont>, changed: (String) -> Unit) { var expanded by remember { mutableStateOf(false) }; Box { androidx.compose.material3.OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().height(48.dp), shape = LumosShape, border = lumosBorder(einkMode), colors = lumosOutlinedButtonColors(einkMode)) { Text("$label · ${selected.ifBlank { "系统字体" }}", Modifier.weight(1f), maxLines = 1) }; androidx.compose.material3.DropdownMenu(expanded, { expanded = false }, containerColor = if (einkMode) ComposeColor.White else androidx.compose.material3.MenuDefaults.containerColor) { (listOf("") + fonts.map { it.name }).forEach { name -> androidx.compose.material3.DropdownMenuItem({ Text(name.ifBlank { "系统字体" }, color = if (einkMode) ComposeColor.Black else ComposeColor.Unspecified) }, { changed(name); expanded = false }) } } } }

    private fun openComic() {
        Log.i("LumosComic", "open book=${book.id} title=${book.title}")
        val view = BitmapPageView(this).also { pageView = it }
        stage.addView(view, FrameLayout.LayoutParams(-1, -1))
        val controller = ComicReaderController(book, view, eink) { current, total ->
            currentProgress = if (total <= 1) 1.0 else (current - 1).toDouble() / (total - 1); composeProgress = currentProgress; composeStatus = "第 $current 页 · 共 $total 页"
        }
        comic = controller
        view.onPrevious = controller::previous
        view.onNext = controller::next
        view.onCenter = ::toggleChrome
        controller.start()
        stage.isClickable = false
    }

    private fun openPdf() {
        val view = BitmapPageView(this).also { pageView = it }
        stage.addView(view, FrameLayout.LayoutParams(-1, -1)); view.onCenter = ::toggleChrome
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            val source = RangePdfSource(this, "/api/books/${book.id}/content", book.size.toLong())
            attachPdf(PdfController(source.descriptor, null, source, view, eink, ::savePdfPosition))
        }.onFailure { downloadPdfFallback() } else downloadPdfFallback()
    }

    private fun downloadPdfFallback() {
        val file = cache.file(book.id, "pdf")
        if (file.isFile && file.length() == book.size.toLong()) return attachPdf(PdfController(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), null, null, requireNotNull(pageView), eink, ::savePdfPosition))
        val part = File(file.parentFile, "${file.name}.part")
        part.delete()
        LumosSession.download("/api/books/${book.id}/content", part.absolutePath) { result -> result.onSuccess {
            if (book.size.toLong() > 0 && part.length() != book.size.toLong()) error("下载内容不完整")
            file.delete(); check(part.renameTo(file)) { "无法保存阅读缓存" }
            cache.touch(file); attachPdf(PdfController(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), null, null, requireNotNull(pageView), eink, ::savePdfPosition))
        }.onFailure { part.delete(); showFailure(LumosSession.friendlyError(it)) } }
    }

    private fun attachPdf(controller: PdfController) { pdf = controller; pageView?.onPrevious = controller::previous; pageView?.onNext = controller::next; controller.show((book.progress * (controller.count - 1)).toInt()) }
    private fun savePdfPosition(current: Int, total: Int) { currentProgress = if (total <= 1) 1.0 else (current - 1).toDouble() / (total - 1); composeProgress = currentProgress; composeStatus = "$current / $total"; LumosSession.saveProgress(book.id, currentProgress, (current - 1).toString()) }

    private fun openDocument() {
        val view = NativeTextPageView(this).also { textView = it; applyTextSettings(it) }
        stage.addView(view, FrameLayout.LayoutParams(-1, -1)); view.onCenter = ::toggleChrome
        openTrace("text view attached")
        documentImageView = BitmapPageView(this).apply {
            visibility = View.GONE
            onPrevious = ::previous
            onNext = ::next
            onCenter = ::toggleChrome
        }.also { stage.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        view.onPageChanged = { page, total -> onTextLocation(page, total) }
        view.onStartReached = { if (chapter > 0) showChapter(chapter - 1, 1.0) }
        view.onEndReached = { if (chapter + 1 < (document?.chapters?.size ?: 0)) showChapter(chapter + 1, 0.0) }
        val file = cache.file(book.id, book.format)
        documentFile = file
        fun acceptDocument(source: String, loaded: NativeDocument) {
            if (readerDestroyed || isFinishing) return
            document = loaded
            openTrace("$source metadata ready chapters=${loaded.chapters.size}")
            seekTo(book.progress)
        }
        fun parseLocal() = LumosSession.task({ result -> result.onSuccess { loaded -> acceptDocument("local", loaded) }.onFailure { showFailure(it.message ?: "无法解析书籍") } }) {
            LocalBookParser.parse(file, book.format, cache.epubIndex(book.id, book.size.toLong())) { bytes ->
                cache.putEpubIndex(book.id, book.size.toLong(), bytes)
            }
        }
        fun downloadLocal() {
            val part = File(file.parentFile, "${file.name}.part")
            part.delete()
            LumosSession.download("/api/books/${book.id}/content", part.absolutePath) { result -> result.onSuccess {
                if (book.size.toLong() > 0 && part.length() != book.size.toLong()) error("下载内容不完整")
                file.delete(); check(part.renameTo(file)) { "无法保存阅读缓存" }
                cache.touch(file); parseLocal()
            }.onFailure { part.delete(); showFailure(LumosSession.friendlyError(it)) } }
        }
        if (file.isFile && file.length() == book.size.toLong()) { openTrace("full-file cache hit bytes=${file.length()}"); cache.touch(file); parseLocal() }
        else if (book.format.equals("epub", ignoreCase = true) && book.size.toLong() > 0) {
            openTrace("using range EPUB bytes=${book.size}")
            LumosSession.task({ result -> result.onSuccess { loaded -> acceptDocument("range", loaded) }.onFailure { error -> openTrace("range parse failed=${error.javaClass.simpleName}"); downloadLocal() } }) {
                LocalBookParser.parseRemoteEpub(
                    book.size.toLong(),
                    cache.epubIndex(book.id, book.size.toLong()),
                    { bytes -> cache.putEpubIndex(book.id, book.size.toLong(), bytes) },
                ) { start, end ->
                    cache.blockedRange(book.id, book.size.toLong(), start, end) { blockStart, blockEnd ->
                        LumosSession.rangeBlocking("/api/books/${book.id}/content", blockStart, blockEnd)
                    }
                }
            }
        } else downloadLocal()
    }

    private fun showChapter(index: Int, fraction: Double = 0.0) {
        val doc = document ?: return
        chapter = index.coerceIn(doc.chapters.indices)
        val requestedChapter = chapter
        if (doc.isLazy && !doc.isChapterLoaded(chapter)) {
            openTrace("chapter load start index=$requestedChapter")
            composeStatus = "正在加载 ${doc.chapters[chapter].title}"
            LumosSession.task({ result -> result.onSuccess { loaded ->
                if (readerDestroyed || isFinishing) return@onSuccess
                openTrace("chapter load complete index=$requestedChapter chars=${loaded.text.length}")
                if (document === doc && chapter == requestedChapter) {
                    showChapter(requestedChapter, fraction)
                }
            }.onFailure { showFailure(it.message ?: "无法加载章节") } }) { doc.chapterAt(requestedChapter) }
            return
        }
        val item = doc.chapterAt(chapter)
        val imageEntry = item.imageEntry
        if (imageEntry != null) {
            textView?.visibility = View.GONE; documentImageView?.visibility = View.VISIBLE
            currentProgress = chapter.toDouble() / doc.chapters.size.coerceAtLeast(1)
            composeProgress = currentProgress; composeStatus = "第 ${chapter + 1} 页 · 共 ${doc.chapters.size} 页"
            showFixedPage(doc, requestedChapter, imageEntry)
        } else {
            documentImageView?.visibility = View.GONE; textView?.visibility = View.VISIBLE
            textView?.setChapter(item.title, item.text, fraction)
            openTrace("first text committed index=$requestedChapter chars=${item.text.length} pages=${textView?.pageCount}")
            preloadTextAround(doc, requestedChapter)
        }
    }

    /** Preloads only adjacent text chapters after the current chapter is visible. */
    private fun preloadTextAround(doc: NativeDocument, current: Int) {
        if (!doc.isLazy) return
        listOf(current + 1, current - 1).forEach { target ->
            if (target !in doc.chapters.indices || doc.isChapterLoaded(target) || !textChapterLoading.add(target)) return@forEach
            LumosSession.task({ result ->
                textChapterLoading.remove(target)
                result.onFailure { Log.d("LumosReader", "chapter prefetch skipped index=$target", it) }
            }) { doc.chapterAt(target); Unit }
        }
    }

    private fun showFixedPage(doc: NativeDocument, target: Int, entry: String) {
        val run = fixedPageGeneration.incrementAndGet()
        fixedPageCache.remove(target)?.let { bitmap ->
            Log.i("LumosComic", "fixed memory hit target=$target")
            documentImageView?.swapPage(bitmap)
            preloadFixedAround(doc, target)
            return
        }
        Log.i("LumosComic", "fixed load target=$target generation=$run entry=$entry")
        LumosSession.task({ result -> result.onSuccess { bitmap ->
            if (fixedPageGeneration.get() != run || chapter != target) bitmap.recycle()
            else {
                documentImageView?.swapPage(bitmap)
                Log.i("LumosComic", "fixed commit target=$target display=${target + 1}/${doc.chapters.size}")
                preloadFixedAround(doc, target)
            }
        }.onFailure { showFailure(it.message ?: "无法解码图片页") } }) { decodeFixedPage(doc, target) }
    }

    private fun preloadFixedAround(doc: NativeDocument, current: Int) {
        preloadFixed(doc, current + 1)
        preloadFixed(doc, current - 1)
    }

    private fun preloadFixed(doc: NativeDocument, target: Int) {
        if (target !in doc.chapters.indices || target in fixedPageCache || !fixedPageLoading.add(target)) return
        LumosSession.task({ result ->
            fixedPageLoading.remove(target)
            result.onSuccess { bitmap ->
                fixedPageCache.put(target, bitmap)?.takeIf { !it.isRecycled }?.recycle()
                Log.d("LumosComic", "fixed prefetched target=$target")
            }.onFailure { Log.w("LumosComic", "fixed prefetch failed target=$target", it) }
        }) { decodeFixedPage(doc, target) }
    }

    private fun decodeFixedPage(doc: NativeDocument, target: Int): android.graphics.Bitmap {
        val item = doc.chapterAt(target)
        val entry = requireNotNull(item.imageEntry) { "该章节不是图片页" }
        val bytes = if (doc.isLazy) doc.resource(entry) else LocalBookParser.image(requireNotNull(documentFile), entry)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片页格式无效")
    }

    private fun onTextLocation(page: Int, total: Int) {
        val chapters = document?.chapters?.size ?: 1
        val pageFraction = if (total <= 1) 0.0 else page.toDouble() / (total - 1)
        currentProgress = (chapter + pageFraction) / chapters
        composeProgress = currentProgress
        composeStatus = "第 ${chapter + 1}/$chapters 章 · ${document?.chapters?.getOrNull(chapter)?.title.orEmpty()} · 本章 ${page + 1}/$total 页"
        LumosSession.saveProgress(book.id, currentProgress, "$chapter:$pageFraction")
    }

    private fun previous() { textView?.let { if (document?.chapters?.getOrNull(chapter)?.imageEntry != null) { if (chapter > 0) showChapter(chapter - 1, 1.0) } else if (it.page > 0) it.previous() else if (chapter > 0) showChapter(chapter - 1, 1.0) } ?: (comic?.previous() ?: pdf?.previous()) }
    private fun next() { textView?.let { if (document?.chapters?.getOrNull(chapter)?.imageEntry != null) { if (chapter + 1 < (document?.chapters?.size ?: 0)) showChapter(chapter + 1) } else it.next() } ?: (comic?.next() ?: pdf?.next()) }
    private fun seekTo(value: Double) { textView?.let { val count = document?.chapters?.size ?: 1; val exact = value * count; showChapter(exact.toInt().coerceAtMost(count - 1), exact % 1) } ?: pdf?.show((value * ((pdf?.count ?: 1) - 1)).toInt()) }

    private fun openTrace(message: String) {
        Log.i("LumosOpen", "+${SystemClock.elapsedRealtime() - openStartedAt}ms $message book=${book.id}")
    }

    private fun applyAndSave() { settingsStore.save(settings); textView?.let(::applyTextSettings) }

    private fun builtInTemplates(): List<Pair<String, ReaderSettings>> = listOf(
        "舒适" to settings.copy(templateName = "舒适", fontSize = 19, lineSpacing = 1.70f, margin = 28, customSpacing = true, customMargins = false, paragraphSpacing = 4f, indent = 2, alignment = "justify", background = "white"),
        "大字护眼" to settings.copy(templateName = "大字护眼", fontSize = 23, lineSpacing = 1.82f, margin = 34, customSpacing = true, customMargins = false, letterSpacing = .01f, paragraphSpacing = 5f, indent = 2, alignment = "justify", background = if (einkMode) "white" else "green"),
        "紧凑" to settings.copy(templateName = "紧凑", fontSize = 17, lineSpacing = 1.42f, margin = 22, customSpacing = true, customMargins = false, paragraphSpacing = 2f, indent = 0, alignment = "left", background = "white"),
    )

    private fun applyTextSettings(view: NativeTextPageView) {
        val background = when (if (einkMode) "white" else settings.background) {
            "black" -> Color.rgb(18, 20, 18)
            "green" -> Color.rgb(232, 241, 229)
            else -> Color.WHITE
        }
        val foreground = if (background == Color.rgb(18, 20, 18)) Color.WHITE else Color.BLACK
        stage.setBackgroundColor(background)
        window.statusBarColor = background; window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (foreground == Color.BLACK) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        val fonts = fontStorage.list()
        fun typeface(name: String): Typeface? {
            if (name.isBlank()) return null
            loadedTypefaces[name]?.let { return it }
            val loaded = fonts.firstOrNull { it.name == name }?.let { font -> runCatching { fontStorage.open(font).use { it.typeface() } }.getOrNull() }
            if (loaded != null) loadedTypefaces[name] = loaded
            return loaded
        }
        view.applyStyle(NativeTextPageView.Style(
            textSizeSp = settings.fontSize.toFloat(), lineSpacing = settings.lineSpacing,
            leftPaddingDp = if (settings.customMargins) settings.leftMargin else settings.margin,
            rightPaddingDp = if (settings.customMargins) settings.leftMargin else settings.margin,
            topPaddingDp = if (settings.customMargins) settings.topMargin else 24,
            bottomPaddingDp = if (settings.customMargins) settings.bottomMargin else 28,
            alignment = settings.alignment,
            letterSpacing = if (settings.customSpacing) settings.letterSpacing else 0f,
            wordSpacing = if (settings.customSpacing) settings.wordSpacing else 1f,
            paragraphSpacingDp = if (settings.customSpacing) settings.paragraphSpacing else 0f,
            indentCharacters = settings.indent,
            textTypeface = typeface(settings.fontName), titleTypeface = typeface(settings.titleFontName),
            backgroundColor = background, textColor = foreground,
        ))
    }

    private fun showFailure(message: String) { stage.removeAllViews(); stage.addView(TextView(this).apply { text = "$message\n\n返回书库"; gravity = Gravity.CENTER; textSize = 16f; setTextColor(Color.BLACK); setOnClickListener { finish() } }, FrameLayout.LayoutParams(-1, -1)) }
    private fun saveCurrentProgress() = LumosSession.saveProgress(book.id, currentProgress)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { previous(); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { next(); true }
        else -> super.onKeyDown(keyCode, event)
    }
    override fun onDestroy() {
        readerDestroyed = true
        fixedPageGeneration.incrementAndGet()
        fixedPageCache.values.forEach { it.takeIf { bitmap -> !bitmap.isRecycled }?.recycle() }
        fixedPageCache.clear(); fixedPageLoading.clear()
        comic?.close(); pdf?.close(); saveCurrentProgress(); cache.trim()
        LumosSession.addReadingTime(book.id, ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceIn(1, 300))
        super.onDestroy()
    }

    private fun isEInkDevice(): Boolean = getSharedPreferences("lumos_connection", MODE_PRIVATE).getBoolean("force_eink", false)
}

private class PdfController(private val descriptor: ParcelFileDescriptor, private val disposable: File?, private val rangeSource: AutoCloseable?, private val view: BitmapPageView, private val eink: xyz.alumos.lumosreader.reader.nativeview.EInkController, private val position: (Int, Int) -> Unit) : AutoCloseable {
    private val renderer = PdfRenderer(descriptor); val count get() = renderer.pageCount; private var requested = 0; private val generation = AtomicInteger()
    fun previous() = show(requested - 1); fun next() = show(requested + 1)
    fun show(target: Int) { if (target !in 0 until count) return; requested = target; val run = generation.incrementAndGet(); val width = view.width.coerceAtLeast(1200); LumosSession.task({ result -> result.onSuccess { bitmap -> if (generation.get() != run) bitmap.recycle() else { view.swapPage(bitmap); eink.onPageTurn(view); position(target + 1, count) } } }) { synchronized(renderer) { renderer.openPage(target).use { page -> val height = (width * page.height.toFloat() / page.width).toInt(); android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565).also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } } } } }
    override fun close() { generation.incrementAndGet(); synchronized(renderer) { renderer.close() }; descriptor.close(); rangeSource?.close(); disposable?.delete() }
}

@androidx.annotation.RequiresApi(26)
private class RangePdfSource(activity: Activity, private val path: String, private val size: Long) : ProxyFileDescriptorCallback(), AutoCloseable {
    private val thread = HandlerThread("lumos-pdf-range").apply { start() }; private val chunks = object : LinkedHashMap<Long, ByteArray>(5, .75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 4 }
    val descriptor: ParcelFileDescriptor = activity.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, this, Handler(thread.looper))
    override fun onGetSize() = size
    @Synchronized override fun onRead(offset: Long, requested: Int, data: ByteArray): Int { if (offset >= size) return 0; val count = minOf(requested.toLong(), size - offset).toInt(); var copied = 0; try { while (copied < count) { val absolute = offset + copied; val start = absolute / CHUNK * CHUNK; val chunk = chunks[start] ?: LumosSession.rangeBlocking(path, start, minOf(size - 1, start + CHUNK - 1)).also { chunks[start] = it }; val inside = (absolute - start).toInt(); val amount = minOf(count - copied, chunk.size - inside); if (amount <= 0) break; chunk.copyInto(data, copied, inside, inside + amount); copied += amount }; return copied } catch (_: Exception) { throw ErrnoException("pdf range read", OsConstants.EIO) } }
    override fun onRelease() = chunks.clear(); override fun close() { thread.quitSafely(); chunks.clear() }
    companion object { private const val CHUNK = 64L * 1024L }
}
