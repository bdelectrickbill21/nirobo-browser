package org.nirobo.browser

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Nirobo's own download engine. It does not use Android's Download Manager.
 *
 * It works the way Chrome's download code works:
 *  - The request carries the page address (Referer), the cookies and the browser's user agent.
 *  - The file name comes from the server's Content-Disposition header first, then from the address,
 *    then from the file type. Dangerous characters are removed and the name is never a folder.
 *  - While downloading, the bytes go into a hidden ".part" file in the app's own folder.
 *    When every byte has arrived, the file is moved into the phone's Downloads folder
 *    (a name that is already taken becomes "name (1).ext").
 *  - Resume = ask for the rest with "Range" plus "If-Range" (the ETag or the date the server gave
 *    at the start). Only a server that gave such a marker is resumed; otherwise the file starts over.
 *    The answer is checked: a 206 must begin at the right byte, a 200 means "start over",
 *    a 416 means "already complete, or start over".
 *  - When the connection drops, the download waits and continues by itself when the network is back
 *    (also after the app was closed and opened again).
 *  - Up to 3 files download at the same time, the rest wait in line.
 *  - A foreground service keeps everything alive while the browser is in the background,
 *    and its notification has Pause and Cancel buttons.
 *
 * Downloads made in private windows are never written to disk (only the finished file in Downloads).
 */

/** One file, from the first byte to the last. */
internal class DlItem(val id: Long, var name: String, val url: String) {
    var mime: String = ""
    var ua: String = ""
    var referer: String = ""
    var priv: Boolean = false
    var ts: Long = System.currentTimeMillis()
    var uri: String = ""
    var dmId: Long = -1L
    /** The marks the server gave at the start: used to prove the file did not change before resuming. */
    var etag: String = ""
    var lastMod: String = ""
    var ranges: Boolean = false
    /** True when the download stopped because of the network and should go on by itself later. */
    var auto: Boolean = false
    val lock = Any()
    @Volatile var ticket: Int = 0
    @Volatile var state: String = "waiting" // waiting, running, paused, done, failed, removed
    @Volatile var got: Long = 0L
    @Volatile var total: Long = 0L
    @Volatile var speed: Long = 0L
    @Volatile var err: String = ""
    @Volatile var conn: HttpURLConnection? = null
    @Volatile var exists: Boolean = true
    @Volatile var checkedAt: Long = 0L
}

private class DlFatal(msg: String) : Exception(msg)

// ---------------- names ----------------

/** Makes a safe plain file name: no folders, no hidden-file dot, no characters Android refuses. */
internal fun cleanFileName(raw: String): String {
    var n = raw.substringAfterLast('/').substringAfterLast('\\')
    n = n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
    if (n.length > 120) {
        val ext = n.substringAfterLast('.', "")
        n = if (ext.isNotEmpty() && ext.length <= 10) n.take(110) + "." + ext else n.take(120)
    }
    return if (n.isEmpty()) "download" else n
}

/** Reads the file name out of a Content-Disposition header (null when it has none). */
internal fun parseDisposition(cd: String?): String? {
    if (cd.isNullOrBlank()) return null
    // the modern form: filename*=UTF-8''%E2%82%AC.txt
    val star = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)?.trim()
    if (star != null) {
        val q1 = star.indexOf('\'')
        val q2 = if (q1 >= 0) star.indexOf('\'', q1 + 1) else -1
        if (q2 > 0) {
            val cs = star.substring(0, q1).ifEmpty { "UTF-8" }
            val raw = star.substring(q2 + 1).trim('"')
            try {
                val s = java.net.URLDecoder.decode(raw.replace("+", "%2B"), cs)
                if (s.isNotBlank()) return cleanFileName(s)
            } catch (e: Exception) {
                // try the plain form below
            }
        }
    }
    val m = Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(cd) ?: return null
    var v = (if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues[1]).trim().trim('"')
    if (v.isEmpty()) return null
    // many servers send UTF-8 letters raw; Java reads headers as Latin-1, so read them again as UTF-8
    val again = String(v.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    if (!again.contains('\uFFFD')) v = again
    if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(v)) {
        try {
            v = java.net.URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            // keep it as it is
        }
    }
    return cleanFileName(v)
}

