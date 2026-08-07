package xyz.alumos.lumosreader.reader.epub

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import xyz.alumos.lumosreader.core.LumosSession

class WebBookActivity : Activity() {
    private lateinit var webView: WebView
    private val bookId by lazy { intent.getStringExtra(EXTRA_BOOK_ID).orEmpty() }
    private var themePrepared = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = LumosSession.client ?: run { finish(); return }
        val root = client.baseUrl()
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            client.sessionCookie()?.let { setCookie(root, "lumos_session=$it; Path=/; HttpOnly; SameSite=Strict") }
            flush()
        }
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.databaseEnabled = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            isHapticFeedbackEnabled = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val base = android.net.Uri.parse(root)
                    val target = request.url
                    return target.scheme != base.scheme || target.host != base.host || target.port != base.port
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (themePrepared || !wantsEInk()) return
                    themePrepared = true
                    view.evaluateJavascript("localStorage.setItem('lumos-app-theme', JSON.stringify({mode:'eink',palette:'water-lilies',accent:'#000000',secondary:'#000000'}))") {
                        view.loadUrl(bookUrl(root))
                    }
                }

            }
            loadUrl(if (wantsEInk()) root else bookUrl(root))
        }
        setContentView(webView)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val key = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) "ArrowLeft" else "ArrowRight"
            webView.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'$key'}))", null)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearCache(true)
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun wantsEInk(): Boolean {
        val forced = getSharedPreferences("lumos_connection", MODE_PRIVATE).getBoolean("force_eink", false)
        val vendor = "${android.os.Build.MANUFACTURER} ${android.os.Build.BRAND}".lowercase()
        return forced || vendor.contains("onyx") || vendor.contains("boox") || vendor.contains("ireader") || vendor.contains("zhangyue")
    }

    private fun bookUrl(root: String) = "${root.trimEnd('/')}?open=${android.net.Uri.encode(bookId)}"

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}
