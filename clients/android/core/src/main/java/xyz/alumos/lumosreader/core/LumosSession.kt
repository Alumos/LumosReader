package xyz.alumos.lumosreader.core

import android.os.Handler
import android.os.Looper
import uniffi.lumos_core.ApiClient
import uniffi.lumos_core.Book
import uniffi.lumos_core.ComicPage
import uniffi.lumos_core.Bookshelf
import uniffi.lumos_core.ShelvesState
import uniffi.lumos_core.ReadingStats
import uniffi.lumos_core.ServerFont
import uniffi.lumos_core.LumosException
import uniffi.lumos_core.ServerInfo
import uniffi.lumos_core.SessionState
import uniffi.lumos_core.createClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object LumosSession {
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "lumos-io").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    @Volatile
    var client: ApiClient? = null
        private set

    @Volatile
    var selectedBook: Book? = null

    fun connect(address: String, cookie: String?, callback: (Result<Pair<ServerInfo, SessionState>>) -> Unit) {
        task(callback) {
            createClient(address, cookie).also { client = it }.let { it.discover() to it.session() }
        }
    }

    fun login(password: String, callback: (Result<SessionState>) -> Unit) = withClient(callback) { it.login(password) }

    fun books(callback: (Result<List<Book>>) -> Unit) = withClient(callback) { it.books() }

    fun scan(callback: (Result<Unit>) -> Unit) = withClient(callback) { it.scan() }

    fun logout(callback: (Result<Unit>) -> Unit = {}) = withClient(callback) { it.logout() }

    fun stats(callback: (Result<ReadingStats>) -> Unit) = withClient(callback) { it.stats() }

    fun fonts(callback: (Result<List<ServerFont>>) -> Unit) = withClient(callback) { it.fonts() }

    fun uploadFont(path: String, name: String, callback: (Result<Unit>) -> Unit) =
        withClient(callback) { it.uploadFont(path, name) }

    fun shelves(callback: (Result<ShelvesState>) -> Unit) = withClient(callback) { it.shelves() }

    fun saveShelves(shelves: List<Bookshelf>, callback: (Result<Unit>) -> Unit) =
        withClient(callback) { it.saveShelves(shelves) }

    fun comicPages(bookId: String, callback: (Result<List<ComicPage>>) -> Unit) =
        withClient(callback) { it.comicPages(bookId) }

    fun bytes(path: String, callback: (Result<ByteArray>) -> Unit) = withClient(callback) { it.getBytes(path) }

    fun rangeBlocking(path: String, start: Long, endInclusive: Long): ByteArray =
        requireNotNull(client) { "尚未连接服务器" }.getRange(path, start.toULong(), endInclusive.toULong())

    fun download(path: String, destination: String, callback: (Result<Unit>) -> Unit) =
        withClient(callback) { it.download(path, destination) }

    fun saveProgress(bookId: String, position: Double, locator: String = "", callback: ((Result<Unit>) -> Unit)? = null) {
        val client = client ?: return
        task(callback ?: {}) { client.saveProgress(bookId, position.coerceIn(0.0, 1.0), locator); Unit }
    }

    fun addReadingTime(bookId: String, seconds: Int) {
        val client = client ?: return
        task<Unit>({}) { client.addReadingTime(bookId, seconds.coerceIn(1, 300).toUInt()) }
    }

    fun cookie(): String? = client?.sessionCookie()

    fun clear() { client = null }

    fun <T> task(callback: (Result<T>) -> Unit, block: () -> T) {
        io.execute {
            val result = runCatching(block)
            main.post { callback(result) }
        }
    }

    private fun <T> withClient(callback: (Result<T>) -> Unit, block: (ApiClient) -> T) {
        val current = client
        if (current == null) callback(Result.failure(IllegalStateException("尚未连接服务器")))
        else task(callback) { block(current) }
    }

    fun friendlyError(error: Throwable): String = when (error) {
        is LumosException.InvalidAddress -> "请输入完整的 HTTP 或 HTTPS 服务端根地址"
        is LumosException.IncompatibleServer -> "服务端 API 版本不兼容，需要 API v4"
        is LumosException.Unauthorized -> "登录已失效，请重新输入密码"
        is LumosException.Network -> "无法连接服务器，请检查地址和网络"
        is LumosException.Server -> "服务端返回错误"
        else -> error.message ?: "发生未知错误"
    }
}