/** A reason a person can understand. */
internal fun friendlyError(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "No internet, or this address does not exist."
    is java.net.SocketTimeoutException -> "The website took too long to answer."
    is javax.net.ssl.SSLException -> "The secure connection failed."
    is java.net.ConnectException -> "Could not connect to the website."
    else -> e.message ?: e.javaClass.simpleName
}

internal object Downloader {

    private val items = ArrayList<DlItem>()
    private val pool = Executors.newFixedThreadPool(3)
    private var ctx: Context? = null
    private var store: Store? = null
    private var lastId = 0L

    const val CH_LIVE = "nirobo_dl_live"
    const val CH_DONE = "nirobo_dl_done"

    // ---------------- start-up ----------------

    @Synchronized
    fun init(c: Context, space: String = "") {
        if (ctx != null) return
        val app = c.applicationContext
        ctx = app
        store = Store(app, space)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        val a = store!!.downloads()
        val now = System.currentTimeMillis()
        val keep = HashSet<String>()
        synchronized(items) {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val d = DlItem(o.optLong("id"), o.optString("n"), o.optString("u"))
                d.mime = o.optString("m")
                d.ua = o.optString("ua")
                d.referer = o.optString("rf")
                d.etag = o.optString("et")
                d.lastMod = o.optString("lm")
                d.ranges = o.optBoolean("rg")
                d.auto = o.optBoolean("au")
                d.ts = o.optLong("ts")
                d.got = o.optLong("got")
                d.total = o.optLong("total")
                d.uri = o.optString("uri")
                d.dmId = o.optLong("dm", -1L)
                d.err = o.optString("err")
                if (!o.has("s")) {
                    // an entry from the old version, which used Android's Download Manager
                    d.dmId = d.id
                    d.state = "done"
                    try {
                        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val u = dm.getUriForDownloadedFile(d.id)
                        d.uri = u?.toString() ?: ""
                        d.mime = dm.getMimeTypeForDownloadedFile(d.id) ?: ""
                        d.exists = u != null
                        d.checkedAt = now
                    } catch (e: Exception) {
                        d.exists = false
                    }
                } else {
                    val s = o.optString("s")
                    if (s == "running" || s == "waiting") {
                        // the app was closed in the middle: go on by itself when the network is there
                        d.state = "paused"
                        d.auto = true
                    } else {
                        d.state = s
                    }
                }
                if (d.state != "done") keep.add(d.id.toString() + ".part")
                items.add(d)
            }
        }
        // half-finished files nobody remembers are thrown away
        try {
            dir(app).listFiles()?.forEach { f -> if (!keep.contains(f.name)) f.delete() }
        } catch (e: Exception) {
            // not important
        }
        watchNetwork(app)
    }

    /** Like Chrome: when the network comes back, interrupted downloads continue by themselves. */
    private fun watchNetwork(app: Context) {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    resumeAuto()
                }
            })
        } catch (e: Exception) {
            // the buttons still work
        }
    }

    private fun resumeAuto() {
        val ids = synchronized(items) { items.filter { it.auto && it.state == "paused" }.map { it.id } }
        for (id in ids) resume(id)
    }

    @Synchronized
    private fun newId(): Long {
        val n = System.currentTimeMillis()
        lastId = if (n > lastId) n else lastId + 1
        return lastId
    }

    internal fun tempDir(c: Context): File = dir(c)

    // ---------------- control ----------------

    fun enqueue(name: String, url: String, ua: String, referer: String, mime: String, priv: Boolean): Long {
        if (ctx == null) return -1L
        val d = DlItem(newId(), cleanFileName(name), url)
        d.ua = ua
        d.referer = referer
        d.mime = mime
        d.priv = priv
        add(d)
        persist()
        start(d)
        return d.id
    }

    private fun add(d: DlItem) {
        synchronized(items) {
            items.add(d)
            // keep the list short: forget the oldest finished entries
            while (items.size > 300) {
                val old = items.firstOrNull { it.state == "done" || it.state == "failed" } ?: break
                items.remove(old)
            }
        }
    }

    private fun start(d: DlItem) {
        d.ticket++
        d.state = "waiting"
        d.err = ""
        d.auto = false
        val t = d.ticket
        ctx?.let { startService(it) }
        pool.execute { run(d, t) }
    }

    fun find(id: Long): DlItem? = synchronized(items) { items.firstOrNull { it.id == id } }

    /** A person pressed Pause: it stays paused until they press Resume. */
    fun pause(id: Long) {
        val d = find(id) ?: return
        if (d.state == "running" || d.state == "waiting") {
            d.state = "paused"
            d.auto = false
            d.speed = 0
            try { d.conn?.disconnect() } catch (e: Exception) { }
            persist()
        }
    }

    /** Resume, or Retry after a failure. */
    fun resume(id: Long) {
        val d = find(id) ?: return
        if (d.url.isEmpty()) return
        if (d.state == "paused" || d.state == "failed") {
            start(d)
            persist()
        }
    }

    fun pauseAll() {
        val ids = synchronized(items) { items.map { it.id } }
        for (id in ids) pause(id)
    }

    fun resumeAll() {
        val ids = synchronized(items) { items.filter { it.state == "paused" }.map { it.id } }
        for (id in ids) resume(id)
    }

    /** Stops the file, throws the half-finished part away and, when asked, also deletes the saved file. */
    fun remove(id: Long, deleteFile: Boolean) {
        val d = find(id) ?: return
        d.ticket++
        d.state = "removed"
        try { d.conn?.disconnect() } catch (e: Exception) { }
        val c = ctx
        if (c != null) {
            try { File(dir(c), d.id.toString() + ".part").delete() } catch (e: Exception) { }
            if (deleteFile) {
                try {
                    if (d.dmId >= 0) {
                        (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(d.dmId)
                    } else if (d.uri.isNotEmpty()) {
                        val u = Uri.parse(d.uri)
                        if (u.scheme == "file") File(u.path ?: "").delete() else c.contentResolver.delete(u, null, null)
                    }
                } catch (e: Exception) {
                    // already gone, or not ours to delete
                }
            }
        }
        synchronized(items) { items.remove(d) }
        persist()
    }

    /** "Clear list": forgets finished, failed and missing entries. The saved files stay on the phone. */
    fun clearFinished(priv: Boolean) {
        val c = ctx
        val gone = synchronized(items) { items.filter { it.priv == priv && (it.state == "done" || it.state == "failed") } }
        for (d in gone) {
            if (c != null) try { File(dir(c), d.id.toString() + ".part").delete() } catch (e: Exception) { }
        }
        synchronized(items) { items.removeAll(gone.toSet()) }
        persist()
    }

    fun activeCount(): Int = synchronized(items) { items.count { it.state == "running" || it.state == "waiting" } }

    fun uriFor(id: Long): Uri? {
        val d = find(id) ?: return null
        if (d.state != "done" || d.uri.isEmpty()) return null
        return try { Uri.parse(d.uri) } catch (e: Exception) { null }
    }

    // ---------------- files that are already on the phone (a saved page, a blob, a data: link) ----------------

    /** Moves a finished file (made by the browser, not downloaded from a server) into Downloads. */
    fun addLocal(src: File, name: String, mime: String, priv: Boolean): Long {
        if (ctx == null) return -1L
        val clean = cleanFileName(name)
        val d = DlItem(newId(), clean, "")
        d.priv = priv
        d.mime = mime
        d.total = src.length()
        d.got = 0L
        d.state = "running"
        add(d)
        persist()
        val t = d.ticket
        pool.execute {
            val c = ctx
            try {
                if (c == null) throw IOException("not ready")
                publish(c, d, src)
                d.got = d.total
                if (d.ticket != t) return@execute
                d.state = "done"
                d.checkedAt = System.currentTimeMillis()
                d.exists = true
                notifyDone(c, d)
            } catch (e: Exception) {
                d.err = "Could not save the file to Downloads."
                d.state = "failed"
            }
            try { src.delete() } catch (e: Exception) { }
            persist()
        }
        return d.id
    }

    /** data: links hold the file inside the address itself. */
    fun saveDataUrl(url: String, name: String, mime: String, priv: Boolean): Boolean {
        val c = ctx ?: return false
        val comma = url.indexOf(',')
        if (comma < 5 || url.length > 80 * 1024 * 1024) return false
        val head = url.substring(5, comma)
        val body = url.substring(comma + 1)
        val type = head.removeSuffix(";base64").substringBefore(';').ifEmpty { mime }
        val bytes: ByteArray = try {
            if (head.endsWith(";base64")) Base64.decode(body, Base64.DEFAULT)
            else java.net.URLDecoder.decode(body.replace("+", "%2B"), "UTF-8").toByteArray()
        } catch (e: Exception) {
            return false
        }
        val f = File(dir(c), "d" + newId() + ".tmp")
        return try {
            f.writeBytes(bytes)
            addLocal(f, withExtension(name, type), type, priv)
            true
        } catch (e: Exception) {
            false
        }
    }

    internal fun withExtension(name: String, mime: String): String {
        if (name.substringAfterLast('.', "").isNotEmpty() && name.contains('.')) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (ext.isNullOrEmpty()) name else "$name.$ext"
    }

    // ---------------- what the Downloads page shows ----------------

    /** The list for one kind of window: normal windows never see private downloads and the other way round. */
    fun snapshot(priv: Boolean): String {
        val c = ctx
        val list = synchronized(items) { ArrayList(items) }
        val arr = JSONArray()
        var used = 0L
        val now = System.currentTimeMillis()
        for (i in list.size - 1 downTo 0) {
            val d = list[i]
            if (d.priv != priv) continue
            var s = d.state
            if (s == "done") {
                if (c != null && now - d.checkedAt > 10000) {
                    d.exists = fileExists(c, d)
                    d.checkedAt = now
                }
                if (!d.exists) s = "gone" else used += d.total
            }
            val o = JSONObject()
            o.put("id", d.id.toString())
            o.put("n", d.name)
            o.put("u", d.url)
            o.put("h", hostOf(d.url))
            o.put("ts", d.ts)
            o.put("s", s)
            o.put("got", d.got)
            o.put("total", d.total)
            o.put("sp", d.speed)
            o.put("err", d.err)
            o.put("auto", d.auto)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("items", arr)
        root.put("used", used)
        root.put("cap", try { StatFs(Environment.getDataDirectory().path).totalBytes } catch (e: Exception) { 0L })
        return root.toString()
    }

    private fun hostOf(u: String): String = try { URL(u).host.removePrefix("www.") } catch (e: Exception) { "" }

    private fun fileExists(c: Context, d: DlItem): Boolean {
        if (d.uri.isEmpty()) return false
        return try {
            val u = Uri.parse(d.uri)
            if (u.scheme == "file") File(u.path ?: "").exists()
            else c.contentResolver.query(u, arrayOf("_id"), null, null, null)?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            d.dmId >= 0
        }
    }

    private fun persist() {
        val st = store ?: return
        val arr = JSONArray()
        synchronized(items) {
            for (d in items) {
                if (d.priv) continue
                val o = JSONObject()
                o.put("id", d.id)
                o.put("n", d.name)
                o.put("u", d.url)
                o.put("m", d.mime)
                o.put("ua", d.ua)
                o.put("rf", d.referer)
                o.put("et", d.etag)
                o.put("lm", d.lastMod)
                o.put("rg", d.ranges)
                o.put("au", d.auto)
                o.put("ts", d.ts)
                o.put("s", d.state)
                o.put("got", d.got)
                o.put("total", d.total)
                o.put("uri", d.uri)
                o.put("dm", d.dmId)
                o.put("err", d.err)
                arr.put(o)
            }
        }
        st.putString("downloads", arr.toString())
    }

    // ---------------- the transfer ----------------

    private fun dir(c: Context): File {
        val base = c.getExternalFilesDir(null) ?: c.filesDir
        val f = File(base, "dl")
        f.mkdirs()
        return f
    }

    private fun alive(d: DlItem, t: Int) = d.ticket == t && d.state == "running"

    /** Only a server that gave a marker (ETag or date) and allows ranges can be resumed safely. */
    private fun canResume(d: DlItem) = d.ranges && (d.etag.isNotEmpty() || d.lastMod.isNotEmpty())

    private fun run(d: DlItem, ticket: Int) {
        synchronized(d.lock) {
            if (d.ticket != ticket || d.state != "waiting") return
            val c = ctx ?: return
            d.state = "running"
            val part = File(dir(c), d.id.toString() + ".part")
            var attempt = 0
            while (alive(d, ticket)) {
                val before = d.got
                try {
                    if (transfer(c, d, ticket, part)) finish(c, d, ticket, part)
                    return
                } catch (e: DlFatal) {
                    fail(c, d, e.message ?: "Failed")
                    return
                } catch (e: javax.net.ssl.SSLException) {
                    fail(c, d, friendlyError(e))
                    return
                } catch (e: IOException) {
                    if (!alive(d, ticket)) return
                    if (d.got > 0 && !canResume(d)) {
                        // the file cannot be continued, so Retry has to start over
                        fail(c, d, friendlyError(e) + " This website does not allow continuing, so Retry starts again.")
                        return
                    }
                    if (d.got > before) attempt = 0
                    attempt++
                    if (attempt > 4) {
                        // wait for the network, then go on by itself
                        d.err = friendlyError(e)
                        d.speed = 0
                        d.auto = true
                        d.state = "paused"
                        persist()
                        return
                    }
                    var waited = 0L
                    while (waited < attempt * 2000L && alive(d, ticket)) {
                        try { Thread.sleep(250) } catch (x: InterruptedException) { }
                        waited += 250
                    }
                } catch (e: Exception) {
                    fail(c, d, "Can't download this file")
                    return
                }
            }
        }
    }

    /** Returns true when every byte has arrived, false when it was paused or cancelled. */
    private fun transfer(c: Context, d: DlItem, ticket: Int, part: File): Boolean {
        var start = if (part.exists()) part.length() else 0L
        if (start > 0 && !canResume(d)) {
            part.delete()
            start = 0
        }
        var url = d.url
        var hops = 0
        var cn = URL(url).openConnection() as HttpURLConnection
        while (true) {
            cn = URL(url).openConnection() as HttpURLConnection
            d.conn = cn
            cn.instanceFollowRedirects = false
            cn.connectTimeout = 15000
            cn.readTimeout = 20000
            cn.setRequestProperty("User-Agent", d.ua)
            cn.setRequestProperty("Accept-Encoding", "identity")
            if (d.referer.startsWith("http")) cn.setRequestProperty("Referer", d.referer)
            val ck = try { CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }
            if (ck != null) cn.setRequestProperty("Cookie", ck)
            if (start > 0) {
                cn.setRequestProperty("Range", "bytes=$start-")
                cn.setRequestProperty("If-Range", if (d.etag.isNotEmpty()) d.etag else d.lastMod)
            }
            val code = cn.responseCode
            if (code in 300..399 && code != 304) {
                val loc = cn.getHeaderField("Location")
                cn.disconnect()
                hops++
                if (loc == null || hops > 8) throw DlFatal("Too many redirects")
                url = URL(URL(url), loc).toString()
                if (!url.startsWith("https://")) throw DlFatal("Blocked: the link moved to an insecure (http) address")
                continue
            }
            break
        }
        try {
            val code = cn.responseCode
            if (code == 416) {
                // "that part does not exist": the file is already complete, or it changed
                val tot = cn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                if (tot > 0 && part.length() == tot) {
                    d.total = tot
                    d.got = tot
                    return true
                }
                part.delete()
                d.got = 0
                throw IOException("The file changed. Starting again.")
            }
            if (code == 401 || code == 403) {
                throw DlFatal("The website refused the download ($code). It may want you to sign in on the page first.")
            }
            if (code == 404 || code == 410) throw DlFatal("The file was not found on the website ($code).")
            if (code == 408 || code == 429 || code in 500..599) throw IOException("The website is busy or broken ($code).")
            if (code != 200 && code != 206) throw DlFatal("The website answered with error $code.")

            var total: Long
            if (code == 206) {
                // "bytes 100-999/1000": it must begin exactly where we asked
                val cr = cn.getHeaderField("Content-Range") ?: ""
                val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).find(cr)
                val first = m?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                if (m == null || first != start) {
                    part.delete()
                    d.got = 0
                    throw IOException("The website sent the wrong part. Starting again.")
                }
                total = m.groupValues[3].toLongOrNull() ?: 0L
            } else {
                // a full answer: when we asked for a part, the server ignored it, so start over
                if (start > 0) {
                    part.delete()
                    start = 0
                }
                val len = cn.contentLengthLong
                total = if (len > 0) len else 0L
            }
            d.total = total

            if (start == 0L) {
                val et = cn.getHeaderField("ETag") ?: ""
                d.etag = if (et.isNotEmpty() && !et.startsWith("W/")) et else ""
                d.lastMod = cn.getHeaderField("Last-Modified") ?: ""
                d.ranges = "bytes".equals(cn.getHeaderField("Accept-Ranges"), true) || code == 206
            }

            // the real file name and type come from the server's answer
            val ct = cn.contentType?.substringBefore(';')?.trim()?.lowercase() ?: ""
            if (ct.isNotEmpty() && (d.mime.isEmpty() || d.mime == "application/octet-stream")) d.mime = ct
            val fromHeader = parseDisposition(cn.getHeaderField("Content-Disposition"))
            if (fromHeader != null) {
                d.name = fromHeader
            } else if (ct.isNotEmpty() && ct != "application/octet-stream" && ct != "binary/octet-stream") {
                val ext = d.name.substringAfterLast('.', "").lowercase()
                if (!d.name.contains('.') || ext == "bin") {
                    val e2 = MimeTypeMap.getSingleton().getExtensionFromMimeType(ct)
                    if (!e2.isNullOrEmpty()) d.name = d.name.substringBeforeLast('.', d.name) + "." + e2
                }
            }

            if (d.total > 0) {
                val need = d.total - start
                if (dir(c).usableSpace < need + 10L * 1024 * 1024) throw DlFatal("Not enough free space on the phone.")
            }
            persist()
            d.got = start
            val buf = ByteArray(65536)
            var last = System.currentTimeMillis()
            var lastBytes = d.got
            var saved = last
            cn.inputStream.use { ins ->
                FileOutputStream(part, start > 0).use { out ->
                    while (true) {
                        if (!alive(d, ticket)) return false
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        d.got += n
                        val now = System.currentTimeMillis()
                        if (now - last >= 1000) {
                            d.speed = (d.got - lastBytes) * 1000 / (now - last)
                            last = now
                            lastBytes = d.got
                        }
                        if (now - saved >= 4000) {
                            saved = now
                            persist()
                        }
                    }
                }
            }
            if (d.total > 0 && d.got < d.total) throw IOException("The connection was cut short.")
            if (d.total <= 0) d.total = d.got
            return true
        } finally {
            try { cn.disconnect() } catch (e: Exception) { }
            d.conn = null
        }
    }

    private fun fail(c: Context, d: DlItem, why: String) {
        d.speed = 0
        d.err = why
        d.state = "failed"
        persist()
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = Notification.Builder(c, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Download failed")
                .setContentText(if (d.priv) why else d.name + " - " + why)
                .setStyle(Notification.BigTextStyle().bigText(why))
                .setAutoCancel(true)
            if (!d.priv) n.setContentIntent(widgetTap(c, "downloads"))
            nm.notify((d.id % 100000L).toInt() + 1000, n.build())
        } catch (e: Exception) {
            // no notification right
        }
    }

    /** The file is complete: move it into the phone's Downloads folder. */
    private fun finish(c: Context, d: DlItem, ticket: Int, part: File) {
        d.speed = 0
        try {
            publish(c, d, part)
            part.delete()
            if (d.ticket != ticket) return
            d.state = "done"
            d.checkedAt = System.currentTimeMillis()
            d.exists = true
            persist()
            notifyDone(c, d)
        } catch (e: Exception) {
            fail(c, d, "Could not save the file to Downloads. Tap Retry.")
        }
    }

    private fun notifyDone(c: Context, d: DlItem) {
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = Notification.Builder(c, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (d.priv) "Private download" else d.name)
                .setContentText("Download complete")
                .setAutoCancel(true)
            // a tap opens the file, like Chrome (private files are not named in the app)
            if (!d.priv) n.setContentIntent(widgetTap(c, "open:" + d.id))
            nm.notify((d.id % 100000L).toInt() + 1000, n.build())
        } catch (e: Exception) {
            // no notification right
        }
    }

    private fun publish(c: Context, d: DlItem, part: File) {
        // the type is taken from the ending when Android knows it, so Android never changes the name
        val ext = d.name.substringAfterLast('.', "").lowercase()
        val byExt = if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        val mime = byExt ?: (if (d.mime.isNotEmpty()) d.mime else "application/octet-stream")
        d.mime = mime
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues()
            cv.put(MediaStore.Downloads.DISPLAY_NAME, d.name)
            cv.put(MediaStore.Downloads.MIME_TYPE, mime)
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            cv.put(MediaStore.Downloads.IS_PENDING, 1)
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IOException("no place to save")
            try {
                val out = c.contentResolver.openOutputStream(uri) ?: throw IOException("cannot write")
                out.use { o -> part.inputStream().use { inp -> inp.copyTo(o, 65536) } }
                val done = ContentValues()
                done.put(MediaStore.Downloads.IS_PENDING, 0)
                c.contentResolver.update(uri, done, null, null)
            } catch (e: Exception) {
                try { c.contentResolver.delete(uri, null, null) } catch (x: Exception) { }
                throw e
            }
            d.uri = uri.toString()
            // Android adds "(1)" when the name was taken: show the real name
            try {
                c.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) d.name = it.getString(0)
                }
            } catch (e: Exception) {
                // keep the name we asked for
            }
        } else {
            val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            folder.mkdirs()
            val base = d.name.substringBeforeLast('.', d.name)
            val e2 = d.name.substringAfterLast('.', "")
            var f = File(folder, d.name)
            var n = 1
            while (f.exists()) {
                f = File(folder, base + " (" + n + ")" + (if (e2.isEmpty()) "" else ".$e2"))
                n++
            }
            part.copyTo(f)
            val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            @Suppress("DEPRECATION")
            val id = dm.addCompletedDownload(f.name, d.name, true, mime, f.absolutePath, f.length(), false)
            d.dmId = id
            d.name = f.name
            d.uri = dm.getUriForDownloadedFile(id)?.toString() ?: Uri.fromFile(f).toString()
        }
    }

    // ---------------- notification and service ----------------

    private fun startService(c: Context) {
        try {
            c.startForegroundService(Intent(c, DownloadService::class.java))
        } catch (e: Exception) {
            // the download still runs while the browser is open
        }
    }

    private fun sizeText(b: Long): String {
        if (b <= 0) return "0 B"
        val u = arrayOf("B", "KB", "MB", "GB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < 3) {
            v /= 1024
            i++
        }
        return (if (i == 0) v.toLong().toString() else String.format("%.1f", v)) + " " + u[i]
    }

    private fun button(c: Context, label: String, icon: Int, act: String, id: Long): Notification.Action {
        val i = Intent(c, DownloadService::class.java)
        i.action = "nirobo.dl.$act"
        i.putExtra("act", act)
        i.putExtra("id", id)
        val pi = PendingIntent.getForegroundService(
            c, act.hashCode() * 31 + id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(c, icon), label, pi).build()
    }

    fun liveNotification(c: Context): Notification {
        var n = 0
        var got = 0L
        var total = 0L
        var speed = 0L
        var name = ""
        var one = -1L
        var priv = false
        synchronized(items) {
            for (d in items) {
                if (d.state == "running" || d.state == "waiting") {
                    n++
                    got += d.got
                    if (d.total > 0) total += d.total
                    speed += d.speed
                    name = d.name
                    one = d.id
                    priv = priv || d.priv
                }
            }
        }
        val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else -1
        val b = Notification.Builder(c, CH_LIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(widgetTap(c, "downloads"))
            .setContentTitle(if (n <= 1) (if (priv) "Private download" else name.ifEmpty { "Downloading" }) else "$n downloads")
            .setContentText(
                (if (pct >= 0) "$pct%  -  " else "") +
                    (if (speed > 0) sizeText(speed) + "/s" else "Starting\u2026")
            )
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        if (n == 1) {
            b.addAction(button(c, "Pause", android.R.drawable.ic_media_pause, "pause", one))
            b.addAction(button(c, "Cancel", android.R.drawable.ic_menu_close_clear_cancel, "cancel", one))
        } else if (n > 1) {
            b.addAction(button(c, "Pause all", android.R.drawable.ic_media_pause, "pauseall", -1L))
        }
        return b.build()
    }

    /** Shown after a Pause button press, so a person can continue from the notification. */
    fun pausedNotification(c: Context): Notification? {
        val n = synchronized(items) { items.count { it.state == "paused" } }
        if (n == 0) return null
        return Notification.Builder(c, CH_LIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (n == 1) "Download paused" else "$n downloads paused")
            .setContentText("Tap Resume to continue")
            .setAutoCancel(true)
            .setContentIntent(widgetTap(c, "downloads"))
            .addAction(button(c, "Resume", android.R.drawable.ic_media_play, "resumeall", -1L))
            .build()
    }
}

