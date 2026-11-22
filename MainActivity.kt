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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Filter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupWindow
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
    /** A restored tab waits here and only loads when you open it, to save memory and data. */
    var pending: String? = null
    @Volatile var host: String = ""
}

/** Address bar suggestions: the list is rebuilt for every letter you type. */
private class SuggestAdapter(ctx: Context, private val source: (String) -> List<String>) :
    ArrayAdapter<String>(ctx, android.R.layout.simple_dropdown_item_1line, ArrayList<String>()) {
    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(c: CharSequence?): FilterResults {
            val r = FilterResults()
            val list = if (c.isNullOrBlank()) emptyList() else source(c.toString())
            r.values = list
            r.count = list.size
            return r
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(c: CharSequence?, r: FilterResults) {
            clear()
            addAll((r.values as? List<String>) ?: emptyList())
        }
    }
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

    private val homeUrl: String get() = store.homeUrl
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
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
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
        Downloader.init(this)
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
        // like Chrome: bring back last time's tabs, then open a tapped link on top of them
        val restored = !isPrivate && !isSandbox && restoreSession()
        if (isWebAddress(data) || !restored) newTab(if (isWebAddress(data)) data else startUrl, false)
        handleWidget(intent)
    }

    /** Opens the tabs that were open when the app was last left. Only the tab you were on loads right away. */
    private fun restoreSession(): Boolean {
        if (!store.restoreTabs) return false
        val urls = store.sessionUrls()
        if (urls.isEmpty()) return false
        val keep = store.sessionIndex().coerceIn(0, urls.size - 1)
        var chosen: Tab? = null
        for ((i, u) in urls.withIndex()) {
            val t = createTab()
            tabs.add(t)
            t.url = u
            t.title = u
            t.pending = u
            if (i == keep) chosen = t
        }
        chosen?.let { switchTo(it) }
        updateTabCount()
        return true
    }

    private fun saveSession() {
        if (isPrivate || isSandbox) return
        val urls = tabs.map { it.url }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return
        val c = current
        val pos = if (c == null) -1 else tabs.indexOf(c)
        val idx = if (pos < 0) 0 else tabs.take(pos).count { it.url.isNotEmpty() }
        store.saveSession(urls, idx)
    }

    /** Menu: bring back the tab you closed last. */
    private fun reopenClosedTab() {
        val u = store.popClosed()
        if (u == null) toast("No recently closed tabs") else newTab(u, false)
    }

    /** Matches for the address bar: bookmarks first, then history. Runs off the main thread. */
    private fun suggestionsFor(q: String): List<String> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        fun scan(a: org.json.JSONArray) {
            for (i in 0 until a.length()) {
                if (out.size >= 40) return
                val o = a.optJSONObject(i) ?: continue
                val u = o.optString("u")
                if (u.isEmpty() || u.startsWith("about:")) continue
                if (u.lowercase().contains(s) || o.optString("t").lowercase().contains(s)) out.add(u)
            }
        }
        scan(store.bookmarks())
        scan(store.history())
        fun bare(u: String) = u.removePrefix("https://").removePrefix("http://").lowercase()
        return out.sortedBy { if (bare(it).startsWith(s) || bare(it).startsWith("www.$s")) 0 else 1 }.take(6)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val data = intent?.dataString
        if (isWebAddress(data)) newTab(data, false)
        handleWidget(intent)
    }

    /** A tap on one of the home screen widgets. */
    private fun handleWidget(intent: Intent?) {
        val what = intent?.getStringExtra("nirobo_widget") ?: return
        intent?.removeExtra("nirobo_widget")
        if (what.startsWith("open:")) {
            // a tap on a "Download complete" notification
            openDownload(what.substring(5))
            return
        }
        when (what) {
            "search" -> addr.post {
                addr.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(addr, 0)
            }
            "voice" -> startVoiceSearch()
            "charge" -> current?.let { loadInto(it, "about:phone") }
            "speed" -> current?.let { loadInto(it, "about:internet") }
            "news" -> current?.let { loadInto(it, "about:news") }
            "newtab" -> newTab(startUrl, false)
            "private" -> if (!isPrivate && !isSandbox) openPrivate(null)
            "downloads" -> current?.let { loadInto(it, "about:downloads") }
            "bookmarks" -> current?.let { loadInto(it, "about:bookmarks") }
            "history" -> current?.let { loadInto(it, "about:history") }
        }
    }

    /** Menu: puts this page on the phone's home screen as an icon, like Chrome. */
    private fun addToHomeScreen(t: Tab) {
        val url = t.web.url ?: t.url
        if (url.isEmpty() || url.startsWith("about:") || !url.startsWith("https://")) {
            toast("Only secure web pages can be added")
            return
        }
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            toast("Your home screen does not allow this")
            return
        }
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity::class.java)
        val label = (if (t.title.isEmpty() || t.title == url) hostOf(url) else t.title).take(24)
        val info = android.content.pm.ShortcutInfo.Builder(this, "page_" + url.hashCode())
            .setShortLabel(label)
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, applicationInfo.icon))
            .setIntent(open)
            .build()
        sm.requestPinShortcut(info, null)
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
        topBar = top
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(barColor)
        top.setPadding(dp(8), dp(6), dp(8), dp(6))

        // the address bar suggests bookmarks and history as you type (never in private or sandbox windows)
        val omni = object : AutoCompleteTextView(this@BrowserActivity) {
            // only while you are typing in it, not when a page sets the address
            override fun enoughToFilter(): Boolean = hasFocus() && super.enoughToFilter()
        }
        addr = omni
        omni.threshold = 1
        if (!isPrivate && !isSandbox) {
            val sug = SuggestAdapter(this@BrowserActivity) { suggestionsFor(it) }
            omni.setAdapter(sug)
            omni.setOnItemClickListener { _, _, pos, _ ->
                val picked = sug.getItem(pos)
                if (picked != null) {
                    go(picked)
                    hideKeyboard()
                }
            }
        }
        omniBox = omni
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
        // voice search: tap to speak, long-press to change the language (not in private or sandbox)
        if (!isPrivate && !isSandbox) {
            val mic = label("\uD83C\uDFA4", 18f, Color.WHITE)
            mic.setPadding(dp(10), dp(4), dp(2), dp(4))
            mic.setOnClickListener { startVoiceSearch() }
            mic.setOnLongClickListener {
                val bangla = store.voiceLang != "bn-BD"
                store.voiceLang = if (bangla) "bn-BD" else "en-US"
                toast(if (bangla) "Voice language: Bangla" else "Voice language: English")
                true
            }
            top.addView(mic, LinearLayout.LayoutParams(wrap, wrap))
        }
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
        bottomBar = bottom
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

        applyBarPosition()
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
        s.textZoom = store.textZoom
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, store.thirdCookies && !isPrivate)

        // dark mode for ordinary web pages
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.forceDark = if (isDark()) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }

        // The Bridge is added only while an about: page is showing (see loadInto).
        // A real website must never be able to reach it, or it could read your
        // bookmarks and history.
        w.setOnLongClickListener { v -> onLongPress(v as WebView) }
        // remembers where the finger is (to find the picture or video under a long press)
        // and gives Chrome's pull-down-to-refresh when the page is at the very top
        w.setOnTouchListener { v, e ->
            val web = v as WebView
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = e.x
                    touchY = e.y
                    pullStartY = if (web.scrollY == 0 && fullView == null) e.y else -1f
                    pullStartX = e.x
                }
                MotionEvent.ACTION_MOVE -> {
                    touchX = e.x
                    touchY = e.y
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> pullStartY = -1f
                MotionEvent.ACTION_UP -> {
                    val down = e.y - pullStartY
                    val side = Math.abs(e.x - pullStartX)
                    if (pullStartY >= 0f && down > dp(140) && side < down / 2 &&
                        web.scrollY == 0 && current?.web === web && fullView == null
                    ) {
                        toast("Refreshing…")
                        web.reload()
                    }
                    pullStartY = -1f
                }
            }
            false
        }

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
                    if (scheme == "https") {
                        // layer 5: remove tracking parts (utm_, fbclid, gclid ...) from the link
                        if (store.cleanLinks) {
                            val clean = cleanTracking(u.toString())
                            if (clean != u.toString()) {
                                view.loadUrl(clean)
                                return true
                            }
                        }
                        return false
                    }
                    view.loadUrl(u.buildUpon().scheme("https").build().toString())
                    return true
                }
                if (isSandbox) {
                    toast("Blocked in the sandbox")
                    return true
                }
                if (scheme != "mailto" && scheme != "tel" && scheme != "sms" &&
                    scheme != "smsto" && scheme != "geo" && scheme != "market"
                ) {
                    toast("Blocked a link that tried to open another app")
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
                if (isSandbox && !sameSite(host, t.host)) return blockedResponse(t)
                // local network shield: a website may not poke at your router or other devices
                if (store.localShield && isPrivateHost(host) && !isPrivateHost(t.host)) return blockedResponse(t)
                if (!store.siteShield(host)) return null
                // layer 1: the list of ad and tracker sites
                if (isBlocked(host)) return blockedResponse(t)
                // layer 2: ad addresses, only for files that come from another site
                if (store.adPatterns && !sameSite(host, t.host) &&
                    adPattern.containsMatchIn(request.url.toString().lowercase())
                ) {
                    return blockedResponse(t)
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.blocked = 0
                // password manager fills in only on secure pages, never in private or sandbox windows
                view.importantForAutofill =
                    if (url.startsWith("https://") && !isPrivate && !isSandbox) View.IMPORTANT_FOR_AUTOFILL_YES
                    else View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
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
                injectShieldScripts(view, t)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (t.desktop) view.evaluateJavascript(desktopViewportJs, null)
                t.progress = 100
                if (t === current) {
                    bar.visibility = View.GONE
                    updateChrome(t)
                }
                if (!t.isAbout) {
                    injectShieldScripts(view, t)
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

        // lets the page hand over a blob: file in pieces (needs a secret token, see BlobSaver)
        if (!isSandbox) w.addJavascriptInterface(blobSaver, "NiroboBlob")
        w.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (isSandbox) {
                toast("Downloads are blocked in the sandbox")
                return@setDownloadListener
            }
            when {
                url.startsWith("blob:") -> saveBlob(w, url, contentDisposition, mimetype)
                url.startsWith("data:") -> saveDataLink(url, contentDisposition, mimetype)
                else -> enqueueDownload(url, userAgent, contentDisposition, mimetype, w.url)
            }
        }
        return t
    }

    // ---------- downloads ----------

    private var askedNotification = false

    private val blobSaver by lazy { BlobSaver(applicationContext) { m -> runOnUiThread { toast(m) } } }

    /** Android 9 asks before a file is saved, Android 13 asks once so progress can show as a notification. */
    private fun askStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 702)
            toast("Allow storage, then start the download again")
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotification &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotification = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 701)
        }
        return true
    }

    /** A blob: link only exists inside its page, so the page itself reads it and hands the bytes over. */
    private fun saveBlob(w: WebView, url: String, cd: String?, mime: String?) {
        if (!askStoragePermission()) return
        Downloader.init(this)
        blobSaver.fetch(w, url, parseDisposition(cd) ?: "download", mime ?: "", isPrivate)
        toast("Saving file\u2026")
    }

    /** A data: link holds the whole file inside the address. */
    private fun saveDataLink(url: String, cd: String?, mime: String?) {
        if (!askStoragePermission()) return
        Downloader.init(this)
        val name = parseDisposition(cd) ?: "download"
        if (Downloader.saveDataUrl(url, name, mime ?: "", isPrivate)) toast("Saving $name") else toast("Can't save this file")
    }

    /** Menu: "Download page" keeps the whole page (pictures included) in one .mht file you can open offline. */
    private fun downloadPage(t: Tab) {
        if (!askStoragePermission()) return
        Downloader.init(this)
        val title = if (t.title.isEmpty()) hostOf(t.url) else t.title
        val name = cleanFileName(title.take(60)) + ".mht"
        val tmp = java.io.File(Downloader.tempDir(this), "p" + System.currentTimeMillis() + ".tmp")
        t.web.saveWebArchive(tmp.absolutePath, false) { path ->
            if (path == null) {
                toast("Can't save this page")
            } else {
                Downloader.addLocal(java.io.File(path), name, "multipart/related", isPrivate)
                toast("Saving page")
            }
        }
    }

    /**
     * Starts a download with Nirobo's own downloader (see Downloader.kt). Returns true when it started.
     * [referer] is the page the download came from; many websites refuse a download without it.
     */
    internal fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        referer: String? = null
    ): Boolean {
        if (!url.startsWith("https://")) {
            toast("Blocked: downloads must use a secure (https) link")
            return false
        }
        try {
            // the server's own file name wins, like in Chrome; then the address; every name is made safe
            val name = parseDisposition(contentDisposition)
                ?: cleanFileName(URLUtil.guessFileName(url, contentDisposition, mimetype))
            val lower = name.lowercase()
            val program = listOf(".exe", ".bat", ".cmd", ".msi", ".scr", ".jar", ".vbs", ".ps1", ".sh")
            if (store.dangerFiles && program.any { lower.endsWith(it) }) {
                toast("Blocked: program files can harm your devices")
                return false
            }
            if (!askStoragePermission()) return false
            val ua = userAgent ?: (if (mobileUa.isNotEmpty()) mobileUa else "Mozilla/5.0 (Linux; Android) NiroboBrowser")
            val ext = name.substringAfterLast('.', "").lowercase()
            val type = if (!mimetype.isNullOrEmpty() && mimetype != "application/octet-stream") mimetype
            else (android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: mimetype ?: "")
            val ref = referer ?: current?.web?.url ?: ""
            val go = {
                Downloader.init(this)
                Downloader.enqueue(name, url, ua, ref, type, isPrivate)
                toast("Downloading $name")
            }
            if (lower.endsWith(".apk") || mimetype == "application/vnd.android.package-archive") {
                // like Chrome: an app file is allowed, but only after a warning
                AlertDialog.Builder(this)
                    .setTitle("This file can harm your phone")
                    .setMessage("$name is an app file. Keep it only if you trust this website.")
                    .setPositiveButton("Keep") { _, _ -> go() }
                    .setNegativeButton("Discard", null)
                    .show()
                return true
            }
            go()
            return true
        } catch (e: Exception) {
            toast("Can't download this file")
            return false
        }
    }

    /** What the Downloads page shows: every download with its live state, plus the storage numbers. */
    internal fun downloadsJson(): String {
        Downloader.init(this)
        return Downloader.snapshot(isPrivate)
    }

    internal fun clearDownloadList() {
        Downloader.clearFinished(isPrivate)
    }

    internal fun copyDownloadLink(idText: String) {
        val u = Downloader.find(idText.toLongOrNull() ?: return)?.url ?: return
        if (u.isNotEmpty()) copyText("download link", u, "Link copied")
    }

    internal fun openDownload(idText: String) {
        val id = idText.toLongOrNull() ?: return
        val d = Downloader.find(id) ?: return
        try {
            val uri = Downloader.uriFor(id)
            if (uri == null) {
                toast("The file is not there anymore")
                return
            }
            val mime = if (d.mime.isEmpty()) "*/*" else d.mime
            val i = if (mime == "application/pdf") {
                // PDF files open in Nirobo's own offline reader
                Intent(this, PdfReaderActivity::class.java).setData(uri)
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            toast("No app on your phone can open this file")
        }
    }

    internal fun shareDownload(idText: String) {
        val id = idText.toLongOrNull() ?: return
        val d = Downloader.find(id) ?: return
        val uri = Downloader.uriFor(id)
        if (uri == null) {
            toast("The file is not there anymore")
            return
        }
        try {
            val send = Intent(Intent.ACTION_SEND)
            send.type = if (d.mime.isEmpty()) "*/*" else d.mime
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            toast("Can't share this file")
        }
    }

    /** Cancel, or delete the saved file. */
    internal fun deleteDownload(idText: String) {
        val id = idText.toLongOrNull() ?: return
        Downloader.remove(id, true)
    }

    internal fun pauseDownload(idText: String) {
        Downloader.pause(idText.toLongOrNull() ?: return)
    }

    /** Resume, or retry after a failure: the download continues where it stopped. */
    internal fun retryDownload(idText: String) {
        Downloader.resume(idText.toLongOrNull() ?: return)
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
        // tell the website: please do not track me (Global Privacy Control + Do Not Track)
        t.web.loadUrl(u, mapOf("Sec-GPC" to "1", "DNT" to "1"))
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
        // a restored tab loads the first time you open it
        t.pending?.let {
            t.pending = null
            loadInto(t, it)
        }
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        if (!isPrivate && !isSandbox) store.pushClosed(t.url, t.title)
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
        if (winMode) rebuildTabStrip()
    }

    private fun closeAll() {
        for (t in ArrayList(tabs)) {
            if (!isPrivate && !isSandbox) store.pushClosed(t.url, t.title)
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

    // endings made of two parts: "a.com.bd" and "b.com.bd" are different sites
    private val twoPartEndings = setOf(
        "com.bd", "gov.bd", "edu.bd", "org.bd", "net.bd", "ac.bd", "mil.bd", "info.bd",
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "co.in", "co.jp", "com.br",
        "co.za", "com.pk", "com.np", "com.lk"
    )

    private fun lastTwo(h: String): String {
        val p = h.lowercase().split('.')
        if (p.size <= 2) return h.lowercase()
        val end = p[p.size - 2] + "." + p[p.size - 1]
        return if (end in twoPartEndings) p[p.size - 3] + "." + end else end
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
        syncWindowLayout()
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

    private var touchX = 0f
    private var touchY = 0f
    private var pullStartY = -1f
    private var pullStartX = 0f

    /**
     * Looks at what is under the finger: a video, a sound, a picture (also a CSS background).
     * Players put a see-through layer on top of the video, so videos are also found by position.
     * Calls [done] with (kind, address, poster) or null when there is nothing to save.
     */
    @Suppress("DEPRECATION")
    private fun probeMedia(w: WebView, done: (Triple<String, String, String>?) -> Unit) {
        val sc = if (w.scale > 0f) w.scale else resources.displayMetrics.density
        val x = (touchX / sc).toInt()
        val y = (touchY / sc).toInt()
        val js = "(function(x,y){" +
            "function pick(n){var t=n.tagName;" +
            "if(t=='VIDEO'||t=='AUDIO'){var u=n.currentSrc||n.src||'';" +
            "if(!u){var s=n.querySelector('source');if(s)u=s.src;}" +
            "return JSON.stringify({k:t.toLowerCase(),u:u,p:n.poster||''});}" +
            "if(t=='IMG'){return JSON.stringify({k:'img',u:n.currentSrc||n.src||'',p:''});}" +
            "return null;}" +
            "var el=document.elementFromPoint(x,y);if(!el)return '';" +
            "for(var n=el,i=0;n&&i<6;n=n.parentElement,i++){var r=pick(n);if(r)return r;}" +
            "var vs=document.querySelectorAll('video,audio');" +
            "for(var j=0;j<vs.length;j++){var b=vs[j].getBoundingClientRect();" +
            "if(x>=b.left&&x<=b.right&&y>=b.top&&y<=b.bottom)return pick(vs[j]);}" +
            "var bg=getComputedStyle(el).backgroundImage||'';" +
            "var m=/url\\([\"']?([^\"')]+)/.exec(bg);" +
            "if(m){try{return JSON.stringify({k:'img',u:new URL(m[1],document.baseURI).href,p:''});}catch(e){}}" +
            "return '';})($x,$y)"
        w.evaluateJavascript(js) { raw ->
            var out: Triple<String, String, String>? = null
            try {
                val s = org.json.JSONTokener(raw ?: "").nextValue() as? String
                if (!s.isNullOrEmpty()) {
                    val o = org.json.JSONObject(s)
                    val u = o.optString("u")
                    if (u.isNotEmpty()) out = Triple(o.optString("k"), u, o.optString("p"))
                }
            } catch (e: Exception) {
                // nothing usable under the finger
            }
            done(out)
        }
    }

    /** The menu for a video or a sound. */
    private fun showMediaMenu(kind: String, url: String, poster: String) {
        val word = if (kind == "audio") "audio" else "video"
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(name: String, action: () -> Unit) {
            items.add(name)
            acts.add(action)
        }
        val low = url.substringBefore('#').substringBefore('?').lowercase()
        if (!isSandbox) {
            add("Download $word") {
                when {
                    url.startsWith("blob:") -> current?.web?.let { saveBlob(it, url, null, if (kind == "audio") "audio/mpeg" else "video/mp4") }
                    low.endsWith(".m3u8") || low.endsWith(".mpd") -> toast("Live or streamed $word can't be saved directly")
                    else -> downloadMedia(url, if (kind == "audio") "audio/" else "video/", if (kind == "audio") "audio/mpeg" else "video/mp4")
                }
            }
        }
        if (url.startsWith("https://")) {
            add("Open $word in new tab") { newTab(url, false) }
            add("Copy $word link") { copyText("$word link", url, "Link copied") }
            add("Share $word link") { shareText(url) }
        }
        if (poster.startsWith("https://") && !isSandbox) add("Download cover picture") { downloadImage(poster) }
        if (items.isEmpty()) {
            toast("This $word can't be saved")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (url.length > 60) url.substring(0, 60) + "..." else url)
            .setItems(items.toTypedArray()) { _, which -> acts[which]() }
            .show()
    }

    /** Like downloadImage, but for videos and sounds: the real type is asked from the server when the name does not say. */
    private fun downloadMedia(url: String, typePrefix: String, fallback: String) {
        if (!url.startsWith("https://")) {
            toast("Blocked: downloads must use a secure (https) link")
            return
        }
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val known = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".mp3", ".m4a", ".aac", ".ogg", ".wav", ".opus", ".flac")
            .any { path.endsWith(it) }
        if (known) {
            enqueueDownload(url, null, null, null)
            return
        }
        Thread {
            var type = fallback
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 6000
                c.readTimeout = 6000
                val ct = c.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (c.responseCode == 200 && ct != null && ct.startsWith(typePrefix)) type = ct
                c.disconnect()
            } catch (e: Exception) {
                // keep the guess
            }
            runOnUiThread { enqueueDownload(url, null, null, type) }
        }.start()
    }

    private fun onLongPress(w: WebView): Boolean {
        val hit = w.hitTestResult
        val type = hit.type
        val extra = hit.extra
        // a picture on its own
        if (type == WebView.HitTestResult.IMAGE_TYPE) {
            if (extra != null && extra.startsWith("https://")) {
                showLinkMenu(null, extra)
            } else {
                toast("This picture can't be saved")
            }
            return true
        }
        // a link, or a picture that is also a link
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            val img = if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) extra else null
            val h = Handler(Looper.getMainLooper()) { m ->
                val u = m.data.getString("url")
                if (u != null) showLinkMenu(u, img)
                true
            }
            w.requestFocusNodeHref(h.obtainMessage())
            return true
        }
        if (type == WebView.HitTestResult.UNKNOWN_TYPE) {
            // a video, a sound or a covered picture? then show the save menu
            probeMedia(w) { found ->
                if (found != null && current?.web === w) {
                    if (found.first == "img") showLinkMenu(null, found.second) else showMediaMenu(found.first, found.second, found.third)
                } else {
                    // pressing on text selects it. Only when nothing got selected do we show the page menu.
                    w.postDelayed({
                        w.evaluateJavascript("(window.getSelection()+'').length") { v ->
                            if (v == "0" && current?.web === w) showPageMenu()
                        }
                    }, 300)
                }
            }
        }
        return false
    }

    private fun showPageMenu() {
        val t = current ?: return
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(name: String, action: () -> Unit) {
            items.add(name)
            acts.add(action)
        }
        if (t.web.canGoBack()) add("Back") { t.web.goBack() }
        if (t.web.canGoForward()) add("Forward") { t.web.goForward() }
        add("Reload") { reloadOrStop() }
        if (!t.isAbout) {
            add("Save as PDF or print") { printPage() }
            if (!isSandbox) add("Download page") { downloadPage(t) }
            add("Share") {
                val send = Intent(Intent.ACTION_SEND)
                send.type = "text/plain"
                send.putExtra(Intent.EXTRA_TEXT, t.web.url ?: t.url)
                startActivity(Intent.createChooser(send, "Share"))
            }
            add(if (t.reader) "Exit reading mode" else "Open in reading mode") { toggleReader() }
            if (!isSandbox && t.url.startsWith("https://")) {
                // sends this page's address to Google Translate
                add("Translate to English") {
                    loadInto(t, "https://translate.google.com/translate?sl=auto&tl=en&u=" + URLEncoder.encode(t.url, "UTF-8"))
                }
            }
        }
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which -> acts[which]() }
            .show()
    }

    private fun printPage() {
        val t = current ?: return
        try {
            val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            pm.print("Nirobo page", t.web.createPrintDocumentAdapter("Nirobo page"), null)
        } catch (e: Exception) {
            toast("Can't print this page")
        }
    }

    internal fun applyCookiePolicy() {
        for (x in tabs) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(x.web, store.thirdCookies && !isPrivate)
        }
    }

    private fun copyText(label: String, text: String, message: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share"))
    }

    /** Saves a picture. If the address has no file ending, the server is asked what kind of picture it is. */
    private fun downloadImage(url: String) {
        if (!url.startsWith("https://")) {
            toast("This picture can't be downloaded")
            return
        }
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val known = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp").any { path.endsWith(it) }
        if (known) {
            enqueueDownload(url, null, null, null)
            return
        }
        Thread {
            var type = "image/jpeg"
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 6000
                c.readTimeout = 6000
                val ct = c.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (c.responseCode == 200 && ct != null && ct.startsWith("image/")) type = ct
                c.disconnect()
            } catch (e: Exception) {
                // keep the guess
            }
            runOnUiThread { enqueueDownload(url, null, null, type) }
        }.start()
    }

    private fun showLinkMenu(url: String?, image: String? = null) {
        // only secure pictures can be saved
        val img = if (image != null && image.startsWith("https://")) image else null
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(name: String, action: () -> Unit) {
            items.add(name)
            acts.add(action)
        }
        if (img != null) {
            if (!isSandbox) add("Download image") { downloadImage(img) }
            add("Open image in new tab") { newTab(img, false) }
            add("Copy image link") { copyText("image link", img, "Image link copied") }
            add("Share image link") { shareText(img) }
            // sends the picture's address to Google, so it is only offered in normal windows
            if (!isPrivate && !isSandbox) {
                add("Search this image (Google Lens)") {
                    newTab("https://lens.google.com/uploadbyurl?url=" + URLEncoder.encode(img, "UTF-8"), false)
                }
            }
        }
        if (url != null) {
            add("Open in new tab") { newTab(url, false) }
            add("Open in background tab") { newTab(url, true) }
            if (!isPrivate) add("Open in private tab") { openPrivate(url) }
            if (!isSandbox) add("Open in sandbox") { openSandbox(url) }
            if (!isSandbox) add("Download link") { enqueueDownload(url, null, null, null) }
            add("Copy link") { copyText("link", url, "Link copied") }
            add("Share link") { shareText(url) }
        }
        val shown = url ?: img ?: return
        AlertDialog.Builder(this)
            .setTitle(if (shown.length > 60) shown.substring(0, 60) + "..." else shown)
            .setItems(items.toTypedArray()) { _, which -> acts[which]() }
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
            t.contains(" ") || !t.contains(".") -> searchLink(t)
            else -> "https://$t"
        }
    }

    /** Builds the search address. A custom engine may have %s in the middle. */
    private fun searchLink(words: String): String {
        val q = URLEncoder.encode(words, "UTF-8")
        val s = store.searchUrl
        return if (s.contains("%s")) s.replace("%s", q) else s + q
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

    private var omniBox: AutoCompleteTextView? = null

    private fun hideKeyboard() {
        omniBox?.dismissDropDown()
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

    private var menuPopup: PopupWindow? = null

    private fun showMenu() {
        val t = current ?: return
        menuPopup?.dismiss()
        val dark = isDark()
        val ink = Color.parseColor(if (dark) "#E8EAED" else "#202124")
        val soft = Color.parseColor(if (dark) "#9AA0A6" else "#5F6368")
        val line = Color.parseColor(if (dark) "#3C4043" else "#E3E6E8")
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val ref = arrayOfNulls<PopupWindow>(1)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor(if (dark) "#2B2D31" else "#FFFFFF"))
        bg.cornerRadius = dp(18).toFloat()
        bg.setStroke(dp(1), line)
        box.background = bg
        box.setPadding(0, dp(6), 0, dp(6))

        fun divider() {
            val v = View(this)
            v.setBackgroundColor(line)
            val lp = LinearLayout.LayoutParams(match, dp(1))
            lp.setMargins(dp(14), dp(6), dp(14), dp(6))
            box.addView(v, lp)
        }

        fun item(glyph: String, name: String, trailing: String, action: () -> Unit) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(18), 0, dp(18), 0)
            val gl = label(glyph, 18f, soft)
            gl.gravity = Gravity.CENTER
            row.addView(gl, LinearLayout.LayoutParams(dp(30), dp(48)))
            val nm = label(name, 16f, ink)
            nm.setPadding(dp(14), 0, 0, 0)
            row.addView(nm, LinearLayout.LayoutParams(0, wrap, 1f))
            if (trailing.isNotEmpty()) {
                row.addView(label(trailing, 18f, Color.parseColor("#1A73E8")), LinearLayout.LayoutParams(wrap, wrap))
            }
            row.setOnClickListener {
                ref[0]?.dismiss()
                action()
            }
            box.addView(row, LinearLayout.LayoutParams(match, wrap))
        }

        // top icon row: forward, bookmark, downloads, info, reload
        val icons = LinearLayout(this)
        icons.orientation = LinearLayout.HORIZONTAL
        icons.setPadding(dp(8), dp(2), dp(8), dp(2))
        fun icon(symbol: String, action: () -> Unit) {
            val v = label(symbol, 22f, ink)
            v.gravity = Gravity.CENTER
            v.setPadding(0, dp(10), 0, dp(10))
            v.setOnClickListener {
                ref[0]?.dismiss()
                action()
            }
            icons.addView(v, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        icon("\u25B6") { if (t.web.canGoForward()) t.web.goForward() }
        if (!isPrivate) {
            icon(if (store.isBookmarked(t.url)) "\u2605" else "\u2606") { toggleBookmark(t) }
            icon("\u2193") { loadInto(t, "about:downloads") }
        }
        icon("\u24D8") { showSiteInfo() }
        icon("\u21BB") { reloadOrStop() }
        box.addView(icons, LinearLayout.LayoutParams(match, wrap))
        divider()

        item("\uFF0B", "New tab", "") { newTab(startUrl, false) }
        if (!isPrivate && !isSandbox) item("\u21BA", "Reopen closed tab", "") { reopenClosedTab() }
        if (!isPrivate) item("\uD83D\uDD76", "New private tab", "") { openPrivate(null) }
        divider()
        if (!isPrivate) {
            item("\u2193", "Downloads", "") { loadInto(t, "about:downloads") }
            item("\u2605", "Bookmarks", "") { loadInto(t, "about:bookmarks") }
            item("\uD83D\uDD52", "History", "") { loadInto(t, "about:history") }
            divider()
        }
        item("\uD83D\uDCE4", "Share\u2026", "") {
            val send = Intent(Intent.ACTION_SEND)
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, current?.web?.url ?: homeUrl)
            startActivity(Intent.createChooser(send, "Share"))
        }
        if (!isPrivate && !isSandbox) item("\uD83D\uDCCC", "Add to Home screen", "") { addToHomeScreen(t) }
        item("\uD83D\uDD0D", "Find in page", "") { openFind() }
        item("\uD83D\uDCD6", if (t.reader) "Exit reading mode" else "Show reading mode", "") { toggleReader() }
        item("\uD83D\uDDA5", "Desktop site", if (t.desktop) "\u2611" else "\u2610") { toggleDesktop() }
        if (!isSandbox) {
            item("\u2197", "Open in another app", "") {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(current?.web?.url ?: homeUrl))
                startActivity(Intent.createChooser(view, "Open with"))
            }
        }
        if (isSandbox) {
            item("\u26A0", "Scripts", if (scriptsOn) "ON" else "OFF") { toggleSandboxScripts() }
        }
        divider()
        if (!isPrivate) item("\u2699", "Settings", "") { loadInto(t, "about:settings") }
        if (!isPrivate) item("\uD83D\uDD04", "Check for updates", "") { Updater.check(this, true) }
        item(
            "\uD83D\uDDD1",
            if (isSandbox) "Close the sandbox" else if (isPrivate) "Close all private tabs" else "Clear browsing data",
            ""
        ) { clearData() }
        if (!isSandbox) item("\u24D8", "About Nirobo Browser", "") { loadInto(t, "about:version") }

        val scroll = ScrollView(this)
        scroll.addView(box, FrameLayout.LayoutParams(match, wrap))
        val width = dp(270)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxH = (resources.displayMetrics.heightPixels * 0.72).toInt()
        val pw = PopupWindow(scroll, width, minOf(box.measuredHeight, maxH), true)
        ref[0] = pw
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        menuPopup = pw
        val lift = bottomBar.height + (if (store.barBottom) topBar.height else 0) + dp(6)
        pw.showAtLocation(root, Gravity.BOTTOM or Gravity.END, dp(8), lift)
    }

    // ---------- Windows-style desktop layout: tab strip on top, quick buttons on the right ----------

    private var winMode = false
    private var lastTabSig = ""
    private var tabStrip: android.widget.HorizontalScrollView? = null
    private var tabRow: LinearLayout? = null
    private var rail: LinearLayout? = null
    private var winBody: LinearLayout? = null

    private fun ensureWindowViews() {
        if (winBody != null) return
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val sv = android.widget.HorizontalScrollView(this)
        sv.isHorizontalScrollBarEnabled = false
        sv.setBackgroundColor(Color.parseColor("#202124"))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.BOTTOM
        row.setPadding(dp(6), dp(6), dp(6), 0)
        sv.addView(row, FrameLayout.LayoutParams(wrap, wrap))
        tabStrip = sv
        tabRow = row

        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.gravity = Gravity.CENTER_HORIZONTAL
        r.setBackgroundColor(Color.parseColor("#F1F4F2"))
        r.setPadding(0, dp(6), 0, dp(6))
        fun railBtn(symbol: String, action: () -> Unit) {
            val b = label(symbol, 20f, barColor)
            b.gravity = Gravity.CENTER
            b.setOnClickListener { action() }
            r.addView(b, LinearLayout.LayoutParams(dp(52), dp(48)))
        }
        railBtn("\u25C0") { current?.web?.let { if (it.canGoBack()) it.goBack() } }
        railBtn("\u25B6") { current?.web?.let { if (it.canGoForward()) it.goForward() } }
        railBtn("\u2606") { current?.let { loadInto(it, "about:bookmarks") } }
        railBtn("\uD83D\uDD52") { current?.let { loadInto(it, "about:history") } }
        railBtn("\u2B07") { current?.let { loadInto(it, "about:downloads") } }
        railBtn("\uD83D\uDEE1") { showShieldMenu() }
        railBtn("\u2699") { current?.let { loadInto(it, "about:settings") } }
        railBtn("\uD83D\uDCF1") { toggleDesktop() }
        railBtn("\u22EE") { showMenu() }
        rail = r

        val body = LinearLayout(this)
        body.orientation = LinearLayout.HORIZONTAL
        winBody = body
    }

    private fun tabSignature(): String {
        val sb = StringBuilder()
        for (x in tabs) {
            sb.append(if (x === current) "*" else "-").append(x.title).append('~').append(x.url).append('|')
        }
        return sb.toString()
    }

    private fun rebuildTabStrip() {
        val row = tabRow ?: return
        lastTabSig = tabSignature()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        row.removeAllViews()
        for (x in tabs) {
            val selected = x === current
            val chip = LinearLayout(this)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor(if (selected) "#3C4043" else "#2B2C30"))
            val r = dp(10).toFloat()
            bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            chip.background = bg
            chip.setPadding(dp(12), dp(8), dp(4), dp(8))
            val name = if (x.title.isNotEmpty()) x.title else if (x.url.isNotEmpty()) x.url else "New tab"
            val title = label(name, 13f, if (selected) Color.WHITE else Color.parseColor("#B0B3B8"))
            title.setSingleLine(true)
            title.ellipsize = TextUtils.TruncateAt.END
            title.maxWidth = dp(150)
            chip.addView(title, LinearLayout.LayoutParams(wrap, wrap))
            val close = label("\u2715", 13f, Color.parseColor("#B0B3B8"))
            close.setPadding(dp(8), dp(2), dp(8), dp(2))
            close.setOnClickListener { closeTab(x) }
            chip.addView(close, LinearLayout.LayoutParams(wrap, wrap))
            chip.setOnClickListener { switchTo(x) }
            val lp = LinearLayout.LayoutParams(wrap, wrap)
            lp.rightMargin = dp(2)
            row.addView(chip, lp)
        }
        val plus = label("\uFF0B", 18f, Color.parseColor("#B0B3B8"))
        plus.setPadding(dp(12), dp(4), dp(12), dp(6))
        plus.setOnClickListener { newTab(startUrl, false) }
        row.addView(plus, LinearLayout.LayoutParams(wrap, wrap))
    }

    /** Switches between the phone layout and the Windows-style layout when desktop mode changes. */
    private fun syncWindowLayout() {
        if (!::bottomBar.isInitialized) return
        val want = current?.desktop == true
        if (want != winMode) {
            winMode = want
            applyBarPosition()
        } else if (winMode && tabSignature() != lastTabSig) {
            rebuildTabStrip()
        }
    }

    /** Puts the address bar on top or at the bottom (Settings, Address bar). */
    internal fun applyBarPosition() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        (holder.parent as? ViewGroup)?.removeView(holder)
        page.removeAllViews()
        if (winMode) {
            ensureWindowViews()
            val body = winBody!!
            body.removeAllViews()
            body.addView(holder, LinearLayout.LayoutParams(0, match, 1f))
            body.addView(rail!!, LinearLayout.LayoutParams(dp(52), match))
            page.addView(tabStrip!!, LinearLayout.LayoutParams(match, wrap))
            page.addView(topBar, LinearLayout.LayoutParams(match, wrap))
            page.addView(bar, LinearLayout.LayoutParams(match, dp(3)))
            page.addView(findBar, LinearLayout.LayoutParams(match, wrap))
            page.addView(body, LinearLayout.LayoutParams(match, 0, 1f))
            rebuildTabStrip()
            return
        }
        if (store.barBottom) {
            page.addView(bar, LinearLayout.LayoutParams(match, dp(3)))
            page.addView(holder, LinearLayout.LayoutParams(match, 0, 1f))
            page.addView(findBar, LinearLayout.LayoutParams(match, wrap))
            page.addView(topBar, LinearLayout.LayoutParams(match, wrap))
            page.addView(bottomBar, LinearLayout.LayoutParams(match, wrap))
        } else {
            page.addView(topBar, LinearLayout.LayoutParams(match, wrap))
            page.addView(bar, LinearLayout.LayoutParams(match, dp(3)))
            page.addView(findBar, LinearLayout.LayoutParams(match, wrap))
            page.addView(holder, LinearLayout.LayoutParams(match, 0, 1f))
            page.addView(bottomBar, LinearLayout.LayoutParams(match, wrap))
        }
    }

    /** Text size of web pages (Settings, Accessibility). */
    internal fun applyTextZoom() {
        for (x in tabs) x.web.settings.textZoom = store.textZoom
    }

    internal fun clearBrowsingData() {
        clearData()
    }

    internal fun blocklistSize(): Int = hosts.size

    /** "on", "off" or "unsupported": is a password manager chosen in Android's settings? */
    internal fun autofillState(): String {
        return try {
            val am = getSystemService(android.view.autofill.AutofillManager::class.java)
            if (am == null || !am.isAutofillSupported) "unsupported"
            else if (am.hasEnabledAutofillServices()) "on"
            else "off"
        } catch (e: Exception) {
            "unsupported"
        }
    }

    internal fun hostBlocked(host: String): Boolean = isBlocked(host)

    internal fun clearCookiesNow() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        toast("Cookies cleared")
    }

    internal fun clearCacheNow() {
        current?.web?.clearCache(true)
        toast("Cache cleared")
    }

    internal fun clearSiteDataNow() {
        WebStorage.getInstance().deleteAllData()
        android.webkit.GeolocationPermissions.getInstance().clearAll()
        toast("Site data cleared")
    }

    /** Opens a page of Android's own settings (default browser, notifications). */
    internal fun openSystemSetting(what: String) {
        try {
            val i = when (what) {
                "default", "autofill" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                else -> return
            }
            startActivity(i)
        } catch (e: Exception) {
            toast("Can't open this setting on your phone")
        }
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
        store.clearClosed()
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
        syncWindowLayout()
    }

    // ---------- ad and tracker blocking ----------

    // ---------- protection layers ----------

    private val adPattern = Regex("(/ads?/|/adserver|/adframe|/adview|/banners?/|pagead|adsbygoogle|doubleclick|googlesyndication|/prebid|adservice|/advert)")

    private val trackParams = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "yclid", "igshid", "mc_eid", "mc_cid",
        "_hsenc", "_hsmi", "ref_src", "twclid", "wbraid", "gbraid"
    )

    private fun blockedResponse(t: Tab): WebResourceResponse {
        t.blocked++
        if (t === current) runOnUiThread { shield.text = "\uD83D\uDEE1 ${t.blocked}" }
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    /** Addresses that only exist inside your home or on your own phone. */
    private fun isPrivateHost(h: String): Boolean {
        val x = h.lowercase()
        if (x == "localhost" || x == "::1" || x.endsWith(".local") || x.endsWith(".localhost") || x.endsWith(".internal")) return true
        val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\$").matchEntire(x) ?: return false
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** Takes tracking parts out of a link: utm_*, fbclid, gclid ... */
    private fun cleanTracking(url: String): String {
        return try {
            val u = Uri.parse(url)
            val names = u.queryParameterNames
            if (names.none { it.lowercase() in trackParams || it.lowercase().startsWith("utm_") }) return url
            val b = u.buildUpon().clearQuery()
            for (n in names) {
                val l = n.lowercase()
                if (l in trackParams || l.startsWith("utm_")) continue
                for (v in u.getQueryParameters(n)) b.appendQueryParameter(n, v)
            }
            b.build().toString()
        } catch (e: Exception) {
            url
        }
    }

    /** Layer 3 hides ad boxes, layer 4 stops pop-ups that nobody asked for. */
    private fun injectShieldScripts(view: WebView, t: Tab) {
        if (t.isAbout) return
        if (store.siteShield(t.host) && store.adHide) view.evaluateJavascript(AD_HIDE_JS, null)
        if (store.popupBlock) view.evaluateJavascript(POPUP_JS, null)
    }

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
        if (h == "pages.dev" || h == "nirobo.pages.dev" || h.endsWith(".nirobo.pages.dev")) return
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
                        val buf = java.io.ByteArrayOutputStream()
                        c.inputStream.use { ins ->
                            val chunk = ByteArray(8192)
                            while (buf.size() < 3_000_000) {
                                val n = ins.read(chunk)
                                if (n < 0) break
                                buf.write(chunk, 0, n)
                            }
                        }
                        val text = buf.toString("UTF-8")
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

    /** Opens Android's own voice screen. The phone asks for the microphone itself, and Nirobo never records you. */
    private fun startVoiceSearch() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(
            android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, store.voiceLang)
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(
            android.speech.RecognizerIntent.EXTRA_PROMPT,
            if (store.voiceLang == "bn-BD") "Speak to search (Bangla)" else "Speak to search"
        )
        try {
            startActivityForResult(i, 7001)
        } catch (e: Exception) {
            toast("Voice search is not available on this phone")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 7001) {
            if (resultCode == RESULT_OK && data != null) {
                val said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()
                if (!said.isNullOrEmpty()) go(said)
            }
            return
        }
        if (requestCode == 1) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onPause() {
        if (!isPrivate) CookieManager.getInstance().flush()
        saveSession()
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
        private const val AD_HIDE_JS = """
        (function(){
          if(window.__nirobo_adhide)return; window.__nirobo_adhide=1;
          var css='ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],[class*="adsbygoogle"],'+
            'iframe[src*="doubleclick"],iframe[src*="googlesyndication"],iframe[src*="adservice"],'+
            '[data-ad-slot],[data-ad-client],[aria-label="Advertisement"],[class~="ad-banner"],'+
            '[class~="ad-container"],[class~="ad-slot"],[class~="advert"],[class~="advertisement"],'+
            '[id~="ad-banner"],[id~="ad-container"],.sponsored-ad,.sticky-ad,.ad-placeholder'+
            '{display:none!important}';
          var s=document.createElement('style');s.textContent=css;
          (document.head||document.documentElement).appendChild(s);
        })();
        """

        private const val POPUP_JS = """
        (function(){
          if(window.__nirobo_popup)return; window.__nirobo_popup=1;
          var last=0;
          function mark(){last=Date.now()}
          ['touchstart','click','keydown'].forEach(function(e){document.addEventListener(e,mark,true)});
          var real=window.open;
          window.open=function(){
            if(Date.now()-last<1200)return real.apply(window,arguments);
            return null;
          };
        })();
        """

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
