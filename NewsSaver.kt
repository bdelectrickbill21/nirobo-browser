package org.nirobo.browser

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Keeps a copy of NewsHub on the phone.
 *
 * It downloads https://nirobo.pages.dev/data/news.json once a day, at UTC
 * midnight, and keeps the newest stories. When the phone has no internet the
 * browser shows this copy instead of an error page.
 *
 * Two things trigger a save:
 *  - an alarm set for the next UTC midnight (works even if the app is closed);
 *  - a check whenever the app comes to the front, which catches a day the
 *    phone was off or the alarm was missed.
 */
internal object NewsSaver {

    private const val FILE = "news_saved.json"
    private const val URL_NEWS = "https://nirobo.pages.dev/data/news.json"
    private const val KEEP = 120
    private const val ALARM_REQUEST = 4711

    private val pool = Executors.newSingleThreadExecutor()
    private var running = false

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** The UTC day number, so "once a day" means once per UTC calendar day. */
    private fun utcDay(ms: Long): Long = ms / 86_400_000L

    /** Saves only if today's UTC copy is not on the phone yet. */
    fun saveIfDue(ctx: Context, done: (Boolean) -> Unit) {
        val f = file(ctx)
        val store = Store(ctx)
        val last = store.newsSavedAt
        val due = !f.exists() || f.length() < 500 || utcDay(last) != utcDay(System.currentTimeMillis())
        if (!due) {
            done(true)
            return
        }
        save(ctx, done)
    }

    /** Downloads and stores the feed. Safe to call from any thread. */
    fun save(ctx: Context, done: (Boolean) -> Unit) {
        if (running) {
            done(false)
            return
        }
        running = true
        val app = ctx.applicationContext
        pool.execute {
            var ok = false
            var savedKeep: JSONArray? = null
            try {
                val c = URL(URL_NEWS).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 25000
                c.setRequestProperty("User-Agent", "NiroboBrowser/1.0 (Android)")
                c.setRequestProperty("Accept", "application/json")
                if (c.responseCode == 200) {
                    val buf = java.io.ByteArrayOutputStream()
                    c.inputStream.use { ins ->
                        val chunk = ByteArray(8192)
                        while (buf.size() < 2_000_000) {
                            val n = ins.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                        }
                    }
                    val text = buf.toString("UTF-8")
                    val parsed = JSONObject(text)
                    val items = parsed.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        val keep = JSONArray()
                        val n = minOf(items.length(), KEEP)
                        for (i in 0 until n) {
                            val o = items.optJSONObject(i) ?: continue
                            // only secure links are kept
                            if (!o.optString("u").startsWith("https://")) continue
                            // the longest text the feed gives us is the description
                            val desc = listOf("c", "content", "d", "summary", "description")
                                .map { k -> o.optString(k) }
                                .maxByOrNull { it.length } ?: ""
                            var img = ""
                            for (k in listOf("i", "img", "image", "thumb")) {
                                val v = o.optString(k)
                                if (v.startsWith("https://")) {
                                    img = v
                                    break
                                }
                            }
                            val slim = JSONObject()
                            slim.put("t", o.optString("t"))
                            slim.put("u", o.optString("u"))
                            slim.put("d", desc.take(6000))
                            slim.put("s", o.optString("s"))
                            slim.put("i", img)
                            keep.put(slim)
                        }
                        val out = JSONObject()
                        out.put("v", 1)
                        out.put("savedAt", System.currentTimeMillis())
                        out.put("source", parsed.optString("updated"))
                        out.put("items", keep)

                        // write to a temp file first, so a half-written file is never read
                        val tmp = File(app.filesDir, "$FILE.tmp")
                        tmp.writeText(out.toString())
                        val dest = file(app)
                        if (dest.exists()) dest.delete()
                        tmp.renameTo(dest)

                        Store(app).newsSavedAt = System.currentTimeMillis()
                        savedKeep = keep
                        ok = true
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
                ok = false
            }
            running = false
            done(ok)
            // pictures come after the text, so the news never waits for them
            val k = savedKeep
            if (ok && k != null) fetchImages(app, k)
        }
    }

    // ---------- pictures kept for offline reading ----------

    private fun imgKey(url: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return md.digest(url.toByteArray()).joinToString("") { b -> "%02x".format(b) }.substring(0, 20)
    }

    private fun imageMime(b: ByteArray): String? {
        if (b.size < 12) return null
        fun at(i: Int): Int = b[i].toInt() and 0xFF
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 -> "image/webp"
            else -> null
        }
    }

    /** Saves the pictures of the newest stories (small ones only) and forgets old ones. */
    private fun fetchImages(ctx: Context, items: JSONArray) {
        try {
            val dir = File(ctx.filesDir, "news_img")
            dir.mkdirs()
            val want = HashSet<String>()
            for (i in 0 until minOf(items.length(), 40)) {
                val u = items.optJSONObject(i)?.optString("i") ?: continue
                if (!u.startsWith("https://")) continue
                val key = imgKey(u)
                want.add(key)
                val f = File(dir, key)
                if (f.exists()) continue
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    if (c.responseCode == 200) {
                        val buf = java.io.ByteArrayOutputStream()
                        var tooBig = false
                        c.inputStream.use { ins ->
                            val chunk = ByteArray(8192)
                            while (true) {
                                val n = ins.read(chunk)
                                if (n < 0) break
                                buf.write(chunk, 0, n)
                                if (buf.size() > 160_000) {
                                    tooBig = true
                                    break
                                }
                            }
                        }
                        val bytes = buf.toByteArray()
                        if (!tooBig && imageMime(bytes) != null) f.writeBytes(bytes)
                    }
                    c.disconnect()
                } catch (e: Exception) {
                    // this picture is skipped
                }
            }
            dir.listFiles()?.forEach { f -> if (f.name !in want) f.delete() }
        } catch (e: Exception) {
            // pictures are a bonus
        }
    }

    /** A saved picture as a data address the news page can show, or "" when there is none. */
    fun imageDataUri(ctx: Context, url: String): String {
        if (!url.startsWith("https://")) return ""
        return try {
            val f = File(File(ctx.filesDir, "news_img"), imgKey(url))
            if (!f.exists()) return ""
            val bytes = f.readBytes()
            val mime = imageMime(bytes) ?: return ""
            "data:" + mime + ";base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    /** Sets the alarm for the next UTC midnight. */
    fun ensureScheduled(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(ctx)
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val at = cal.timeInMillis
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
        }
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, NewsAlarmReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, ALARM_REQUEST, i, flags)
    }
}

/** Fires at UTC midnight, saves the feed, then sets tomorrow's alarm. */
class NewsAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        NewsSaver.save(app) {
            NewsSaver.ensureScheduled(app)
        }
    }
}
