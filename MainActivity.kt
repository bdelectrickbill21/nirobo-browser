package org.nirobo.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** One open page. */
internal class Tab(val web: WebView) {
    var title: String = ""
    var url: String = ""
    var progress: Int = 100
    var blocked: Int = 0
    var desktop: Boolean = false
    var reader: Boolean = false
    var isAbout: Boolean = false
    @Volatile var host: String = ""
}

/**
 * Nirobo Browser.
 *
 * - Nirobo is the home page and the search engine.
 * - about: pages are built inside the app and work with no internet.
 * - Ads and trackers are blocked per site, with a switch for each.
 * - NewsHub is saved to the phone once a day and shown when there is no internet.
 */
open class BrowserActivity : Activity() {

    protected open val isPrivate: Boolean get() = false

    /** The sandbox is a private window that is locked down much further (see SandboxActivity). */
    protected open val isSandbox: Boolean get() = false

    private val homeUrl = "https://nirobo.pages.dev/"
    private val searchUrl = "https://nirobo.pages.dev/?q="
    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val desktopViewportJs =
        "(function(){var m=document.querySelector('meta[name=viewport]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m)}" +
            "m.setAttribute('content','width=1024');})();"

    private val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private var mobileUa = ""

    internal lateinit var store: Store
    private lateinit var bridge: Bridge

    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var holder: FrameLayout
    private lateinit var addr: EditText
    private lateinit var shield: TextView
    private lateinit var bar: ProgressBar
    private lateinit var tabCount: TextView
    private lateinit var switcher: LinearLayout
    private lateinit var switcherList: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var reloadBtn: TextView
    private lateinit var lockBtn: TextView

    // sandbox only: scripts are off until you turn them on (never saved)
    private var scriptsOn = false

    /** Where a new tab starts. */
    private val startUrl: String get() = if (isSandbox) "about:sandbox" else homeUrl