/**
 * Keeps the downloads running while the browser is in the background. It stops by itself when nothing
 * is left, and it also carries out the Pause / Cancel / Resume buttons of its notification.
 */
internal class DownloadService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (Downloader.activeCount() == 0) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(77, Downloader.liveNotification(this@DownloadService))
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Downloader.init(this)
        // Android wants the notification at once
        val n = Downloader.liveNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(77, n)
        } catch (e: Exception) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        val act = intent?.getStringExtra("act") ?: ""
        val id = intent?.getLongExtra("id", -1L) ?: -1L
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (act) {
            "pause" -> Downloader.pause(id)
            "pauseall" -> Downloader.pauseAll()
            "cancel" -> Downloader.remove(id, false)
            "resume" -> Downloader.resume(id)
            "resumeall" -> {
                nm.cancel(78)
                Downloader.resumeAll()
            }
        }
        h.removeCallbacks(tick)
        if (Downloader.activeCount() == 0) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            if (act == "pause" || act == "pauseall") {
                Downloader.pausedNotification(this)?.let { nm.notify(78, it) }
            }
            stopSelf()
            return Service.START_NOT_STICKY
        }
        h.postDelayed(tick, 1000)
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        super.onDestroy()
    }
}

/**
 * Saves a blob: file (a file a page made with its own script). The page cannot be fetched from outside,
 * so the page's own code reads it and hands the bytes over in pieces, each call carrying a secret
 * one-time token that only this browser knows.
 */
