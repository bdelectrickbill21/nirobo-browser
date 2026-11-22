package org.nirobo.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Decides how a downloaded file is opened: inside Nirobo when it can, else in another app. */
internal object FileOpener {

    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val videoExt = setOf("mp4", "m4v", "3gp", "webm", "mkv", "mov")
    private val audioExt = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac")
    private val htmlExt = setOf("html", "htm", "xhtml")
    private val textExt = setOf(
        "txt", "xml", "json", "csv", "tsv", "log", "md", "css", "js", "ini", "conf", "yml", "yaml",
        "kt", "java", "py", "c", "h", "cpp", "sh", "bat", "svg", "srt", "vtt", "gpx", "kml"
    )

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String, mime: String): String {
        val ext = extOf(name)
        val m = mime.lowercase()
        return when {
            ext == "pdf" || m == "application/pdf" -> "pdf"
            ext == "apk" || m == "application/vnd.android.package-archive" -> "apk"
            ext in imageExt -> "image"
            ext in videoExt -> "video"
            ext in audioExt -> "audio"
            ext in htmlExt -> "html"
            ext in textExt -> "text"
            m.startsWith("image/") && m != "image/svg+xml" -> "image"
            m.startsWith("video/") -> "video"
            m.startsWith("audio/") -> "audio"
            m == "text/html" -> "html"
            m.startsWith("text/") || m == "application/json" || m == "application/xml" -> "text"
            else -> "other"
        }
    }

    private fun typeFor(name: String, mime: String): String {
        if (mime.isNotEmpty() && mime != "application/octet-stream") return mime
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name)) ?: "*/*"
    }

    /** Opens a finished download. [say] shows a short message to the person. */
    fun open(act: Activity, uri: Uri, name: String, mime: String, say: (String) -> Unit) {
        // is the file really there and readable?
        try {
            act.contentResolver.openInputStream(uri)?.close()
        } catch (e: java.io.FileNotFoundException) {
            say("The file is not there anymore. Download it again.")
            return
        } catch (e: Exception) {
            say("Can't read the file: " + (e.message ?: e.javaClass.simpleName))
            return
        }
        try {
            when (val kind = kindOf(name, mime)) {
                "pdf" -> {
                    val i = Intent(act, PdfReaderActivity::class.java).setData(uri)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    act.startActivity(i)
                }
                "apk" -> {
                    if (!act.packageManager.canRequestPackageInstalls()) {
                        say("Allow Nirobo to install apps, then tap Open again.")
                        act.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
                        )
                    } else {
                        external(act, uri, name, "application/vnd.android.package-archive", say)
                    }
                }
                "image", "video", "audio", "html", "text" -> {
                    val i = Intent(act, FileViewerActivity::class.java).setData(uri)
                    i.putExtra("name", name)
                    i.putExtra("mime", mime)
                    i.putExtra("kind", kind)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    act.startActivity(i)
                }
                else -> external(act, uri, name, mime, say)
            }
        } catch (e: Exception) {
            say("Can't open this file: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Hands the file to another app. */
    fun external(act: Activity, uri: Uri, name: String, mime: String, say: (String) -> Unit) {
        if (uri.scheme == "file") {
            say("Open it from your Files app, in the Downloads folder.")
            return
        }
        val first = Intent(Intent.ACTION_VIEW)
        first.setDataAndType(uri, typeFor(name, mime))
        first.clipData = ClipData.newRawUri("", uri)
        first.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            act.startActivity(first)
            return
        } catch (e: ActivityNotFoundException) {
            // try again with any app
        } catch (e: Exception) {
            say("Can't open this file: " + (e.message ?: e.javaClass.simpleName))
            return
        }
        try {
            val any = Intent(Intent.ACTION_VIEW)
            any.setDataAndType(uri, "*/*")
            any.clipData = ClipData.newRawUri("", uri)
            any.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(any, "Open with"))
        } catch (e: Exception) {
            val ext = extOf(name)
            say("No app on your phone can open ." + (if (ext.isEmpty()) "this type of file" else ext + " files"))
        }
    }

    fun share(act: Activity, uri: Uri, name: String, mime: String, say: (String) -> Unit) {
        try {
            val send = Intent(Intent.ACTION_SEND)
            send.type = typeFor(name, mime)
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri("", uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            say("Can't share this file")
        }
    }
}

