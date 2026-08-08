package xyz.alumos.lumosreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import xyz.alumos.lumosreader.core.LumosSession
import uniffi.lumos_core.Book
import uniffi.lumos_core.Bookshelf
import uniffi.lumos_core.ReadingStats
import uniffi.lumos_core.ServerFont
import uniffi.lumos_core.ServerInfo
import uniffi.lumos_core.ShelvesState
import java.io.File

private enum class AppPage { CONNECTION, LOGIN, LIBRARY, SETTINGS, STATS, FONTS, CACHE, SHELVES }

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("lumos_connection", MODE_PRIVATE) }
    private val secureCookie by lazy { SecureCookieStore(this) }
    private var page by mutableStateOf(AppPage.CONNECTION)
    private var loading by mutableStateOf(false)
    private var error by mutableStateOf("")
    private var banner by mutableStateOf<String?>(null)
    private var scanning by mutableStateOf(false)
    private var books by mutableStateOf<List<Book>>(emptyList())
    private var server by mutableStateOf<ServerInfo?>(null)
    private var stats by mutableStateOf<ReadingStats?>(null)
    private var fonts by mutableStateOf<List<ServerFont>>(emptyList())
    private var shelves by mutableStateOf<ShelvesState?>(null)
    private var einkEnabled by mutableStateOf(false)
    private var autoScanStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        einkEnabled = isEInk()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = when (page) {
                AppPage.LIBRARY, AppPage.CONNECTION, AppPage.LOGIN -> finish()
                AppPage.SETTINGS -> showLibrary()
                else -> showSettings()
            }
        })
        setContent { LumosTheme(einkEnabled) { LumosApp() } }
        val address = prefs.getString("address", "").orEmpty()
        if (address.isBlank()) page = AppPage.CONNECTION else connect(address, secureCookie.read(), true)
    }

    override fun onResume() {
        super.onResume()
        if (page == AppPage.LIBRARY && LumosSession.client != null && books.isNotEmpty()) loadBooks(false)
    }

    @Composable
    private fun LumosApp() {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(banner) {
            val text = banner ?: return@LaunchedEffect
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
            if (banner == text) banner = null
        }
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(page, transitionSpec = {
                if (einkEnabled) EnterTransition.None togetherWith ExitTransition.None
                else (slideInHorizontally { it / 8 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 8 } + fadeOut())
            }, label = "page") { currentPage -> when (currentPage) {
                AppPage.CONNECTION -> ConnectionScreen()
                AppPage.LOGIN -> LoginScreen()
                AppPage.LIBRARY -> LibraryScreen()
                AppPage.SETTINGS -> SettingsScreen()
                AppPage.STATS -> StatsScreen()
                AppPage.FONTS -> FontsScreen()
                AppPage.CACHE -> CacheScreen()
                AppPage.SHELVES -> ShelvesScreen()
            } }
            if (loading) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = .93f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("正在读取数据…")
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)) { data ->
                var upward by remember(data) { mutableFloatStateOf(0f) }
                Snackbar(snackbarData = data, containerColor = Color.Black, contentColor = Color.White, shape = LumosShape,
                    modifier = Modifier.pointerInput(data) { detectVerticalDragGestures(onVerticalDrag = { _, amount -> upward += amount }, onDragEnd = { if (upward < -32f) data.dismiss(); upward = 0f }) })
            }
        }
    }

    @Composable
    private fun ConnectionScreen() = CenteredPane {
        Text("微光阅", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("连接你的书库", style = MaterialTheme.typography.titleLarge)
        var address by rememberSaveable { mutableStateOf(prefs.getString("address", "").orEmpty()) }
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("服务器地址") }, placeholder = { Text("http://192.168.1.100:7767") }, singleLine = true)
        ErrorText()
        Button({ connect(address.trim(), null, false) }, Modifier.fillMaxWidth().height(50.dp), enabled = address.isNotBlank(), border = lumosBorder(einkEnabled), colors = lumosButtonColors(einkEnabled)) { Text("连接服务器") }
        Text("公网地址建议使用 HTTPS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }

    @Composable
    private fun LoginScreen() = CenteredPane {
        Text(server?.name ?: "微光阅", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("此服务器需要密码")
        var password by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("服务器密码") }, singleLine = true)
        ErrorText()
        Button({ login(password) }, Modifier.fillMaxWidth().height(50.dp), border = lumosBorder(einkEnabled), colors = lumosButtonColors(einkEnabled)) { Text("登录") }
        OutlinedButton(::disconnect, Modifier.fillMaxWidth().height(48.dp), border = lumosBorder(einkEnabled), colors = lumosOutlinedButtonColors(einkEnabled)) { Text("更换服务器") }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun LibraryScreen() {
        var shelf by rememberSaveable { mutableStateOf("全部书架") }
        var category by rememberSaveable { mutableStateOf("全部分类") }
        var query by rememberSaveable { mutableStateOf("") }
        val shelfNames = remember(books) { listOf("全部书架") + books.map(Book::shelf).filter(String::isNotBlank).distinct().sorted() }
        if (shelf !in shelfNames) shelf = "全部书架"
        val categoryNames = remember(books, shelf) { listOf("全部分类") + books.asSequence().filter { shelf == "全部书架" || it.shelf == shelf }.map(Book::category).filter(String::isNotBlank).distinct().sorted().toList() }
        if (category !in categoryNames) category = "全部分类"
        val filtered = remember(books, shelf, category, query) {
            books.filter { book ->
                (shelf == "全部书架" || book.shelf == shelf) && (category == "全部分类" || book.category == category) &&
                    (query.isBlank() || "${book.title} ${book.author} ${book.series}".contains(query.trim(), true))
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 720.dp
            val controls: @Composable ColumnScope.() -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("微光阅", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (einkEnabled) OutlinedButton({ scanLibrary() }, enabled = !scanning, contentPadding = PaddingValues(horizontal = 14.dp), border = lumosBorder(true), colors = lumosOutlinedButtonColors(true)) { Text(if (scanning) "扫描中" else "扫描") }
                    else FilledTonalButton({ scanLibrary() }, enabled = !scanning, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(if (scanning) "扫描中" else "扫描") }
                    Spacer(Modifier.width(8.dp)); OutlinedButton(::showSettings, contentPadding = PaddingValues(horizontal = 14.dp), border = lumosBorder(einkEnabled), colors = lumosOutlinedButtonColors(einkEnabled)) { Text("设置") }
                }
                Spacer(Modifier.height(14.dp))
                AdaptiveDropdown("书架", shelf, shelfNames) { shelf = it; category = "全部分类" }
                Spacer(Modifier.height(10.dp)); AdaptiveDropdown("分类", category, categoryNames) { category = it }
                Spacer(Modifier.height(10.dp)); OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().height(48.dp), label = { Text("搜索") }, singleLine = true, shape = LumosShape)
                Spacer(Modifier.height(10.dp)); Text("${presentWorks(filtered).size} 部作品", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (wide) Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(Modifier.widthIn(min = 230.dp, max = 290.dp).fillMaxHeight(), shape = LumosShape, tonalElevation = if (einkEnabled) 0.dp else 2.dp, border = lumosBorder(einkEnabled)) {
                    Column(Modifier.padding(18.dp), content = controls)
                }
                LibraryPager(filtered, Modifier.weight(1f).fillMaxHeight(), wide = true)
            } else Column(Modifier.fillMaxSize().padding(16.dp)) {
                controls(); Spacer(Modifier.height(14.dp)); LibraryPager(filtered, Modifier.weight(1f), wide = false)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun LibraryPager(source: List<Book>, modifier: Modifier, wide: Boolean) {
        val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
        val columns = if (wide) if (tablet) 5 else 4 else 2
        val rows = if (wide) 1 else 2
        val capacity = columns * rows
        val activeFilter = source !== books && source.size != books.size
        val pages = remember(source, capacity, activeFilter) {
            if (activeFilter) presentWorks(source).chunked(capacity).mapIndexed { index, values -> ComposeLibraryPage("筛选结果", "第 ${index + 1} 页", values) }
            else buildList {
                val recent = source.filter { it.progressTime.isNotBlank() }.sortedByDescending(Book::progressTime).take(capacity)
                if (recent.isNotEmpty()) add(ComposeLibraryPage("最近阅读", "继续上次阅读", presentWorks(recent)))
                listOf("图书" to source.filter { it.shelfKind != "comic" }, "漫画" to source.filter { it.shelfKind == "comic" }).forEach { (title, values) ->
                    val works = presentWorks(values); works.chunked(capacity).forEachIndexed { i, chunk -> add(ComposeLibraryPage(title, "第 ${i + 1}/${(works.size + capacity - 1) / capacity} 页", chunk)) }
                }
            }
        }
        if (pages.isEmpty()) Box(modifier, contentAlignment = Alignment.Center) { Text("没有符合条件的书籍", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else {
            val state = rememberPagerState(pageCount = { pages.size })
            HorizontalPager(state, modifier, pageSpacing = 12.dp, key = { "${pages[it].title}-$it" }) { index ->
                val page = pages[index]
                Column(Modifier.fillMaxSize()) {
                    Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(page.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val cardHeight = (maxHeight - (10.dp * (rows - 1))) / rows
                        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize(), userScrollEnabled = false, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(page.items, key = { libraryKey(it) }) { item -> BookCard(item, Modifier.height(cardHeight)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BookCard(item: LibraryItem, modifier: Modifier) {
        val book = when (item) { is LibraryItem.BookEntry -> item.book; is LibraryItem.SeriesEntry -> item.books.first(); else -> return }
        val title = if (item is LibraryItem.SeriesEntry) item.name else book.title
        val subtitle = if (item is LibraryItem.SeriesEntry) "${item.books.size} 卷" else "${(book.progress * 100).toInt()}% · ${book.author.ifBlank { book.format.uppercase() }}"
        Card(modifier.fillMaxWidth().clickable {
            if (item is LibraryItem.SeriesEntry) { LumosSession.selectedCollection = item.books.sortedBy(Book::fileName); startActivity(Intent(this, SeriesActivity::class.java)) }
            else openBook(book)
        }, shape = LumosShape, border = lumosBorder(einkEnabled)) {
            Column(Modifier.fillMaxSize().padding(7.dp)) {
                Cover(book, Modifier.fillMaxWidth().weight(1f).clip(LumosShape))
                Spacer(Modifier.height(7.dp)); Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun SettingsScreen() = ResponsivePage("设置", onBack = ::showLibrary, bottom = {
        BlackBackButton("返回书库", ::showLibrary)
        Spacer(Modifier.height(8.dp)); OutlinedButton(::disconnect, Modifier.fillMaxWidth().height(48.dp), shape = LumosShape, border = lumosBorder(einkEnabled), colors = lumosOutlinedButtonColors(einkEnabled)) { Text("更换服务器") }
    }) { wide ->
        val entries = listOf(
            Triple("阅读统计", "阅读时长、趋势与常读书籍", ::loadStats), Triple("书架管理", "同步目录、分类和类型", ::loadShelves),
            Triple("字体库", "下载、上传和删除本地字体", ::loadFonts), Triple("缓存管理", "查看占用并设置容量上限", { page = AppPage.CACHE }),
        )
        if (wide) entries.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { (title, body, action) -> Box(Modifier.weight(1f)) { SettingsCard(title, body, action) } }; if (row.size == 1) Spacer(Modifier.weight(1f)) } }
        else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { entries.forEach { (title, body, action) -> SettingsCard(title, body, action) } }
        Spacer(Modifier.height(14.dp)); Card(border = lumosBorder(einkEnabled)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("E‑INK 模式", fontWeight = FontWeight.Bold); Text(if (einkEnabled) "纯黑白与统一描边" else "关闭后使用系统莫奈动态取色", fontSize = 12.sp) }; Switch(einkEnabled, { enabled -> einkEnabled = enabled; prefs.edit().putBoolean("force_eink", enabled).apply() }) } }
    }

    @Composable private fun SettingsCard(title: String, body: String, action: () -> Unit) = Card(Modifier.fillMaxWidth().clickable(onClick = action), border = lumosBorder(einkEnabled)) {
        Column(Modifier.padding(18.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }

    @Composable
    private fun StatsScreen() = ResponsivePage("阅读统计", onBack = ::showSettings, bottom = { BlackBackButton("返回设置", ::showSettings) }) { wide ->
        val value = stats
        if (value == null) Text("暂无统计数据") else if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f)) { StatSummary(value); Spacer(Modifier.height(16.dp)); ReadingChart(value, Modifier.fillMaxWidth().height(210.dp)) }
            Column(Modifier.weight(1f)) { Text("读得最多", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); TopBooks(value) }
        } else Column { StatSummary(value); Spacer(Modifier.height(16.dp)); ReadingChart(value, Modifier.fillMaxWidth().height(220.dp)); Spacer(Modifier.height(18.dp)); Text("读得最多", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); TopBooks(value) }
    }

    @Composable private fun StatSummary(value: ReadingStats) = Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("今日阅读" to value.todaySeconds.toLong(), "累计阅读" to value.totalSeconds.toLong()).forEach { (label, seconds) -> Card(Modifier.weight(1f), border = lumosBorder(einkEnabled)) { Column(Modifier.padding(16.dp)) { Text(label, fontSize = 12.sp); Text(formatDuration(seconds), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } } }
    }
    @Composable private fun ReadingChart(value: ReadingStats, modifier: Modifier) = Card(modifier, border = lumosBorder(einkEnabled)) {
        val days = remember(value.days) { fillLastThirtyDays(value.days) }
        val max = days.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        val total = days.sumOf { it.second }
        val activeDays = days.count { it.second > 0 }
        val dailyAverage = if (activeDays == 0) 0 else total / activeDays
        val primary = MaterialTheme.colorScheme.primary
        val grid = MaterialTheme.colorScheme.outlineVariant
        Column(Modifier.padding(14.dp)) { Text("近 30 日", fontWeight = FontWeight.Bold); Text("每日阅读分钟数", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("累计 ${formatDuration(total)}", fontSize = 11.sp)
                Text("活跃 $activeDays 天", fontSize = 11.sp)
                Text("日均 ${dailyAverage / 60} 分钟", fontSize = 11.sp)
            }
            Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp, bottom = 18.dp)) {
                val step = size.width / (days.size - 1).coerceAtLeast(1); val bottom = size.height
                for (level in 0..2) { val y = bottom * level / 2f; drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f) }
                val points = days.mapIndexed { i, item -> androidx.compose.ui.geometry.Offset(i * step, bottom - bottom * item.second / max) }
                points.zipWithNext().forEach { (a, b) -> drawLine(primary, a, b, 4f) }
                points.forEach { drawCircle(primary, 4f, it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(days.first().first.substring(5), fontSize = 10.sp); Text("峰值 ${max / 60} 分钟", fontSize = 10.sp); Text(days.last().first.substring(5), fontSize = 10.sp) }
        }
    }
    @Composable private fun TopBooks(value: ReadingStats) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { value.books.take(6).forEach { item -> val book = books.firstOrNull { it.id == item.bookId }; Card(border = lumosBorder(einkEnabled)) { Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) { if (book != null) Cover(book, Modifier.size(42.dp, 58.dp).clip(LumosShape)); Spacer(Modifier.width(12.dp)); Column { Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(formatDuration(item.seconds.toLong()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } } }

    @Composable
    private fun FontsScreen() = ResponsivePage("字体库", onBack = ::showSettings, bottom = { BlackBackButton("返回设置", ::showSettings) }) { _ ->
        val storage = remember { FontStorage(this@MainActivity) }
        var locals by remember(fonts) { mutableStateOf(storage.list()) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::uploadFont) }
        OutlinedButton({ picker.launch(arrayOf("font/ttf", "font/otf", "font/woff", "font/woff2", "application/octet-stream")) }, border = lumosBorder(einkEnabled), colors = lumosOutlinedButtonColors(einkEnabled)) { Text("上传字体") }
        Text("字体保存在 Downloads/LumosReader/Fonts", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        val rows = fonts.map { it.name to it } + locals.filter { local -> fonts.none { it.name == local.name } }.map { it.name to null }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { rows.forEach { (name, remote) ->
            val local = locals.firstOrNull { it.name == name }; var progress by remember(name) { mutableIntStateOf(-1) }
            Card(border = lumosBorder(einkEnabled)) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Aa", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Bold, maxLines = 1); Text(if (local != null) "已下载 · ${formatBytes(local.size)}" else "服务器字体 · ${formatBytes(remote?.size?.toLong() ?: 0)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (progress >= 0) CircularProgressIndicator({ progress / 100f }, Modifier.size(38.dp))
                else if (local != null) TextButton({ storage.delete(local.name); locals = storage.list() }) { Text("删除") }
                else TextButton({ if (remote != null) { progress = 0; LumosSession.bytesWithProgress(remote.url, remote.size.toLong(), { progress = it }) { result -> result.onSuccess { storage.save(name, it); locals = storage.list(); progress = -1 }.onFailure { progress = -1; banner = LumosSession.friendlyError(it) } } } }) { Text("下载") }
            } }
        } }
    }

    @Composable
    private fun CacheScreen() = ResponsivePage("缓存管理", onBack = ::showSettings, bottom = { BlackBackButton("返回设置", ::showSettings) }) { _ ->
        val cache = remember { ReaderCache(this@MainActivity) }; var size by remember { mutableLongStateOf(cache.sizeBytes()) }; var limit by remember { mutableLongStateOf(cache.limitBytes) }
        Card(border = lumosBorder(einkEnabled)) { Column(Modifier.fillMaxWidth().padding(18.dp)) { Text("当前缓存", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatBytes(size), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("上限 ${formatBytes(limit)}") } }
        Spacer(Modifier.height(14.dp)); Text("缓存上限", fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf(200L, 500L, 1024L, 2048L)) { mb -> FilterChip(limit == mb * ReaderCache.MB, { cache.limitBytes = mb * ReaderCache.MB; limit = cache.limitBytes; size = cache.sizeBytes() }, { Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB") }, border = lumosBorder(einkEnabled), colors = lumosFilterChipColors(einkEnabled)) } }
        Spacer(Modifier.height(14.dp)); Button({ cache.clear(); size = cache.sizeBytes(); banner = "缓存已清理" }, border = lumosBorder(einkEnabled), colors = if (einkEnabled) ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black) else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("清理全部缓存") }
    }

    @Composable
    private fun ShelvesScreen() = ResponsivePage("书架管理", onBack = ::showSettings, bottom = { BlackBackButton("返回设置", ::showSettings) }) { wide ->
        val state = shelves
        if (state == null) Text("暂无书架数据") else {
            var entries by remember(state) { mutableStateOf(state.shelves) }
            val editor: @Composable () -> Unit = {
                Text("书架配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                entries.forEachIndexed { index, shelf -> ShelfEditor(shelf, { next -> entries = entries.toMutableList().also { it[index] = next } }, { entries = entries.toMutableList().also { it.removeAt(index) } }) }
                OutlinedButton({ entries = entries + Bookshelf("", "", "auto") }, border = lumosBorder(einkEnabled), colors = lumosOutlinedButtonColors(einkEnabled)) { Text("添加书架") }
                Button({ saveShelves(entries) }, border = lumosBorder(einkEnabled), colors = lumosButtonColors(einkEnabled)) { Text("保存并扫描") }
            }
            val tree: @Composable () -> Unit = { DirectoryTree(state.directories) }
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { editor() }; Box(Modifier.weight(1f)) { tree() } }
            else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { editor(); tree() }
        }
    }

    @Composable private fun ShelfEditor(value: Bookshelf, changed: (Bookshelf) -> Unit, remove: () -> Unit) = Card(border = lumosBorder(einkEnabled)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value.name, { changed(Bookshelf(it, value.path, value.kind)) }, Modifier.fillMaxWidth(), label = { Text("书架名称") }, singleLine = true)
            OutlinedTextField(value.path, { changed(Bookshelf(value.name, it, value.kind)) }, Modifier.fillMaxWidth(), label = { Text("相对目录") }, singleLine = true)
            Row { listOf("自动" to "auto", "图书" to "book", "漫画" to "comic").forEach { (label, kind) -> FilterChip(value.kind == kind, { changed(Bookshelf(value.name, value.path, kind)) }, { Text(label) }, Modifier.padding(end = 6.dp), border = lumosBorder(einkEnabled), colors = lumosFilterChipColors(einkEnabled)) }; Spacer(Modifier.weight(1f)); TextButton(remove) { Text("移除") } }
        }
    }

    @Composable private fun DirectoryTree(paths: List<String>) = Card(border = lumosBorder(einkEnabled)) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("已发现目录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
        if (paths.isEmpty()) Text("暂无可用目录") else paths.take(60).sorted().forEach { path -> val depth = path.replace('\\', '/').trim('/').count { it == '/' }.coerceAtMost(5); Row(Modifier.padding(start = (depth * 14).dp, top = 5.dp, bottom = 5.dp)) { Text(if (depth == 0) "▾" else "└", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(path.substringAfterLast('/').substringAfterLast('\\'), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    } }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun ResponsivePage(title: String, onBack: () -> Unit, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.(Boolean) -> Unit) {
        Scaffold { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).safeDrawingPadding()) {
                val wide = maxWidth >= 720.dp
                if (wide) Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Surface(Modifier.widthIn(min = 220.dp, max = 270.dp).fillMaxHeight(), shape = LumosShape, border = lumosBorder(einkEnabled), tonalElevation = if (einkEnabled) 0.dp else 2.dp) {
                        Column(Modifier.fillMaxSize().padding(18.dp)) {
                            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Column(Modifier.fillMaxWidth(), content = bottom)
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
                        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content(true) } }
                    }
                } else Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) { item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content(false) } } }
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), content = bottom)
                }
            }
        }
    }

    @Composable private fun BlackBackButton(label: String, action: () -> Unit) = Button(action, Modifier.fillMaxWidth().height(48.dp), shape = LumosShape, border = lumosBorder(einkEnabled), colors = if (einkEnabled) ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black) else ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)) { Text(label) }
    @Composable private fun ErrorText() { if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
    @Composable private fun CenteredPane(content: @Composable ColumnScope.() -> Unit) = Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) { Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }

    @Composable private fun AdaptiveDropdown(label: String, value: String, options: List<String>, changed: (String) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Box { OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().height(48.dp), shape = LumosShape, contentPadding = PaddingValues(horizontal = 14.dp), border = lumosBorder(einkEnabled), colors = lumosOutlinedButtonColors(einkEnabled)) { Text("$label · $value", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) }; DropdownMenu(expanded, { expanded = false }, containerColor = if (einkEnabled) Color.White else MenuDefaults.containerColor) { options.forEach { DropdownMenuItem({ Text(it, color = if (einkEnabled) Color.Black else Color.Unspecified) }, { changed(it); expanded = false }) } } }
    }

    @Composable private fun Cover(book: Book, modifier: Modifier) {
        var bitmap by remember(book.id, book.coverUrl) { mutableStateOf<Bitmap?>(CoverMemory.get(book.id)) }
        LaunchedEffect(book.id, book.coverUrl) {
            if (bitmap == null) {
                val path = book.coverUrl.ifBlank { "/api/books/${book.id}/cover" }
                LumosSession.bytes(path) { result ->
                    result.onSuccess { bytes ->
                        LumosSession.task({ decoded ->
                            decoded.onSuccess { CoverMemory.put(book.id, it); bitmap = it }
                                .onFailure { Log.w("LumosCover", "decode failed: ${book.id}", it) }
                        }) { decodeCover(bytes) }
                    }.onFailure { Log.w("LumosCover", "request failed: ${book.id}, $path", it) }
                }
            }
        }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { if (bitmap == null) Text(book.title.take(1), style = MaterialTheme.typography.headlineLarge) else androidx.compose.foundation.Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }

    private fun connect(address: String, cookie: String?, automatic: Boolean) {
        if (address.isBlank()) { error = "请输入服务器地址"; return }
        loading = true; error = ""
        LumosSession.connect(address, cookie) { result -> loading = false; result.onSuccess { (info, session) -> server = info; prefs.edit().putString("address", LumosSession.client?.baseUrl()).apply(); if (session.authenticated || !info.authRequired) { persistCookie(); loadBooks() } else page = AppPage.LOGIN }.onFailure { page = AppPage.CONNECTION; error = (if (automatic) "上次的服务器暂时无法连接：" else "") + LumosSession.friendlyError(it) } }
    }
    private fun login(password: String) { loading = true; LumosSession.login(password) { result -> loading = false; result.onSuccess { persistCookie(); loadBooks() }.onFailure { error = LumosSession.friendlyError(it) } } }
    private fun loadBooks(scanAfter: Boolean = true) { loading = true; LumosSession.books { result -> loading = false; result.onSuccess { books = it; page = AppPage.LIBRARY; if (scanAfter && !autoScanStarted) { autoScanStarted = true; scanLibrary() } }.onFailure { error = LumosSession.friendlyError(it); if (books.isNotEmpty()) page = AppPage.LIBRARY else page = AppPage.CONNECTION } } }
    private fun showLibrary() { page = AppPage.LIBRARY; error = "" }
    private fun showSettings() { page = AppPage.SETTINGS; error = "" }
    private fun scanLibrary() { if (scanning) return; scanning = true; val known = books.map(Book::id).toSet(); LumosSession.scan { result -> result.onSuccess { LumosSession.books { refreshed -> scanning = false; refreshed.onSuccess { updated -> val added = updated.count { it.id !in known }; val removed = books.count { old -> updated.none { it.id == old.id } }; books = updated; banner = if (added + removed == 0) "扫描完成，书库已经是最新" else "扫描完成：新增 $added 本，移除 $removed 本" }.onFailure { banner = LumosSession.friendlyError(it) } } }.onFailure { scanning = false; banner = LumosSession.friendlyError(it) } } }
    private fun loadStats() { loading = true; LumosSession.stats { result -> loading = false; result.onSuccess { stats = it; page = AppPage.STATS }.onFailure { banner = LumosSession.friendlyError(it) } } }
    private fun loadFonts() { loading = true; LumosSession.fonts { result -> loading = false; result.onSuccess { fonts = it; page = AppPage.FONTS }.onFailure { banner = LumosSession.friendlyError(it) } } }
    private fun loadShelves() { loading = true; LumosSession.shelves { result -> loading = false; result.onSuccess { shelves = it; page = AppPage.SHELVES }.onFailure { banner = LumosSession.friendlyError(it) } } }
    private fun saveShelves(values: List<Bookshelf>) { loading = true; LumosSession.saveShelves(values.filter { it.name.isNotBlank() && it.path.isNotBlank() }) { result -> loading = false; result.onSuccess { banner = "书架已保存，正在重新扫描"; loadBooks() }.onFailure { banner = LumosSession.friendlyError(it) } } }
    private fun openBook(book: Book) { LumosSession.selectedBook = book; LumosSession.selectedCollection = listOf(book); startActivity(Intent(this, ReaderActivity::class.java)) }
    private fun disconnect() { LumosSession.logout(); prefs.edit().clear().apply(); secureCookie.clear(); LumosSession.clear(); books = emptyList(); page = AppPage.CONNECTION }
    private fun persistCookie() = LumosSession.cookie()?.let(secureCookie::write)
    private fun uploadFont(uri: Uri) { val name = queryDisplayName(uri); if (name.substringAfterLast('.', "").lowercase() !in FontStorage.EXTENSIONS) { banner = "仅支持 TTF、OTF、WOFF 和 WOFF2 字体"; return }; val temporary = File.createTempFile("lumos-font-", ".${name.substringAfterLast('.')}", cacheDir); runCatching { contentResolver.openInputStream(uri)!!.use { input -> temporary.outputStream().use(input::copyTo) } }.onFailure { banner = "无法读取字体" }.onSuccess { loading = true; LumosSession.uploadFont(temporary.absolutePath, name) { result -> temporary.delete(); loading = false; result.onSuccess { banner = "字体已上传"; loadFonts() }.onFailure { banner = LumosSession.friendlyError(it) } } } }
    private fun queryDisplayName(uri: Uri): String { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0).substringAfterLast('/').substringAfterLast('\\') }; return "font.ttf" }
    private fun isEInk(): Boolean = prefs.getBoolean("force_eink", false)
}

private data class ComposeLibraryPage(val title: String, val subtitle: String, val items: List<LibraryItem>)
private sealed interface LibraryItem {
    data class Header(val title: String) : LibraryItem
    data class BookEntry(val book: Book) : LibraryItem
    data class SeriesEntry(val name: String, val books: List<Book>) : LibraryItem
}
private fun libraryKey(item: LibraryItem) = when (item) { is LibraryItem.BookEntry -> "b:${item.book.id}"; is LibraryItem.SeriesEntry -> "s:${item.name}:${item.books.firstOrNull()?.id}"; is LibraryItem.Header -> "h:${item.title}" }
private fun presentWorks(source: List<Book>): List<LibraryItem> { val series = linkedMapOf<String, MutableList<Book>>(); val result = mutableListOf<LibraryItem>(); source.forEach { if (it.shelfKind == "comic" && it.series.isNotBlank()) series.getOrPut("${it.shelf}/${it.category}/${it.series}") { mutableListOf() }.add(it) else result += LibraryItem.BookEntry(it) }; result += series.values.map { LibraryItem.SeriesEntry(it.first().series, it) }; return result }
private fun formatBytes(bytes: Long): String = when { bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)); bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024)); else -> "%.0f KB".format(bytes / 1024.0) }
private fun formatDuration(seconds: Long): String = if (seconds < 3600) "${seconds / 60} 分钟" else "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分钟"
private fun fillLastThirtyDays(source: List<uniffi.lumos_core.ReadingDay>): List<Pair<String, Long>> {
    val values = source.associate { it.date to it.seconds.toLong() }
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.DAY_OF_YEAR, -29)
    return List(30) {
        val date = formatter.format(calendar.time)
        calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        date to (values[date] ?: 0L)
    }
}
private fun decodeCover(bytes: ByteArray): Bitmap { val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds); var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 480) sample *= 2; return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })) }
private object CoverMemory { private val values = object : LinkedHashMap<String, Bitmap>(32, .75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 32 }; @Synchronized fun get(id: String) = values[id]; @Synchronized fun put(id: String, bitmap: Bitmap) { values[id] = bitmap } }