internal class BlobSaver(private val ctx: Context, private val say: (String) -> Unit) {
    private class Req(val name: String, val mime: String, val priv: Boolean)
    private class Job(val req: Req, val file: File, val out: FileOutputStream) {
        var size = 0L
        var type = ""
    }

    private val pending = ConcurrentHashMap<String, Req>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val rnd = SecureRandom()
    private val limit = 64L * 1024 * 1024

    /** Starts reading the blob inside the page. Call on the main thread. */
    fun fetch(web: WebView, blobUrl: String, name: String, mime: String, priv: Boolean) {
        val raw = ByteArray(16)
        rnd.nextBytes(raw)
        val token = raw.joinToString("") { String.format("%02x", it) }
        pending[token] = Req(name, mime, priv)
        val js = """
            (function(u,t){
              function f(){try{NiroboBlob.fail(t)}catch(e){}}
              try{
                var x=new XMLHttpRequest();
                x.open('GET',u);x.responseType='blob';
                x.onerror=f;
                x.onload=function(){
                  var b=x.response;
                  if(!b){f();return}
                  var r=new FileReader();
                  r.onerror=f;
                  r.onload=function(){
                    var s=String(r.result),i=s.indexOf(',')+1;
                    NiroboBlob.begin(t,b.type||'',b.size);
                    for(var o=i;o<s.length;o+=262144){NiroboBlob.chunk(t,s.substring(o,o+262144))}
                    NiroboBlob.end(t)};
                  r.readAsDataURL(b)};
                x.send()
              }catch(e){f()}
            })(${'$'}URL,${'$'}TOKEN)
        """.trimIndent().replace("${'$'}URL", JSONObject.quote(blobUrl)).replace("${'$'}TOKEN", JSONObject.quote(token))
        web.evaluateJavascript(js, null)
    }