/** Shows images, video, audio, text and web files inside Nirobo. */
internal class FileViewerActivity : Activity() {

    private lateinit var uri: Uri
    private var name = "File"
    private var mime = ""
    private var kind = "text"
    private var video: VideoView? = null
    private var d = 1f

    private fun dp(x: Int): Int = (x * d).toInt()

    private fun say(m: String) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun label(text: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        uri = data
        name = intent.getStringExtra("name") ?: "File"
        mime = intent.getStringExtra("mime") ?: ""
        kind = intent.getStringExtra("kind") ?: FileOpener.kindOf(name, mime)
        window.statusBarColor = Color.parseColor("#0E3B2C")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0B0F0D"))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#0E3B2C"))
        bar.setPadding(dp(4), dp(6), dp(4), dp(6))
        val back = label("\u2190", 22f, Color.WHITE)
        back.setPadding(dp(14), dp(6), dp(14), dp(6))
        back.setOnClickListener { finish() }
        bar.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val title = label(name, 16f, Color.WHITE)
        title.gravity = Gravity.CENTER_VERTICAL
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.MIDDLE
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val openWith = label("Open with", 14f, Color.WHITE)
        openWith.setPadding(dp(10), dp(8), dp(10), dp(8))
        openWith.setOnClickListener { FileOpener.external(this, uri, name, mime) { m -> say(m) } }
        bar.addView(openWith, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val share = label("Share", 14f, Color.WHITE)
        share.setPadding(dp(10), dp(8), dp(10), dp(8))
        share.setOnClickListener { FileOpener.share(this, uri, name, mime) { m -> say(m) } }
        bar.addView(share, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        when (kind) {
            "image" -> showImage(body)
            "video", "audio" -> showMedia(body)
            "html" -> showHtml(body, bar)
            else -> showText(body)
        }
    }

    private fun fail(body: FrameLayout, msg: String) {
        body.removeAllViews()
        val t = label(msg, 16f, Color.WHITE)
        t.setPadding(dp(24), dp(24), dp(24), dp(24))
        body.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------- pictures ----------

    private fun showImage(body: FrameLayout) {
        Thread {
            var bmp: Bitmap? = null
            try {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var sample = 1
                val longest = maxOf(o.outWidth, o.outHeight)
                while (longest / sample > 4096) sample *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = sample
                bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            } catch (e: Throwable) {
                // shown below
            }
            runOnUiThread {
                val b = bmp
                if (b == null) {
                    fail(body, "This picture can't be shown here. Tap Open with.")
                } else {
                    val z = ZoomImageView(this)
                    z.setBackgroundColor(Color.BLACK)
                    z.setImageBitmap(b)
                    body.addView(z, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }
        }.start()
    }

    // ---------- video and audio ----------

    private fun showMedia(body: FrameLayout) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = VideoView(this)
        video = v
        val mc = MediaController(this)
        mc.setAnchorView(v)
        v.setMediaController(mc)
        v.setOnPreparedListener { it.start() }
        v.setOnErrorListener { _, _, _ ->
            fail(body, "This file can't be played here. Tap Open with.")
            true
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.gravity = Gravity.CENTER
        body.addView(v, lp)
        if (kind == "audio") {
            val t = label("\uD83C\uDFB5  " + name, 18f, Color.WHITE)
            t.setPadding(dp(24), dp(24), dp(24), dp(24))
            body.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        try {
            v.setVideoURI(uri)
        } catch (e: Exception) {
            fail(body, "This file can't be played here. Tap Open with.")
        }
    }

    override fun onPause() {
        video?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        video?.stopPlayback()
        super.onDestroy()
    }

    // ---------- text, xml, json, csv, html ----------

    private fun readText(): String? {
        return try {
            val buf = ByteArrayOutputStream()
            contentResolver.openInputStream(uri)?.use { ins ->
                val chunk = ByteArray(8192)
                while (buf.size() < 1_500_000) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
            }
            var s = String(buf.toByteArray(), Charsets.UTF_8)
            if (FileOpener.extOf(name) == "json") {
                try {
                    val t = s.trim()
                    s = if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
                } catch (e: Exception) {
                    // show as it is
                }
            }
            if (buf.size() >= 1_500_000) s += "\n\n... (the file is bigger; only the first part is shown)"
            s
        } catch (e: Throwable) {
            null
        }
    }

    private fun sourceView(text: String): View {
        val sv = ScrollView(this)
        val t = TextView(this)
        t.text = text
        t.typeface = Typeface.MONOSPACE
        t.textSize = 13f
        t.setTextColor(Color.parseColor("#E4ECE8"))
        t.setPadding(dp(14), dp(12), dp(14), dp(24))
        t.setTextIsSelectable(true)
        sv.addView(t, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sv
    }

    private fun showText(body: FrameLayout) {
        val s = readText()
        if (s == null) {
            fail(body, "This file can't be read here. Tap Open with.")
            return
        }
        body.addView(sourceView(s), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showHtml(body: FrameLayout, bar: LinearLayout) {
        val s = readText()
        if (s == null) {
            fail(body, "This file can't be read here. Tap Open with.")
            return
        }
        var asPage = true
        val toggle = label("Source", 14f, Color.WHITE)
        toggle.setPadding(dp(10), dp(8), dp(10), dp(8))
        bar.addView(toggle, 2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun draw() {
            body.removeAllViews()
            if (asPage) {
                val w = WebView(this)
                // a saved page is shown safely: no scripts, no internet, no access to other files
                w.settings.javaScriptEnabled = false
                w.settings.blockNetworkLoads = true
                w.settings.allowFileAccess = false
                w.settings.allowContentAccess = false
                w.settings.builtInZoomControls = true
                w.settings.displayZoomControls = false
                w.settings.useWideViewPort = true
                w.settings.loadWithOverviewMode = true
                w.loadDataWithBaseURL(null, s, "text/html", "utf-8", null)
                body.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                toggle.text = "Source"
            } else {
                body.addView(sourceView(s), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                toggle.text = "Page"
            }
        }
        toggle.setOnClickListener {
            asPage = !asPage
            draw()
        }
        draw()
    }
}

/** An image you can pinch to zoom, drag, and double-tap to reset. */
internal class ZoomImageView(ctx: Context) : ImageView(ctx) {
    private val m = Matrix()
    private var minScale = 1f
    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            var f = d.scaleFactor
            val target = scale * f
            if (target < minScale) f = minScale / scale
            if (target > minScale * 8f) f = minScale * 8f / scale
            m.postScale(f, f, d.focusX, d.focusY)
            scale *= f
            fix()
            imageMatrix = m
            return true
        }
    })

    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            fit()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        fit()
    }

    private fun fit() {
        val dr = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = dr.intrinsicWidth.toFloat()
        val dh = dr.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        val s = minOf(width / dw, height / dh)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
        minScale = s
        scale = s
        imageMatrix = m
    }

    /** Keeps the picture from sliding out of the screen. */
    private fun fix() {
        val dr = drawable ?: return
        val v = FloatArray(9)
        m.getValues(v)
        val w = dr.intrinsicWidth * v[Matrix.MSCALE_X]
        val h = dr.intrinsicHeight * v[Matrix.MSCALE_Y]
        var dx = 0f
        var dy = 0f
        val x = v[Matrix.MTRANS_X]
        val y = v[Matrix.MTRANS_Y]
        if (w <= width) dx = (width - w) / 2f - x else {
            if (x > 0) dx = -x
            if (x + w < width) dx = width - (x + w)
        }
        if (h <= height) dy = (height - h) / 2f - y else {
            if (y > 0) dy = -y
            if (y + h < height) dy = height - (y + h)
        }
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaler.isInProgress && e.pointerCount == 1) {
                    m.postTranslate(e.x - lastX, e.y - lastY)
                    lastX = e.x
                    lastY = e.y
                    fix()
                    imageMatrix = m
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i)
                lastY = e.getY(i)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