    private var fullView: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val barColor: Int
        get() = Color.parseColor(if (isSandbox) "#6B1414" else if (isPrivate) "#2A1F4B" else "#0E3B2C")

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    /** True when the app should draw its own pages dark. */
    internal fun isDark(): Boolean {
        val t = store.theme
        if (t == 1) return false
        if (t == 2) return true
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    // ---------- start ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        bridge = Bridge(this, store)
        window.statusBarColor = barColor
        applySecure()
        if (isSandbox) CookieManager.getInstance().setAcceptCookie(false)
        mobileUa = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        buildUi()
        if (isPrivate) wipePrivateData()
        loadBlocklist()
        askNotificationsOnce()
        if (!isPrivate) {
            NewsSaver.ensureScheduled(this)
            NewsSaver.saveIfDue(this) { }
        }
        val data = intent?.dataString
        newTab(if (isWebAddress(data)) data else startUrl, false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val data = intent?.dataString
        if (isWebAddress(data)) newTab(data, false)
    }

    private fun isWebAddress(s: String?): Boolean =
        s != null && (s.startsWith("http://") || s.startsWith("https://"))

    // ---------- the screen ----------

    private fun label(text: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        return t
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        root = FrameLayout(this)
        page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(barColor)
        top.setPadding(dp(8), dp(6), dp(8), dp(6))

        addr = EditText(this)
        addr.setSingleLine(true)
        addr.hint = if (isSandbox) "Sandbox: type an address" else if (isPrivate) "Private: search or type address" else "Search or type address"
        addr.textSize = 15f
        addr.setTextColor(Color.BLACK)
        addr.setHintTextColor(Color.GRAY)
        addr.imeOptions = EditorInfo.IME_ACTION_GO
        addr.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val box = GradientDrawable()
        box.setColor(Color.WHITE)
        box.cornerRadius = dp(20).toFloat()
        addr.background = box
        addr.setPadding(dp(14), dp(8), dp(14), dp(8))
        addr.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(addr.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        addr.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) addr.post { addr.selectAll() }
        }
        // like Chrome: a lock (or info) button on the left, reload or stop on the right
        lockBtn = label("\u24D8", 18f, Color.WHITE)
        lockBtn.setPadding(dp(4), dp(4), dp(10), dp(4))
        lockBtn.setOnClickListener { showSiteInfo() }
        top.addView(lockBtn, LinearLayout.LayoutParams(wrap, wrap))
        top.addView(addr, LinearLayout.LayoutParams(0, wrap, 1f))
        reloadBtn = label("\u21BB", 20f, Color.WHITE)
        reloadBtn.setPadding(dp(10), dp(4), dp(4), dp(4))
        reloadBtn.setOnClickListener { reloadOrStop() }
        top.addView(reloadBtn, LinearLayout.LayoutParams(wrap, wrap))

        shield = label("\uD83D\uDEE1 0", 14f, Color.WHITE)
        shield.setPadding(dp(10), dp(4), dp(4), dp(4))
        shield.setOnClickListener { showShieldMenu() }
        top.addView(shield, LinearLayout.LayoutParams(wrap, wrap))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.visibility = View.GONE

        holder = FrameLayout(this)

        // find-in-page bar, hidden until you ask for it
        findBar = LinearLayout(this)
        findBar.orientation = LinearLayout.HORIZONTAL
        findBar.gravity = Gravity.CENTER_VERTICAL
        findBar.setBackgroundColor(Color.parseColor("#F1F4F2"))
        findBar.setPadding(dp(10), dp(6), dp(6), dp(6))
        findBar.visibility = View.GONE
        findInput = EditText(this)
        findInput.setSingleLine(true)
        findInput.hint = "Find in page"
        findInput.textSize = 14f
        findInput.setTextColor(Color.BLACK)
        findInput.setHintTextColor(Color.GRAY)
        findInput.imeOptions = EditorInfo.IME_ACTION_SEARCH
        findInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                current?.web?.findAllAsync(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findInput.setOnEditorActionListener { _, _, _ ->
            current?.web?.findNext(true)
            true
        }
        findBar.addView(findInput, LinearLayout.LayoutParams(0, wrap, 1f))
        fun findBtn(t: String, action: () -> Unit) {
            val v = label(t, 16f, barColor)
            v.setPadding(dp(12), dp(8), dp(12), dp(8))
            v.setOnClickListener { action() }
            findBar.addView(v, LinearLayout.LayoutParams(wrap, wrap))
        }
        findBtn("\u25B2") { current?.web?.findNext(false) }
        findBtn("\u25BC") { current?.web?.findNext(true) }
        findBtn("\u2715") { closeFind() }

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.setBackgroundColor(Color.parseColor(if (isPrivate) "#EEEAF6" else "#F1F4F2"))
        fun button(symbol: String, action: () -> Unit) {
            val t = label(symbol, 22f, barColor)
            t.gravity = Gravity.CENTER
            t.setOnClickListener { action() }
            bottom.addView(t, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        button("\u25C0") { current?.web?.let { if (it.canGoBack()) it.goBack() } }
        button("\u25B6") { current?.web?.let { if (it.canGoForward()) it.goForward() } }
        button("\uFF0B") { newTab(startUrl, false) }
        button("\u2302") { go(startUrl) }

        tabCount = label("1", 12f, barColor)
        tabCount.gravity = Gravity.CENTER
        val frame = GradientDrawable()
        frame.setColor(Color.TRANSPARENT)
        frame.setStroke(dp(2), barColor)
        frame.cornerRadius = dp(5).toFloat()
        tabCount.background = frame
        val tabCell = FrameLayout(this)
        tabCell.setOnClickListener { showSwitcher() }
        tabCell.addView(tabCount, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        bottom.addView(tabCell, LinearLayout.LayoutParams(0, dp(50), 1f))

        button("\u22EE") { showMenu() }

        page.addView(top, LinearLayout.LayoutParams(match, wrap))
        page.addView(bar, LinearLayout.LayoutParams(match, dp(3)))
        page.addView(findBar, LinearLayout.LayoutParams(match, wrap))
        page.addView(holder, LinearLayout.LayoutParams(match, 0, 1f))
        page.addView(bottom, LinearLayout.LayoutParams(match, wrap))
        root.addView(page, FrameLayout.LayoutParams(match, match))

        switcher = LinearLayout(this)
        switcher.orientation = LinearLayout.VERTICAL
        switcher.setBackgroundColor(Color.WHITE)
        switcher.isClickable = true
        switcher.visibility = View.GONE

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setBackgroundColor(barColor)
        head.setPadding(dp(14), dp(8), dp(6), dp(8))
        val headTitle = label(if (isSandbox) "Sandbox tabs" else if (isPrivate) "Private tabs" else "Tabs", 18f, Color.WHITE)
        head.addView(headTitle, LinearLayout.LayoutParams(0, wrap, 1f))
        fun headButton(text: String, action: () -> Unit) {
            val t = label(text, 15f, Color.WHITE)
            t.setPadding(dp(12), dp(10), dp(12), dp(10))
            t.setOnClickListener { action() }
            head.addView(t, LinearLayout.LayoutParams(wrap, wrap))
        }
        headButton("\uFF0B New") {
            newTab(startUrl, false)
            hideSwitcher()
        }
        if (!isPrivate) {
            headButton("\uD83D\uDD76 Private") {
                openPrivate(null)
                hideSwitcher()
            }
        }
        headButton("\u2715 All") { closeAll() }
        switcher.addView(head, LinearLayout.LayoutParams(match, wrap))

        val scroll = ScrollView(this)
        switcherList = LinearLayout(this)
        switcherList.orientation = LinearLayout.VERTICAL
        scroll.addView(switcherList, FrameLayout.LayoutParams(match, wrap))
        switcher.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(switcher, FrameLayout.LayoutParams(match, match))

        setContentView(root)
    }

    // ---------- tabs ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(): Tab {
        val w = WebView(this)
        val t = Tab(w)
        val s = w.settings
        s.javaScriptEnabled = !isSandbox || scriptsOn
        s.domStorageEnabled = !isSandbox
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(!isSandbox)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setGeolocationEnabled(false)
        s.safeBrowsingEnabled = true
        if (isPrivate) s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.userAgentString = mobileUa
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)

        // dark mode for ordinary web pages
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.forceDark = if (isDark()) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }

        // The Bridge is added only while an about: page is showing (see loadInto).
        // A real website must never be able to reach it, or it could read your
        // bookmarks and history.
        w.setOnLongClickListener { v -> onLongPress(v as WebView) }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: ""
                if (scheme == "about") {
                    loadInto(t, u.toString())
                    return true
                }
                if (scheme == "https" || scheme == "http") {
                    // leaving one of our own pages: take the Bridge away before the website loads
                    if (t.isAbout) {
                        t.isAbout = false
                        view.removeJavascriptInterface("Bridge")
                    }
                    if (scheme == "https") return false
                    view.loadUrl(u.buildUpon().scheme("https").build().toString())
                    return true
                }
                if (isSandbox) {
                    toast("Blocked in the sandbox")
                    return true
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) return null
                val host = request.url.host ?: return null
                // sandbox: a page may only load files from its own site
                if (isSandbox && !sameSite(host, t.host)) {
                    t.blocked++
                    if (t === current) runOnUiThread { shield.text = "\uD83D\uDEE1 ${t.blocked}" }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (!store.siteShield(host)) return null
                if (isBlocked(host)) {
                    t.blocked++
                    if (t === current) runOnUiThread { shield.text = "\uD83D\uDEE1 ${t.blocked}" }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.blocked = 0
                // our own pages keep the about: name we gave them
                if (!(t.isAbout && (url.startsWith("data:") || url == "about:blank"))) {
                    t.url = url
                    t.isAbout = AboutPages.isAbout(url)
                    t.host = hostOf(url)
                }
                if (t === current) {
                    shield.text = "\uD83D\uDEE1 0"
                    if (!addr.hasFocus()) addr.setText(t.url)
                    updateChrome(t)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (t.desktop) view.evaluateJavascript(desktopViewportJs, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (t.desktop) view.evaluateJavascript(desktopViewportJs, null)
                t.progress = 100
                if (t === current) {
                    bar.visibility = View.GONE
                    updateChrome(t)
                }
                if (!t.isAbout) {
                    if (store.cookieBlock) view.evaluateJavascript(COOKIE_JS, null)
                    if (t.reader) applyReader(t)
                    if (!isPrivate) store.addHistory(url, t.title)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val why = TextUtils.htmlEncode(error.description.toString())
                    val saved = File(filesDir, "news_saved.json")
                    val hasNews = saved.exists() && saved.length() > 1000
                    val newsLink = if (hasNews)
                        "<p style='margin-top:22px'><a href='about:news' " +
                            "style='display:inline-block;background:#0B5D3B;color:#fff;" +
                            "padding:12px 20px;border-radius:11px;text-decoration:none;" +
                            "font-weight:600'>Read the saved news</a></p>" else ""
                    val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                        "</head><body style='font-family:sans-serif;padding:24px;color:#0E3B2C;" +
                        "background:#F6FAF7;line-height:1.6'>" +
                        "<h2 style='margin:0 0 8px'>Can't open this page</h2>" +
                        "<p style='color:#5C7A6C'>$why</p>" +
                        "<p style='color:#5C7A6C'>Check your internet connection. " +
                        "Nirobo Browser opens only secure (https) pages.</p>" +
                        newsLink +
                        "</body></html>"
                    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                t.progress = newProgress
                if (t === current) {
                    bar.progress = newProgress
                    bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    updateChrome(t)
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                t.title = title ?: ""
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (isSandbox || !isUserGesture) return false
                val nt = newTab(null, false)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = nt.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.firstOrNull { it.web === window }?.let { closeTab(it) }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullView = view
                fullCb = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                page.visibility = View.GONE
                this@BrowserActivity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                if (isSandbox) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                fileCb?.onReceiveValue(null)
                fileCb = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams.createIntent(), 1)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }

        w.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (isSandbox) {
                toast("Downloads are blocked in the sandbox")
                return@setDownloadListener
            }
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                if (Build.VERSION.SDK_INT < 29) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } else {
                    val req = DownloadManager.Request(Uri.parse(url))
                    req.setMimeType(mimetype)
                    req.addRequestHeader("User-Agent", userAgent)
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (cookie != null) req.addRequestHeader("Cookie", cookie)
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    val id = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    if (!isPrivate) store.addDownload(id, name, url)
                    Toast.makeText(this@BrowserActivity, "Downloading $name", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@BrowserActivity, "Can't download this file", Toast.LENGTH_SHORT).show()
            }
        }
        return t
    }

    private fun newTab(input: String?, background: Boolean): Tab {
        val t = createTab()
        tabs.add(t)
        if (!background) switchTo(t)
        updateTabCount()
        if (input != null) loadInto(t, input)
        if (background) Toast.makeText(this, "Opened in a background tab", Toast.LENGTH_SHORT).show()
        return t
    }

    /** Loads either a real address or one of the internal about: pages. */
    internal fun loadInto(t: Tab, input: String) {
        val low = input.trim().lowercase()
        // hidden command: "about:sandbox" or "sandbox:<address>" opens the sandbox window
        if (!isSandbox && (low == "about:sandbox" || low.startsWith("sandbox:"))) {
            val rest = if (low.startsWith("sandbox:")) input.trim().substring(8).trim() else ""
            openSandbox(if (rest.isEmpty()) null else toUrl(rest))
            return
        }
        if (AboutPages.isAbout(input)) {
            val name = AboutPages.name(input)
            if (isSandbox && name != "blank" && name != "sandbox") {
                toast("Internal pages are switched off in the sandbox")
                return
            }
            val shown = "about:" + name
            t.isAbout = true
            t.url = shown
            t.host = ""
            t.title = shown
            // The bridge exists only for internal pages, and is removed again
            // before any real website is loaded.
            t.web.removeJavascriptInterface("Bridge")
            val html: String
            if (isSandbox) {
                html = if (name == "blank") AboutPages.build("about:blank", isDark()) else sandboxPage()
            } else {
                t.web.addJavascriptInterface(bridge, "Bridge")
                html = AboutPages.build(shown, isDark())
            }
            // the page name is also the history address, so back/forward and the address bar keep the right name
            t.web.loadDataWithBaseURL(shown, html, "text/html", "utf-8", shown)
            if (t === current) {
                addr.setText(shown)
                shield.text = "\uD83D\uDEE1 0"
                updateChrome(t)
            }
            return
        }
        val u = toUrl(input) ?: return
        t.isAbout = false
        t.web.removeJavascriptInterface("Bridge")
        t.web.loadUrl(u)
    }

    private fun switchTo(t: Tab) {
        current = t
        holder.removeAllViews()
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        holder.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addr.setText(t.url)
        shield.text = "\uD83D\uDEE1 ${t.blocked}"
        updateChrome(t)
        bar.progress = t.progress
        bar.visibility = if (t.progress in 1..99) View.VISIBLE else View.GONE
        updateTabCount()
        t.web.requestFocus()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        t.web.stopLoading()
        t.web.destroy()
        if (tabs.isEmpty()) {
            if (isPrivate) {
                finish()
                return
            }
            newTab(startUrl, false)
        } else if (current === t) {
            switchTo(tabs[minOf(i, tabs.size - 1)])
        }
        updateTabCount()
        refreshSwitcher()
    }

    private fun closeAll() {
        for (t in ArrayList(tabs)) {
            (t.web.parent as? ViewGroup)?.removeView(t.web)
            t.web.destroy()
        }
        tabs.clear()
        current = null
        if (isPrivate) {
            finish()
            return
        }
        newTab(startUrl, false)
        hideSwitcher()
    }

    private fun updateTabCount() {
        tabCount.text = tabs.size.toString()
    }

    private fun showSwitcher() {
        hideKeyboard()
        refreshSwitcher()
        switcher.visibility = View.VISIBLE
    }

    private fun hideSwitcher() {
        switcher.visibility = View.GONE
    }

    private fun refreshSwitcher() {
        switcherList.removeAllViews()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        if (isPrivate) {
            val note = label(
                if (isSandbox) "Sandbox: scripts, cookies, storage and downloads are blocked. Everything is erased when you close it."
                else "Nothing is saved here. Everything is erased when you close these tabs.",
                13f, Color.GRAY
            )
            note.setPadding(dp(14), dp(10), dp(14), dp(10))
            switcherList.addView(note, LinearLayout.LayoutParams(match, wrap))
        }
        for (t in tabs) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(14), dp(10), dp(6), dp(10))
            row.setBackgroundColor(if (t === current) Color.parseColor("#E3F1EA") else Color.WHITE)

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val title = label(if (t.title.isEmpty()) "New tab" else t.title, 15f, Color.BLACK)
            title.setSingleLine(true)
            title.ellipsize = TextUtils.TruncateAt.END
            val url = label(t.url, 12f, Color.GRAY)
            url.setSingleLine(true)
            url.ellipsize = TextUtils.TruncateAt.END
            col.addView(title)
            col.addView(url)
            row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))

            val x = label("\u2715", 20f, Color.DKGRAY)
            x.setPadding(dp(14), dp(8), dp(14), dp(8))
            x.setOnClickListener { closeTab(t) }
            row.addView(x, LinearLayout.LayoutParams(wrap, wrap))

            row.setOnClickListener {
                switchTo(t)
                hideSwitcher()
            }
            switcherList.addView(row, LinearLayout.LayoutParams(match, wrap))
        }
    }

    // ---------- find in page ----------

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        findInput.setText("")
        current?.web?.clearMatches()
        hideKeyboard()
    }

    // ---------- reader mode ----------

    private fun applyReader(t: Tab) {
        val size = store.readerSize
        val js = """
        (function(){
          if(document.getElementById('__nirobo_reader'))return;
          var best=null,bestLen=0;
          var cands=document.querySelectorAll('article,main,[role=main],.post,.entry-content,.article-body,#content,.content');
          for(var i=0;i<cands.length;i++){
            var n=cands[i].innerText?cands[i].innerText.length:0;
            if(n>bestLen){bestLen=n;best=cands[i]}
          }
          if(!best||bestLen<400){
            var ps=document.querySelectorAll('p');var sum=0;
            for(var j=0;j<ps.length;j++)sum+=ps[j].innerText.length;
            if(sum<400)return;
            best=document.body;
          }
          var title=document.title||'';
          var h1=document.querySelector('h1');
          if(h1&&h1.innerText)title=h1.innerText;
          var html=best.innerHTML;
          var dark=${if (isDark()) "true" else "false"};
          var bg=dark?'#0B1512':'#FBFCFB', ink=dark?'#E8F1EC':'#16241E',
              dim=dark?'#8FA79C':'#5C7A6C', line=dark?'#1E3A32':'#E2EAE5';
          var wrap=document.createElement('div');
          wrap.id='__nirobo_reader';
          wrap.style.cssText='position:fixed;inset:0;z-index:2147483647;overflow:auto;'+
            'background:'+bg+';color:'+ink+';padding:22px 18px 60px;'+
            'font-family:Georgia,serif;font-size:${size}px;line-height:1.75';
          wrap.innerHTML='<div style="max-width:680px;margin:0 auto">'+
            '<div style="font-family:-apple-system,Roboto,sans-serif;font-size:11px;'+
            'letter-spacing:1.2px;text-transform:uppercase;color:'+dim+';margin-bottom:10px">'+
            'Reader mode</div>'+
            '<h1 style="font-size:1.55em;line-height:1.25;margin:0 0 18px;color:'+ink+'">'+
            title.replace(/[<>&]/g,'')+'</h1>'+
            '<div id="__nirobo_body">'+html+'</div>'+
            '<div style="margin-top:34px;padding-top:18px;border-top:1px solid '+line+'">'+
            '<button id="__nirobo_exit" style="font-family:-apple-system,Roboto,sans-serif;'+
            'font-size:14px;font-weight:600;border:0;border-radius:10px;padding:11px 18px;'+
            'background:#0B5D3B;color:#fff">Exit reader</button></div></div>';
          document.body.appendChild(wrap);
          var b=document.getElementById('__nirobo_body');
          b.querySelectorAll('script,style,nav,aside,form,iframe,button').forEach(function(e){e.remove()});
          b.querySelectorAll('img').forEach(function(im){
            im.style.cssText='max-width:100%;height:auto;border-radius:10px;margin:14px 0';
          });
          b.querySelectorAll('a').forEach(function(a){
            a.style.color='#0B5D3B';a.style.textDecoration='underline';
          });
          b.querySelectorAll('p').forEach(function(p){p.style.margin='0 0 1.1em'});
          document.getElementById('__nirobo_exit').onclick=function(){
            var w=document.getElementById('__nirobo_reader');
            if(w)w.remove();
          };
        })();
        """
        t.web.evaluateJavascript(js, null)
    }

    private fun toggleReader() {
        val t = current ?: return
        if (t.isAbout) {
            Toast.makeText(this, "Reader mode works on web pages", Toast.LENGTH_SHORT).show()
            return
        }
        t.reader = !t.reader
        if (t.reader) {
            applyReader(t)
            Toast.makeText(this, "Reader mode on", Toast.LENGTH_SHORT).show()
        } else {
            t.web.evaluateJavascript(
                "(function(){var w=document.getElementById('__nirobo_reader');if(w)w.remove()})();", null
            )
            Toast.makeText(this, "Reader mode off", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- helpers: buttons, screen lock, sandbox ----------

    /** Android 13 and newer: ask once, so download progress can show as a notification. */
    private fun askNotificationsOnce() {
        if (isPrivate || Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) return
        if (store.getString("notif_asked", "") == "1") return
        store.putString("notif_asked", "1")
        requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun hostOf(u: String): String = try {
        Uri.parse(u).host ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun lastTwo(h: String): String {
        val p = h.lowercase().split('.')
        return if (p.size <= 2) h.lowercase() else p[p.size - 2] + "." + p[p.size - 1]
    }

    /** Same site, roughly: the last two parts of the name match. */
    private fun sameSite(a: String, b: String): Boolean = b.isEmpty() || lastTwo(a) == lastTwo(b)

    /** Block screen mirroring, casting, recording and screenshots. Always on in private and sandbox windows. */
    internal fun applySecure() {
        if (isPrivate || store.secureScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Lock or info symbol on the left, reload or stop on the right. */
    private fun updateChrome(t: Tab) {
        reloadBtn.text = if (t.progress in 1..99) "\u2715" else "\u21BB"
        lockBtn.text = if (t.url.startsWith("https://")) "\uD83D\uDD12" else "\u24D8"
    }

    private fun reloadOrStop() {
        val t = current ?: return
        if (t.progress in 1..99) {
            t.web.stopLoading()
            t.progress = 100
            bar.visibility = View.GONE
            updateChrome(t)
            return
        }
        if (t.isAbout) loadInto(t, t.url) else t.web.reload()
    }

    private fun showSiteInfo() {
        val t = current ?: return
        val message = when {
            t.isAbout -> "This page is inside Nirobo Browser. It never leaves your phone."
            t.url.startsWith("https://") -> "The connection is secure (https).\n\nBlocked on this page: ${t.blocked}"
            else -> t.url
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (t.host.isEmpty()) "Nirobo Browser" else t.host)
            .setMessage(message)
            .setNeutralButton("Copy address") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("link", t.url))
                toast("Address copied")
            }
            .setNegativeButton("Close", null)
        if (!t.isAbout) b.setPositiveButton("Shield options") { _, _ -> showShieldMenu() }
        b.show()
    }

    private fun toggleSandboxScripts() {
        scriptsOn = !scriptsOn
        for (x in tabs) x.web.settings.javaScriptEnabled = scriptsOn
        val t = current
        if (t != null && !t.isAbout) t.web.reload()
        toast(if (scriptsOn) "Scripts are ON. Be careful." else "Scripts are OFF")
    }

    private fun sandboxPage(): String {
        val dark = isDark()
        val bg = if (dark) "#1A0F0F" else "#FFF6F4"
        val ink = if (dark) "#F3DCDC" else "#4A1414"
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'><title>Sandbox</title></head>" +
            "<body style='font-family:sans-serif;padding:22px;line-height:1.6;background:" + bg + ";color:" + ink + "'>" +
            "<h2 style='margin:0 0 6px'>Nirobo Sandbox</h2>" +
            "<p>A locked room for links you do not trust. Type an address above.</p>" +
            "<ul style='padding-left:18px'>" +
            "<li>Scripts are off (turn them on in the menu only if you must).</li>" +
            "<li>No cookies and no stored data.</li>" +
            "<li>A page can load files only from its own site.</li>" +
            "<li>No downloads, no pop-ups, no file uploads, no other apps.</li>" +
            "<li>Nothing is saved. Closing the sandbox erases it.</li>" +
            "<li>Screenshots and screen mirroring are blocked.</li>" +
            "</ul></body></html>"
    }

    // ---------- links (long press) ----------

    private fun onLongPress(w: WebView): Boolean {
        val type = w.hitTestResult.type
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            val h = Handler(Looper.getMainLooper()) { m ->
                val u = m.data.getString("url")
                if (u != null) showLinkMenu(u)
                true
            }
            w.requestFocusNodeHref(h.obtainMessage())
            return true
        }
        return false
    }

    private fun showLinkMenu(url: String) {
        val inNew = "Open in new tab"
        val inBack = "Open in background tab"
        val inPriv = "Open in private tab"
        val copy = "Copy link"
        val share = "Share link"
        val inSand = "Open in sandbox"
        val items = ArrayList<String>()
        items.add(inNew)
        items.add(inBack)
        if (!isPrivate) items.add(inPriv)
        if (!isSandbox) items.add(inSand)
        items.add(copy)
        items.add(share)
        AlertDialog.Builder(this)
            .setTitle(if (url.length > 60) url.substring(0, 60) + "..." else url)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    inNew -> newTab(url, false)
                    inBack -> newTab(url, true)
                    inPriv -> openPrivate(url)
                    inSand -> openSandbox(url)
                    copy -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("link", url))
                        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
                    }
                    share -> {
                        val send = Intent(Intent.ACTION_SEND)
                        send.type = "text/plain"
                        send.putExtra(Intent.EXTRA_TEXT, url)
                        startActivity(Intent.createChooser(send, "Share"))
                    }
                }
            }
            .show()
    }

    private fun openPrivate(url: String?) {
        val i = Intent(this, PrivateActivity::class.java)
        if (url != null) i.data = Uri.parse(url)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun openSandbox(url: String?) {
        val i = Intent(this, SandboxActivity::class.java)
        if (url != null) i.data = Uri.parse(url)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun wipePrivateData() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            val tmp = WebView(this)
            tmp.clearCache(true)
            tmp.destroy()
        } catch (e: Exception) {
        }
    }

    // ---------- opening pages ----------

    private fun toUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (AboutPages.isAbout(t)) return t
        return when {
            t.startsWith("https://") -> t
            t.startsWith("http://") -> "https://" + t.substring(7)
            t.contains(" ") || !t.contains(".") -> store.searchUrl + URLEncoder.encode(t, "UTF-8")
            else -> "https://$t"
        }
    }

    private fun go(input: String) {
        val t = current ?: return
        loadInto(t, input)
    }

    /** Called from an about: page when the user taps a link. */
    internal fun openFromPage(url: String) {
        if (url.isEmpty()) return
        val t = current ?: return
        loadInto(t, url)
    }

    /** Runs JavaScript in the page the user is looking at. */
    internal fun evalInCurrent(js: String) {
        current?.web?.evaluateJavascript(js, null)
    }

    internal fun saveNewsNow(done: (Boolean) -> Unit) {
        NewsSaver.save(this) { ok -> done(ok) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addr.windowToken, 0)
        current?.web?.requestFocus()
    }

    // ---------- the shield button ----------

    private fun showShieldMenu() {
        val t = current ?: return
        val host = try {
            Uri.parse(t.url).host ?: ""
        } catch (e: Exception) {
            ""
        }
        if (host.isEmpty()) {
            Toast.makeText(this, "Blocked ${t.blocked} ads and trackers on this page", Toast.LENGTH_SHORT).show()
            return
        }
        val on = store.siteShield(host)
        val items = arrayOf(
            if (on) "Turn blocking off for $host" else "Turn blocking on for $host",
            if (store.cookieBlock) "Stop blocking cookie pop-ups" else "Block cookie pop-ups",
            "Ad shield settings",
            "Blocked on this page: ${t.blocked}"
        )
        AlertDialog.Builder(this)
            .setTitle(host)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        store.setSiteShield(host, !on)
                        Toast.makeText(this, if (!on) "Blocking on for $host" else "Blocking off for $host", Toast.LENGTH_SHORT).show()
                        t.web.reload()
                    }
                    1 -> {
                        store.cookieBlock = !store.cookieBlock
                        Toast.makeText(this, if (store.cookieBlock) "Cookie pop-ups blocked" else "Cookie pop-ups allowed", Toast.LENGTH_SHORT).show()
                        t.web.reload()
                    }
                    2 -> loadInto(t, "about:shield")
                }
            }
            .show()
    }

    // ---------- menu ----------

    private fun showMenu() {
        val t = current ?: return
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(name: String, action: () -> Unit) {
            items.add(name)
            acts.add(action)
        }
        add("\uFF0B New tab") { newTab(startUrl, false) }
        if (!isPrivate) add("\uD83D\uDD76 New private tab") { openPrivate(null) }
        if (!isPrivate) {
            add("\u2606 Bookmarks") { loadInto(t, "about:bookmarks") }
            add("\uD83D\uDD52 History") { loadInto(t, "about:history") }
            add("\u2193 Downloads") { loadInto(t, "about:downloads") }
        }
        add("\uD83D\uDD0D Find in page") { openFind() }
        add(if (t.reader) "Exit reader mode" else "\uD83D\uDCD6 Reader mode") { toggleReader() }
        add("Share page") {
            val send = Intent(Intent.ACTION_SEND)
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, current?.web?.url ?: homeUrl)
            startActivity(Intent.createChooser(send, "Share"))
        }
        if (!isSandbox) {
            add("Open in another app") {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(current?.web?.url ?: homeUrl))
                startActivity(Intent.createChooser(view, "Open with"))
            }
        }
        add(if (t.desktop) "Use mobile site" else "Use desktop site") { toggleDesktop() }
        if (isSandbox) {
            add(if (scriptsOn) "Sandbox: turn scripts OFF" else "Sandbox: turn scripts ON") { toggleSandboxScripts() }
        }
        if (!isPrivate) add("Check for updates") { Updater.check(this, true) }
        add(
            if (isSandbox) "Close the sandbox"
            else if (isPrivate) "Close all private tabs"
            else "Clear browsing data"
        ) { clearData() }
        if (!isSandbox) add("About Nirobo Browser") { loadInto(t, "about:version") }

        // the icon row on top of the menu, like Chrome: forward, bookmark, downloads, info, reload
        val dlgRef = arrayOfNulls<AlertDialog>(1)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(4), dp(8), dp(4), dp(4))
        fun icon(symbol: String, action: () -> Unit) {
            val v = label(symbol, 22f, barColor)
            v.gravity = Gravity.CENTER
            v.setPadding(0, dp(10), 0, dp(10))
            v.setOnClickListener {
                dlgRef[0]?.dismiss()
                action()
            }
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        icon("\u25B6") { if (t.web.canGoForward()) t.web.goForward() }
        if (!isPrivate) {
            icon(if (store.isBookmarked(t.url)) "\u2605" else "\u2606") { toggleBookmark(t) }
            icon("\u2193") { loadInto(t, "about:downloads") }
        }
        icon("\u24D8") { showSiteInfo() }
        icon("\u21BB") { reloadOrStop() }

        val dlg = AlertDialog.Builder(this)
            .setCustomTitle(row)
            .setItems(items.toTypedArray()) { _, which -> acts[which]() }
            .create()
        dlgRef[0] = dlg
        dlg.show()
    }

    private fun toggleBookmark(t: Tab) {
        if (t.isAbout || !t.url.startsWith("http")) {
            toast("This page can't be bookmarked")
            return
        }
        if (store.isBookmarked(t.url)) {
            store.removeBookmark(t.url)
            toast("Bookmark removed")
        } else {
            store.addBookmark(t.url, t.title)
            toast("Bookmarked")
        }
    }

    private fun clearData() {
        if (isPrivate) {
            closeAll()
            return
        }
        current?.web?.clearCache(true)
        current?.web?.clearHistory()
        current?.web?.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        store.clearHistory()
        toast("Cleared")
    }

    private fun toggleDesktop() {
        val t = current ?: return
        t.desktop = !t.desktop
        val s = t.web.settings
        s.userAgentString = if (t.desktop) desktopUa else mobileUa
        s.useWideViewPort = true
        s.loadWithOverviewMode = t.desktop
        if (!t.isAbout) t.web.reload()
    }

    // ---------- ad and tracker blocking ----------

    private fun isBlocked(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (h.contains('.')) {
            if (hosts.contains(h)) return true
            h = h.substringAfter('.')
        }
        return false
    }

    private fun addHost(line: String) {
        val l = line.trim().lowercase()
        if (l.isEmpty() || l.startsWith("#")) return
        val h = l.split(Regex("\\s+")).last()
        if (h.contains('.') && !h.contains('/') && !h.contains(':') && h != "localhost") hosts.add(h)
    }

    private fun loadBlocklist() {
        val mayDownload = !isPrivate
        Thread {
            try {
                assets.open("blocklist.txt").bufferedReader().useLines { lines -> lines.forEach { addHost(it) } }
            } catch (e: Exception) {
            }
            val saved = File(filesDir, "blocklist_extra.txt")
            if (saved.exists()) {
                try {
                    saved.useLines { lines -> lines.forEach { addHost(it) } }
                } catch (e: Exception) {
                }
            }
            val weekMs = 7L * 24 * 3600 * 1000
            val old = !saved.exists() || System.currentTimeMillis() - saved.lastModified() > weekMs
            if (old && mayDownload) {
                try {
                    val c = URL("https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext")
                        .openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    if (c.responseCode == 200) {
                        val text = c.inputStream.bufferedReader().readText()
                        if (text.length > 1000) {
                            saved.writeText(text)
                            text.lineSequence().forEach { addHost(it) }
                        }
                    }
                    c.disconnect()
                } catch (e: Exception) {
                }
            }
        }.start()
    }

    // ---------- the phone's own buttons ----------

    private fun exitFullscreen() {
        val v = fullView ?: return
        root.removeView(v)
        fullView = null
        fullCb?.onCustomViewHidden()
        fullCb = null
        page.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun onBackPressed() {
        val t = current
        when {
            fullView != null -> exitFullscreen()
            findBar.visibility == View.VISIBLE -> closeFind()
            switcher.visibility == View.VISIBLE -> hideSwitcher()
            t != null && t.web.canGoBack() -> t.web.goBack()
            t != null && tabs.size > 1 -> closeTab(t)
            else -> super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onPause() {
        current?.web?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        current?.web?.onResume()
        if (!isPrivate) {
            NewsSaver.saveIfDue(this) { }
            Updater.check(this, false)
        }
    }

    override fun onDestroy() {
        for (t in ArrayList(tabs)) {
            (t.web.parent as? ViewGroup)?.removeView(t.web)
            t.web.destroy()
        }
        tabs.clear()
        if (isPrivate) wipePrivateData()
        super.onDestroy()
    }

    companion object {
        /**
         * Hides cookie-consent banners and answers the common ones.
         * Runs on every page when "Block cookie pop-ups" is on.
         */
        private const val COOKIE_JS = """
        (function(){
          if(window.__nirobo_cookie)return; window.__nirobo_cookie=1;
          var sel=[
            '#onetrust-banner-sdk','#onetrust-consent-sdk','.onetrust-pc-dark-filter',
            '#CybotCookiebotDialog','#CybotCookiebotDialogBodyUnderlay',
            '.cc-window','.cookie-consent','.cookie-banner','.cookie-notice',
            '#cookie-banner','#cookie-notice','#cookieConsent','#cookie-law-info-bar',
            '.qc-cmp2-container','#qc-cmp2-container','.fc-consent-root',
            '#gdpr-banner','.gdpr-banner','.cmp-container',
            '[id^="sp_message_container"]','.truste_box_overlay','#truste-consent-track',
            '.osano-cm-window','#didomi-host','.didomi-popup-container'
          ];
          function kill(){
            for(var i=0;i<sel.length;i++){
              var els=document.querySelectorAll(sel[i]);
              for(var j=0;j<els.length;j++){
                var e=els[j];
                if(e&&e.style){e.style.setProperty('display','none','important');
                  e.style.setProperty('visibility','hidden','important')}
              }
            }
            if(document.body){
              document.body.style.setProperty('overflow','auto','important');
              document.documentElement.style.setProperty('overflow','auto','important');
            }
          }
          kill();
          var n=0,iv=setInterval(function(){kill();if(++n>20)clearInterval(iv)},400);
          var mo=new MutationObserver(kill);
          try{mo.observe(document.documentElement,{childList:true,subtree:true})}catch(e){}
          setTimeout(function(){try{mo.disconnect()}catch(e){}},12000);
        })();
        """
    }
}

/** The normal browser. */
class MainActivity : BrowserActivity()

/** The private browser: its own window, its own storage, no screenshots, erased when closed. */
class PrivateActivity : BrowserActivity() {
    override val isPrivate: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            WebView.setDataDirectorySuffix("private")
        } catch (e: Exception) {
        }
        super.onCreate(savedInstanceState)
    }
}

/**
 * The sandbox: a window for links you do not trust.
 * It is its own process with its own storage, like the private window, and on top of that:
 * scripts off, no cookies, no stored data, no downloads, no pop-ups, no file uploads,
 * no opening other apps, and a page may only load files from its own site.
 */
class SandboxActivity : BrowserActivity() {
    override val isPrivate: Boolean get() = true
    override val isSandbox: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            WebView.setDataDirectorySuffix("sandbox")
        } catch (e: Exception) {
        }
        super.onCreate(savedInstanceState)
    }
}