    @JavascriptInterface
    fun begin(token: String, type: String, size: Double) {
        val req = pending[token] ?: return
        if (size > limit) {
            pending.remove(token)
            say("This file is too big to save from the page (over 64 MB)")
            return
        }
        try {
            val f = File(Downloader.tempDir(ctx), "b" + token.take(12) + ".tmp")
            val j = Job(req, f, FileOutputStream(f))
            j.type = type
            jobs[token] = j
        } catch (e: Exception) {
            pending.remove(token)
        }
    }

    @JavascriptInterface
    fun chunk(token: String, data: String) {
        val j = jobs[token] ?: return
        try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            j.size += bytes.size
            if (j.size > limit) throw IOException("too big")
            j.out.write(bytes)
        } catch (e: Exception) {
            abort(token)
        }
    }

    @JavascriptInterface
    fun end(token: String) {
        val j = jobs.remove(token) ?: return
        pending.remove(token)
        try {
            j.out.close()
            val type = if (j.type.isNotEmpty()) j.type else j.req.mime
            val name = Downloader.withExtension(cleanFileName(j.req.name), type)
            Downloader.addLocal(j.file, name, type, j.req.priv)
            say("Saving $name")
        } catch (e: Exception) {
            abort(token)
        }
    }

    @JavascriptInterface
    fun fail(token: String) {
        abort(token)
        say("Can't save this file from the page")
    }

    private fun abort(token: String) {
        pending.remove(token)
        val j = jobs.remove(token) ?: return
        try { j.out.close() } catch (e: Exception) { }
        try { j.file.delete() } catch (e: Exception) { }
    }
}
