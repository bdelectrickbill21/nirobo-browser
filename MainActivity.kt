package org.nirobo.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Nirobo Browser: a small, private browser.
 * - Nirobo is the home page and the search engine.
 * - Ads and trackers are blocked by a public list of domains.
 * - No history is kept. Only secure (https) pages are opened.
 */
class MainActivity : Activity() {

    private val homeUrl = "https://nirobo.pages.dev/"
    private val searchUrl = "https://nirobo.pages.dev/?q="
    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var blocked = 0
    private var desktop = false
    private var mobileUa = ""

    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var web: WebView
    private lateinit var addr: EditText
    private lateinit var shield: TextView
    private lateinit var bar: ProgressBar

    private var fullView: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0E3B2C")
        buildUi()
        loadBlocklist()
        handleIntent(intent, true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent, false)
    }

    private fun handleIntent(i: Intent?, first: Boolean) {
        val data = i?.dataString
        if (data != null && (data.startsWith("http://") || data.startsWith("https://"))) {
            go(data)
        } else if (first) {
            web.loadUrl(homeUrl)
        }
    }

    // ---------- the screen ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val green = Color.parseColor("#0E3B2C")
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        root = FrameLayout(this)
        page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL

        // top bar: address box + shield counter
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(green)
        top.setPadding(dp(8), dp(6), dp(8), dp(6))

        addr = EditText(this)
        addr.setSingleLine(true)
        addr.hint = "Search or type address"
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

        shield = TextView(this)
        shield.text = "\uD83D\uDEE1 0"
        shield.setTextColor(Color.WHITE)
        shield.textSize = 14f
        shield.setPadding(dp(10), dp(4), dp(4), dp(4))
        shield.setOnClickListener {
            Toast.makeText(this, "Blocked $blocked ads and trackers on this page", Toast.LENGTH_SHORT).show()
        }
        top.addView(shield, LinearLayout.LayoutParams(wrap, wrap))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.visibility = View.GONE

        // the page itself
        web = WebView(this)
        setupWebView()

        // bottom bar
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.setBackgroundColor(Color.parseColor("#F1F4F2"))
        fun button(label: String, action: () -> Unit) {
            val t = TextView(this)
            t.text = label
            t.textSize = 13f
            t.setTextColor(green)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(14), 0, dp(14))
            t.setOnClickListener { action() }
            bottom.addView(t, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        button("Back") { if (web.canGoBack()) web.goBack() }
        button("Forward") { if (web.canGoForward()) web.goForward() }
        button("Home") { go(homeUrl) }
        button("Reload") { web.reload() }
        button("Menu") { showMenu() }

        page.addView(top, LinearLayout.LayoutParams(match, wrap))
        page.addView(bar, LinearLayout.LayoutParams(match, dp(3)))
        page.addView(web, LinearLayout.LayoutParams(match, 0, 1f))
        page.addView(bottom, LinearLayout.LayoutParams(match, wrap))
        root.addView(page, FrameLayout.LayoutParams(match, match))
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.javaScriptCanOpenWindowsAutomatically = false   // no pop-up windows
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setGeolocationEnabled(false)
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = true
        mobileUa = s.userAgentString.replace("; wv", "")
        s.userAgentString = mobileUa
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.webViewClient = object : WebViewClient() {
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
                    blocked++
                    runOnUiThread { shield.text = "\uD83D\uDEE1 $blocked" }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                blocked = 0
                shield.text = "\uD83D\uDEE1 0"
                if (!addr.hasFocus()) addr.setText(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                bar.visibility = View.GONE
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

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
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
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
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

        web.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
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
                    Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Can't download this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- opening pages ----------

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("https://") -> t
            t.startsWith("http://") -> "https://" + t.substring(7)
            t.contains(" ") || !t.contains(".") -> searchUrl + URLEncoder.encode(t, "UTF-8")
            else -> "https://$t"
        }
        web.loadUrl(url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addr.windowToken, 0)
        web.requestFocus()
    }

    // ---------- menu ----------

    private fun showMenu() {
        val items = arrayOf(
            "Share page",
            "Open in another app",
            if (desktop) "Use mobile site" else "Use desktop site",
            "Clear browsing data",
            "About Nirobo Browser"
        )
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val send = Intent(Intent.ACTION_SEND)
                    send.type = "text/plain"
                    send.putExtra(Intent.EXTRA_TEXT, web.url ?: homeUrl)
                    startActivity(Intent.createChooser(send, "Share"))
                }
                1 -> {
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(web.url ?: homeUrl))
                    startActivity(Intent.createChooser(view, "Open with"))
                }
                2 -> {
                    desktop = !desktop
                    val s = web.settings
                    s.userAgentString = if (desktop) desktopUa else mobileUa
                    s.useWideViewPort = desktop
                    s.loadWithOverviewMode = desktop
                    web.reload()
                }
                3 -> {
                    web.clearCache(true)
                    web.clearHistory()
                    web.clearFormData()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
                }
                4 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Nirobo Browser 0.1 (beta)")
                        .setMessage(
                            "A small private browser.\n\n" +
                                "- Nirobo is the home page and search.\n" +
                                "- Ads and trackers are blocked using public lists (" + hosts.size + " sites in the list now).\n" +
                                "- No history is kept, and only secure (https) pages open.\n" +
                                "- Websites can't use your camera, microphone or location.\n\n" +
                                "Built on Android System WebView, so keep it updated in Play Store."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.show()
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
            if (old) {
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
        if (fullView != null) {
            exitFullscreen()
        } else if (web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
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
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
