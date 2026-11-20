package org.nirobo.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the browser remembers: settings, bookmarks, history, downloads,
 * and the per-site ad-shield switches.
 *
 * All of it lives in one SharedPreferences file as JSON, so there is no database
 * to migrate and nothing to leak between the normal and the private browser.
 */
internal class Store(ctx: Context) {

    private val p: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("nirobo_store", Context.MODE_PRIVATE)

    // ---------------- settings ----------------

    /** 0 = follow the phone, 1 = always light, 2 = always dark. */
    var theme: Int
        get() = p.getInt("theme", 0)
        set(v) = p.edit().putInt("theme", v).apply()

    /** The master ad-shield switch. Per-site switches start from this value. */
    var shieldEnabled: Boolean
        get() = p.getBoolean("shield", true)
        set(v) = p.edit().putBoolean("shield", v).apply()

    /** Hide cookie-consent banners and auto-answer them. */
    var cookieBlock: Boolean
        get() = p.getBoolean("cookie_block", true)
        set(v) = p.edit().putBoolean("cookie_block", v).apply()

    /** Reader-mode text size in sp. */
    var readerSize: Int
        get() = p.getInt("reader_size", 18)
        set(v) = p.edit().putInt("reader_size", v).apply()

    var searchUrl: String
        get() = p.getString("search_url", "https://nirobo.pages.dev/?q=") ?: "https://nirobo.pages.dev/?q="
        set(v) = p.edit().putString("search_url", v).apply()

    var homeUrl: String
        get() = p.getString("home_url", "https://nirobo.pages.dev/") ?: "https://nirobo.pages.dev/"
        set(v) = p.edit().putString("home_url", v).apply()

    /** True = the address bar sits at the bottom of the screen (Chrome has this choice too). */
    var barBottom: Boolean
        get() = p.getBoolean("bar_bottom", false)
        set(v) = p.edit().putBoolean("bar_bottom", v).apply()

    /** Text size of web pages in percent (Accessibility). */
    var textZoom: Int
        get() = p.getInt("text_zoom", 100)
        set(v) = p.edit().putInt("text_zoom", v).apply()

    /** Block screenshots and screen mirroring (shown as black). */
    var secureScreen: Boolean
        get() = p.getBoolean("secure_screen", false)
        set(v) = p.edit().putBoolean("secure_screen", v).apply()

    /** Look for a new version of the app whenever it opens. */
    var autoUpdate: Boolean
        get() = p.getBoolean("auto_update", true)
        set(v) = p.edit().putBoolean("auto_update", v).apply()

    /** Remember the last NewsHub download time (epoch millis). */
    var newsSavedAt: Long
        get() = p.getLong("news_saved_at", 0L)
        set(v) = p.edit().putLong("news_saved_at", v).apply()

    // ---------------- per-site ad shield ----------------

    private fun siteMap(): JSONObject = try {
        JSONObject(p.getString("sites", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    /** Is the shield on for this host? Falls back to the master switch. */
    fun siteShield(host: String): Boolean = try {
        val m = siteMap()
        if (m.has(host)) m.getBoolean(host) else shieldEnabled
    } catch (e: Exception) {
        shieldEnabled
    }

    fun setSiteShield(host: String, on: Boolean) {
        val m = siteMap()
        try {
            m.put(host, on)
        } catch (e: Exception) {
        }
        p.edit().putString("sites", m.toString()).apply()
    }

    // ---------------- bookmarks ----------------

    fun bookmarks(): JSONArray = try {
        JSONArray(p.getString("bookmarks", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    fun isBookmarked(url: String): Boolean {
        val a = bookmarks()
        for (i in 0 until a.length()) {
            if (a.optJSONObject(i)?.optString("u") == url) return true
        }
        return false
    }

    fun addBookmark(url: String, title: String) {
        if (url.isEmpty() || isBookmarked(url)) return
        val a = bookmarks()
        val o = JSONObject()
        o.put("u", url)
        o.put("t", if (title.isEmpty()) url else title)
        o.put("ts", System.currentTimeMillis())
        a.put(o)
        p.edit().putString("bookmarks", a.toString()).apply()
    }

    fun removeBookmark(url: String) {
        val a = bookmarks()
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("u") != url) out.put(o)
        }
        p.edit().putString("bookmarks", out.toString()).apply()
    }

    // ---------------- history ----------------

    fun history(): JSONArray = try {
        JSONArray(p.getString("history", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    /** Newest first, one entry per address, capped at 2000. */
    fun addHistory(url: String, title: String) {
        if (url.isEmpty()) return
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("javascript:")) return
        val a = history()
        val out = JSONArray()
        val o = JSONObject()
        o.put("u", url)
        o.put("t", if (title.isEmpty()) url else title)
        o.put("ts", System.currentTimeMillis())
        out.put(o)
        for (i in 0 until a.length()) {
            if (out.length() >= 2000) break
            val e = a.optJSONObject(i) ?: continue
            if (e.optString("u") != url) out.put(e)
        }
        p.edit().putString("history", out.toString()).apply()
    }

    fun clearHistory() {
        p.edit().putString("history", "[]").apply()
    }

    // ---------------- downloads ----------------

    fun downloads(): JSONArray = try {
        JSONArray(p.getString("downloads", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    fun addDownload(id: Long, name: String, url: String) {
        val a = downloads()
        val o = JSONObject()
        o.put("id", id)
        o.put("n", name)
        o.put("u", url)
        o.put("ts", System.currentTimeMillis())
        a.put(o)
        p.edit().putString("downloads", a.toString()).apply()
    }

    fun clearDownloads() {
        p.edit().putString("downloads", "[]").apply()
    }

    // ---------------- generic key/value, used by the about: pages ----------------

    fun getString(key: String, def: String): String = p.getString(key, def) ?: def

    fun putString(key: String, value: String) {
        p.edit().putString(key, value).apply()
    }
}
