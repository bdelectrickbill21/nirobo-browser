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
            try {
                val c = URL(URL_NEWS).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 25000
                c.setRequestProperty("User-Agent", "NiroboBrowser/1.0 (Android)")
                c.setRequestProperty("Accept", "application/json")
                if (c.responseCode == 200) {
                    val text = c.inputStream.bufferedReader().readText()
                    val parsed = JSONObject(text)
                    val items = parsed.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        val keep = JSONArray()
                        val n = minOf(items.length(), KEEP)
                        for (i in 0 until n) {
                            val o = items.optJSONObject(i) ?: continue
                            val slim = JSONObject()
                            slim.put("t", o.optString("t"))
                            slim.put("u", o.optString("u"))
                            slim.put("d", o.optString("d"))
                            slim.put("s", o.optString("s"))
                            slim.put("i", o.optString("i"))
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
                        ok = true
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
                ok = false
            }
            running = false
            done(ok)
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
