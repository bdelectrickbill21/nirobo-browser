package org.nirobo.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
}

/**
 * Nirobo Browser: a small, private browser.
 * - Nirobo is the home page and the search engine.
 * - Ads and trackers are blocked by a public list of domains.
 * - Tabs, background tabs, and a private mode that runs apart from the normal browser.
 */
open class BrowserActivity : Activity() {

    protected open val isPrivate: Boolean get() = false

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

    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var holder: FrameLayout
    private lateinit var addr: EditText
    private lateinit var shield: TextView
    private lateinit var bar: ProgressBar
    private lateinit var tabCount: TextView
    private lateinit var switcher: LinearLayout
    private lateinit var switcherList: LinearLayout

    private var fullView: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val barColor: Int
        get() = Color.parseColor(if (isPrivate) "#2A1F4B" else "#0E3B2C")

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    // ---------- start ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = barColor
        if (isPrivate) {
            // no screenshots, and the page is hidden in the recent apps list
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        mobileUa = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        buildUi()
        if (isPrivate) wipePrivateData()
        loadBlocklist()
        val data = intent?.dataString
        newTab(if (isWebAddress(data)) data else homeUrl, false)
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

        // top bar: address box + shield counter
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(barColor)
        top.setPadding(dp(8), dp(6), dp(8), dp(6))

        addr = EditText(this)
        addr.setSingleLine(true)
        addr.hint = if (isPrivate) "Private: search or type address" else "Search or type address"
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
        top.addView(addr, LinearLayout.LayoutParams(0, wrap, 1f))

        shield = label("\uD83D\uDEE1 0", 14f, Color.WHITE)
        shield.setPadding(dp(10), dp(4), dp(4), dp(4))
        shield.setOnClickListener {
            val n = current?.blocked ?: 0
            Toast.makeText(this, "Blocked $n ads and trackers on this page", Toast.LENGTH_SHORT).show()
        }
        top.addView(shield, LinearLayout.LayoutParams(wrap, wrap))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.visibility = View.GONE

        holder = FrameLayout(this)

        // bottom bar, with symbols
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
        button("\u2302") { go(homeUrl) }
        button("\u21BB") { current?.web?.reload() }

        // the tabs button: a box with the number of open tabs
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
        page.addView(holder, LinearLayout.LayoutParams(match, 0, 1f))
        page.addView(bottom, LinearLayout.LayoutParams(match, wrap))
        root.addView(page, FrameLayout.LayoutParams(match, match))

        // the list of open tabs (hidden until you tap the tabs button)
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
        val headTitle = label(if (isPrivate) "Private tabs" else "Tabs", 18f, Color.WHITE)
        head.addView(headTitle, LinearLayout.LayoutParams(0, wrap, 1f))
        fun headButton(text: String, action: () -> Unit) {
            val t = label(text, 15f, Color.WHITE)
            t.setPadding(dp(12), dp(10), dp(12), dp(10))
            t.setOnClickListener { action() }
            head.addView(t, LinearLayout.LayoutParams(wrap, wrap))
        }
        headButton("\uFF0B New") {
            newTab(homeUrl, false)
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
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.javaScriptCanOpenWindowsAutomatically = false   // pop-ups only when you tap something
        s.setSupportMultipleWindows(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setGeolocationEnabled(false)
        s.safeBrowsingEnabled = true
        if (isPrivate) s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.userAgentString = mobileUa
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)

        w.setOnLongClickListener { v -> onLongPress(v as WebView) }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: ""
                if (scheme == "https") return false
                if (scheme == "http") {
                    view.loadUrl(u.buildUpon().scheme("https").build().toString())
                    return true
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame && isBlocked(request.url.host)) {
                    t.blocked++
                    if (t === current) runOnUiThread { shield.text = "\uD83D\uDEE1 ${t.blocked}" }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.blocked = 0
                t.url = url
                if (t === current) {
                    shield.text = "\uD83D\uDEE1 0"
                    if (!addr.hasFocus()) addr.setText(url)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (t.desktop) view.evaluateJavascript(desktopViewportJs, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (t.desktop) view.evaluateJavascript(desktopViewportJs, null)
                t.progress = 100
                if (t === current) bar.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val why = TextUtils.htmlEncode(error.description.toString())
                    val html = "<html><body style='font-family:sans-serif;padding:24px;color:#0E3B2C'>" +
                        "<h2>Can't open this page</h2><p>$why</p>" +
                        "<p>Check your internet. Nirobo Browser opens only secure (https) pages.</p>" +
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
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                t.title = title ?: ""
            }

            // a link that wants a new window: it opens as a new tab, but only if you tapped it (no pop-up ads)
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
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

            // websites may not use the camera, the microphone or the location
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
            try {
                if (Build.VERSION.SDK_INT < 29) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } else {
                    val req = DownloadManager.Request(Uri.parse(url))
                    req.setMimeType(mimetype)
                    req.addRequestHeader("User-Agent", userAgent)
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (cookie != null) req.addRequestHeader("Cookie", cookie)
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimetype)
                    )
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this@BrowserActivity, "Downloading...", Toast.LENGTH_SHORT).show()
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
        val u = if (input == null) null else toUrl(input)
        if (u != null) t.web.loadUrl(u)
        if (background) Toast.makeText(this, "Opened in a background tab", Toast.LENGTH_SHORT).show()
        return t
    }

    private fun switchTo(t: Tab) {
        current = t
        holder.removeAllViews()
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        holder.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addr.setText(t.url)
        shield.text = "\uD83D\uDEE1 ${t.blocked}"
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
            newTab(homeUrl, false)
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
        newTab(homeUrl, false)
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
            val note = label("Nothing is saved here. Everything is erased when you close these tabs.", 13f, Color.GRAY)
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
        val items = ArrayList<String>()
        items.add(inNew)
        items.add(inBack)
        if (!isPrivate) items.add(inPriv)
        items.add(copy)
        items.add(share)
        AlertDialog.Builder(this)
            .setTitle(if (url.length > 60) url.substring(0, 60) + "..." else url)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    inNew -> newTab(url, false)
                    inBack -> newTab(url, true)
                    inPriv -> openPrivate(url)
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

    // The private browser is a separate window with its own storage. It is erased when you close it.
    private fun openPrivate(url: String?) {
        val i = Intent(this, PrivateActivity::class.java)
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
        return when {
            t.startsWith("https://") -> t
            t.startsWith("http://") -> "https://" + t.substring(7)
            t.contains(" ") || !t.contains(".") -> searchUrl + URLEncoder.encode(t, "UTF-8")
            else -> "https://$t"
        }
    }

    private fun go(input: String) {
        val u = toUrl(input) ?: return
        current?.web?.loadUrl(u)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addr.windowToken, 0)
        current?.web?.requestFocus()
    }

    // ---------- menu ----------

    private fun showMenu() {
        val nNew = "\uFF0B New tab"
        val nPriv = "\uD83D\uDD76 New private tab"
        val nShare = "Share page"
        val nOpen = "Open in another app"
        val nDesk = if (current?.desktop == true) "Use mobile site" else "Use desktop site"
        val nClear = if (isPrivate) "Close all private tabs" else "Clear browsing data"
        val nAbout = "About Nirobo Browser"
        val items = ArrayList<String>()
        items.add(nNew)
        if (!isPrivate) items.add(nPriv)
        items.add(nShare)
        items.add(nOpen)
        items.add(nDesk)
        items.add(nClear)
        items.add(nAbout)
        AlertDialog.Builder(this).setItems(items.toTypedArray()) { _, which ->
            when (items[which]) {
                nNew -> newTab(homeUrl, false)
                nPriv -> openPrivate(null)
                nShare -> {
                    val send = Intent(Intent.ACTION_SEND)
                    send.type = "text/plain"
                    send.putExtra(Intent.EXTRA_TEXT, current?.web?.url ?: homeUrl)
                    startActivity(Intent.createChooser(send, "Share"))
                }
                nOpen -> {
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(current?.web?.url ?: homeUrl))
                    startActivity(Intent.createChooser(view, "Open with"))
                }
                nDesk -> toggleDesktop()
                nClear -> {
                    if (isPrivate) {
                        closeAll()
                    } else {
                        current?.web?.clearCache(true)
                        current?.web?.clearHistory()
                        current?.web?.clearFormData()
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                        Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                nAbout -> {
                    AlertDialog.Builder(this)
                        .setTitle("Nirobo Browser 0.2 (beta)")
                        .setMessage(
                            "A small private browser.\n\n" +
                                "- Nirobo is the home page and search.\n" +
                                "- Ads and trackers are blocked using public lists (" + hosts.size + " sites in the list now).\n" +
                                "- Press and hold a link to open it in a new, background or private tab.\n" +
                                "- Private tabs run in a separate window and are erased when you close them.\n" +
                                "- Only secure (https) pages open. Websites can't use your camera, microphone or location.\n\n" +
                                "Built on Android System WebView, so keep it updated in Play Store."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.show()
    }

    // Desktop mode: a desktop browser name, and a wide page (1024) instead of the phone width.
    private fun toggleDesktop() {
        val t = current ?: return
        t.desktop = !t.desktop
        val s = t.web.settings
        s.userAgentString = if (t.desktop) desktopUa else mobileUa
        s.useWideViewPort = true
        s.loadWithOverviewMode = t.desktop
        t.web.reload()
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

    // First the short list that comes inside the app, then a bigger public list that is downloaded once a week.
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
}

/** The normal browser. */
class MainActivity : BrowserActivity()

/** The private browser: its own window, its own storage, no screenshots, erased when closed. */
class PrivateActivity : BrowserActivity() {
    override val isPrivate: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // must happen before the first page is created: private pages get their own storage folder
        try {
            WebView.setDataDirectorySuffix("private")
        } catch (e: Exception) {
        }
        super.onCreate(savedInstanceState)
    }
}
