package xyz.alumos.lumosreader

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import xyz.alumos.lumosreader.core.LumosSession
import xyz.alumos.lumosreader.reader.epub.WebBookActivity
import uniffi.lumos_core.Book
import uniffi.lumos_core.ServerInfo
import uniffi.lumos_core.Bookshelf
import kotlin.math.max
import java.io.File

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("lumos_connection", MODE_PRIVATE) }
    private val secureCookie by lazy { SecureCookieStore(this) }
    private lateinit var root: LinearLayout
    private var server: ServerInfo? = null
    private var books: List<Book> = emptyList()
    private var fontsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            setBackgroundColor(if (isEInk()) Color.WHITE else getColor(xyz.alumos.lumosreader.design.R.color.lumos_canvas))
        }
        setContentView(root)
        val address = prefs.getString("address", "").orEmpty()
        if (address.isBlank()) showConnection() else connect(address, secureCookie.read(), true)
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized && LumosSession.client != null && root.tag == "library") loadBooks()
    }

    @Deprecated("Uses the platform document picker result callback for API 23 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FONT || resultCode != RESULT_OK) return
        data?.data?.let(::uploadFont)
    }

    private fun showConnection(message: String = "") {
        reset("connection")
        val mark = TextView(this).apply { text = "L"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundResource(xyz.alumos.lumosreader.design.R.drawable.lumos_button) }
        root.addView(mark, LinearLayout.LayoutParams(dp(58), dp(58)).apply { bottomMargin = dp(22) })
        root.addView(title("连接你的微光阅"))
        root.addView(body("只需填写服务端根地址。书籍保留在 NAS，进度会在设备之间同步。"), wrap(bottom = 24))
        val address = input("http://nas.alumos.xyz:7767").apply {
            setText(prefs.getString("address", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(address, match(height = 52, bottom = 12))
        if (message.isNotBlank()) root.addView(error(message), wrap(bottom = 10))
        root.addView(primary("连接服务器") { connect(address.text.toString(), null, false) }, match(height = 48))
        root.addView(body("HTTP 仅建议用于可信局域网；公网地址请优先使用 HTTPS。"), wrap(top = 16))
    }

    private fun connect(address: String, cookie: String?, automatic: Boolean) {
        showLoading("正在连接服务器…")
        LumosSession.connect(address, cookie) { result ->
            result.onSuccess { (info, session) ->
                server = info
                prefs.edit().putString("address", LumosSession.client?.baseUrl()).apply()
                when {
                    session.authenticated -> { persistCookie(); loadBooks() }
                    info.authRequired -> showLogin()
                    else -> loadBooks()
                }
            }.onFailure {
                val prefix = if (automatic) "上次的服务器暂时无法连接：" else ""
                showConnection(prefix + LumosSession.friendlyError(it))
            }
        }
    }

    private fun showLogin(message: String = "") {
        reset("login")
        root.addView(title(server?.name ?: "微光阅"))
        root.addView(body("此服务器需要密码"), wrap(bottom = 20))
        val password = input("服务端密码").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(password, match(height = 52, bottom = 12))
        if (message.isNotBlank()) root.addView(error(message), wrap(bottom = 10))
        root.addView(primary("登录") {
            LumosSession.login(password.text.toString()) { result ->
                result.onSuccess { persistCookie(); loadBooks() }.onFailure { showLogin(LumosSession.friendlyError(it)) }
            }
        }, match(height = 48, bottom = 10))
        root.addView(secondary("更换服务器") { disconnect() }, match(height = 48))
    }

    private fun loadBooks() {
        showLoading("正在同步书库…")
        LumosSession.books { result ->
            result.onSuccess { books = it; showLibrary(it) }
                .onFailure { if (LumosSession.friendlyError(it).contains("登录")) showLogin() else showLibrary(books, LumosSession.friendlyError(it)) }
        }
    }

    private fun showLibrary(items: List<Book>, message: String = "") {
        root.tag = "library"
        root.removeAllViews()
        root.gravity = Gravity.NO_GRAVITY
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(title("微光阅"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("扫描") { LumosSession.scan { it.onSuccess { loadBooks() }.onFailure { error -> toast(LumosSession.friendlyError(error)) } } })
        header.addView(smallButton("设置") { showAccount() }, wrap(left = 8))
        root.addView(header, match(bottom = 14))
        val search = input("搜索书名、作者或系列")
        root.addView(search, match(height = 48, bottom = 16))
        if (message.isNotBlank()) root.addView(error(message), wrap(bottom = 10))
        val count = body("全部书籍 · ${items.size}")
        root.addView(count, match(bottom = 10))
        val minCell = dp(if (resources.configuration.smallestScreenWidthDp >= 600) 150 else 118)
        val columns = max(2, resources.displayMetrics.widthPixels / minCell)
        val grid = GridLayoutManager(this, columns)
        val recycler = RecyclerView(this).apply {
            setHasFixedSize(true)
            itemAnimator = null
            layoutManager = grid
        }
        val adapter = BookAdapter(::openBook)
        recycler.adapter = adapter
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == BookAdapter.HEADER) columns else 1
        }
        val recent = items.filter { it.progressTime.isNotBlank() }.sortedByDescending { it.progressTime }
        val shelves = items.groupBy { it.shelfKind to it.shelf }.toSortedMap(compareBy({ it.first }, { it.second }))
        val sections = buildList {
            if (recent.isNotEmpty()) add(LibraryItem.Header("最近阅读") to recent.take(6))
            shelves.forEach { (key, group) -> add(LibraryItem.Header("${if (key.first == "comic") "漫画" else "图书"} · ${key.second}") to group) }
        }
        val presented = sections.flatMap { (header, group) -> listOf(header) + group.map(LibraryItem::BookEntry) }
        adapter.submit(presented)
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        search.addTextChangedListener(SimpleTextWatcher { query ->
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) {
                count.text = "全部书籍 · ${items.size}"
                adapter.submit(presented)
            } else {
                val filtered = items.filter { "${it.title} ${it.author} ${it.series}".lowercase().contains(needle) }
                count.text = "搜索结果 · ${filtered.size}"
                adapter.submit(filtered.map(LibraryItem::BookEntry))
            }
        })
    }

    private fun openBook(book: Book) {
        LumosSession.selectedBook = book
        val intent = if (book.format in setOf("epub", "mobi", "azw3")) {
            Intent(this, WebBookActivity::class.java).putExtra(WebBookActivity.EXTRA_BOOK_ID, book.id)
        } else Intent(this, ReaderActivity::class.java)
        startActivity(intent)
    }

    private fun showAccount() {
        reset("account")
        root.addView(title("连接设置"))
        root.addView(body("${server?.name ?: "微光阅"} · API v${server?.apiVersion ?: 4}\n${LumosSession.client?.baseUrl().orEmpty()}"), wrap(bottom = 22))
        val eink = prefs.getBoolean("force_eink", false)
        root.addView(secondary(if (eink) "E‑INK 模式：已开启" else "E‑INK 模式：自动识别") {
            prefs.edit().putBoolean("force_eink", !eink).apply()
            root.setBackgroundColor(if (!eink) Color.WHITE else getColor(xyz.alumos.lumosreader.design.R.color.lumos_canvas))
            showAccount()
        }, match(height = 48, bottom = 10))
        root.addView(secondary("阅读统计") { showStats() }, match(height = 48, bottom = 10))
        root.addView(secondary("书架管理") { showShelves() }, match(height = 48, bottom = 10))
        root.addView(secondary("字体库") { showFonts() }, match(height = 48, bottom = 10))
        val palettes = listOf("睡莲晨雾" to 0xFF47777A.toInt(), "吉维尼花园" to 0xFF55765F.toInt(), "鲁昂黄昏" to 0xFF945B50.toInt(), "鸢尾微雨" to 0xFF596B8D.toInt())
        val paletteIndex = prefs.getInt("palette", 0).coerceIn(palettes.indices)
        root.addView(secondary("配色：${palettes[paletteIndex].first}") {
            prefs.edit().putInt("palette", (paletteIndex + 1) % palettes.size).apply()
            window.statusBarColor = palettes[(paletteIndex + 1) % palettes.size].second
            showAccount()
        }, match(height = 48, bottom = 10))
        root.addView(primary("返回书库") { showLibrary(books) }, match(height = 48, bottom = 10))
        root.addView(secondary("更换服务器") { disconnect() }, match(height = 48))
    }

    private fun showStats() {
        showLoading("正在读取统计…")
        LumosSession.stats { result -> result.onSuccess { stats ->
            reset("stats")
            root.addView(title("阅读统计"))
            root.addView(body("累计 ${formatDuration(stats.totalSeconds.toLong())}\n今天 ${formatDuration(stats.todaySeconds.toLong())}"), wrap(bottom = 18))
            stats.books.take(8).forEach { item -> root.addView(body("${item.title.ifBlank { item.bookId }} · ${formatDuration(item.seconds.toLong())}"), wrap(bottom = 8)) }
            root.addView(primary("返回设置") { showAccount() }, match(height = 48, bottom = 10))
        }.onFailure { showAccount(); toast(LumosSession.friendlyError(it)) } }
    }

    private fun showFonts() {
        fontsVisible = true
        showLoading("正在读取字体库…")
        LumosSession.fonts { result -> result.onSuccess { fonts ->
            reset("fonts")
            root.addView(title("字体库 · ${fonts.size}"))
            root.addView(body("字体会在 EPUB、MOBI 与 AZW3 的排版设置中按需下载。"), wrap(bottom = 16))
            root.addView(secondary("上传字体") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("font/ttf", "font/otf", "font/woff", "font/woff2", "application/font-sfnt"))
                }, REQUEST_FONT)
            }, match(height = 48, bottom = 12))
            fonts.take(16).forEach { font -> root.addView(body("${font.name} · ${formatBytes(font.size.toLong())}"), wrap(bottom = 7)) }
            root.addView(primary("返回设置") { showAccount() }, match(height = 48))
        }.onFailure { showAccount(); toast(LumosSession.friendlyError(it)) } }
    }

    private fun uploadFont(uri: Uri) {
        if (!fontsVisible) return
        val name = queryDisplayName(uri).takeIf { it.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "woff", "woff2") }
            ?: run { toast("仅支持 TTF、OTF、WOFF 和 WOFF2 字体"); return }
        val temporary = File.createTempFile("lumos-font-", ".${name.substringAfterLast('.')}", cacheDir)
        runCatching { contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use(input::copyTo) } ?: error("无法读取文件") }
            .onFailure { temporary.delete(); toast("无法读取字体") }
            .onSuccess {
                LumosSession.uploadFont(temporary.absolutePath, name) { result ->
                    temporary.delete()
                    result.onSuccess { toast("字体已上传"); showFonts() }.onFailure { toast(LumosSession.friendlyError(it)) }
                }
            }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0).substringAfterLast('/').substringAfterLast('\\')
        }
        return "font.ttf"
    }

    private fun showShelves() {
        showLoading("正在读取书架…")
        LumosSession.shelves { result -> result.onSuccess { state ->
            reset("shelves")
            root.addView(title("书架管理"))
            root.addView(body(if (state.automatic) "当前使用自动书架" else "每行填写：名称 | 目录 | auto/book/comic"), wrap(bottom = 12))
            val editor = EditText(this).apply {
                setText(state.shelves.joinToString("\n") { "${it.name} | ${it.path} | ${it.kind}" })
                hint = "漫画 | 漫画 | comic"
                minLines = 6; gravity = Gravity.TOP; setTextColor(getColor(xyz.alumos.lumosreader.design.R.color.lumos_ink)); setBackgroundResource(xyz.alumos.lumosreader.design.R.drawable.lumos_input); setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            root.addView(editor, match(height = 190, bottom = 12))
            root.addView(body("可用目录：${state.directories.take(12).joinToString("、")}"), wrap(bottom = 12))
            root.addView(primary("保存并扫描") {
                val shelves = editor.text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                    val parts = line.split('|').map(String::trim)
                    if (parts.size < 2) null else Bookshelf(parts[0], parts[1], parts.getOrElse(2) { "auto" })
                }
                LumosSession.saveShelves(shelves) { saved -> saved.onSuccess { loadBooks() }.onFailure { toast(LumosSession.friendlyError(it)) } }
            }, match(height = 48, bottom = 10))
            root.addView(secondary("返回设置") { showAccount() }, match(height = 48))
        }.onFailure { showAccount(); toast(LumosSession.friendlyError(it)) } }
    }

    private fun disconnect() {
        LumosSession.logout()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        prefs.edit().clear().apply()
        secureCookie.clear()
        LumosSession.clear()
        showConnection()
    }
    private fun persistCookie() { LumosSession.cookie()?.let(secureCookie::write) }
    private fun reset(tag: String) { root.tag = tag; root.removeAllViews(); root.gravity = Gravity.CENTER_VERTICAL }
    private fun showLoading(text: String) { root.tag = "loading"; root.removeAllViews(); root.gravity = Gravity.CENTER; root.addView(ProgressBar(this)); root.addView(body(text), wrap(top = 14)) }
    private fun title(text: String) = TextView(this).apply { this.text = text; setTextAppearance(xyz.alumos.lumosreader.design.R.style.TextAppearance_Lumos_Title); if (isEInk()) setTextColor(Color.BLACK) }
    private fun body(text: String) = TextView(this).apply { this.text = text; setTextAppearance(xyz.alumos.lumosreader.design.R.style.TextAppearance_Lumos_Body); setLineSpacing(0f, 1.25f); if (isEInk()) setTextColor(Color.BLACK) }
    private fun error(text: String) = body(text).apply { setTextColor(getColor(xyz.alumos.lumosreader.design.R.color.lumos_error)) }
    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint; setSingleLine(); setTextColor(getColor(xyz.alumos.lumosreader.design.R.color.lumos_ink));
        setHintTextColor(getColor(xyz.alumos.lumosreader.design.R.color.lumos_ink_secondary)); setBackgroundResource(xyz.alumos.lumosreader.design.R.drawable.lumos_input); setPadding(dp(14), 0, dp(14), 0)
    }
    private fun primary(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE); setBackgroundResource(xyz.alumos.lumosreader.design.R.drawable.lumos_button); backgroundTintList = ColorStateList.valueOf(accentColor()); stateListAnimator = null; setOnClickListener { action() }
    }
    private fun secondary(text: String, action: () -> Unit) = primary(text, action).apply { setTextColor(getColor(xyz.alumos.lumosreader.design.R.color.lumos_leaf)); setBackgroundResource(xyz.alumos.lumosreader.design.R.drawable.lumos_card) }
    private fun smallButton(text: String, action: () -> Unit) = secondary(text, action).apply { minWidth = 0; minimumWidth = 0 }
    private fun wrap(left: Int = 0, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(left), dp(top), 0, dp(bottom)) }
    private fun match(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (height > 0) dp(height) else height).apply { bottomMargin = dp(bottom) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun formatDuration(seconds: Long): String = if (seconds < 3600) "${seconds / 60} 分钟" else "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分钟"
    private fun formatBytes(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / 1024 / 1024} MB"
    private fun isEInk(): Boolean {
        if (prefs.getBoolean("force_eink", false)) return true
        val vendor = "${android.os.Build.MANUFACTURER} ${android.os.Build.BRAND}".lowercase()
        return vendor.contains("onyx") || vendor.contains("boox") || vendor.contains("ireader") || vendor.contains("zhangyue")
    }
    private fun accentColor(): Int {
        if (isEInk()) return Color.BLACK
        return intArrayOf(0xFF47777A.toInt(), 0xFF55765F.toInt(), 0xFF945B50.toInt(), 0xFF596B8D.toInt())[prefs.getInt("palette", 0).coerceIn(0, 3)]
    }

    companion object { private const val REQUEST_FONT = 1204 }
}
