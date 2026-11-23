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
internal class Store(ctx: Context, space: String = "") {

    // a space ("work") gets its own file, because two processes must never write the same file.
    // On first use it copies the settings (not bookmarks, history, tabs or downloads) from the main file.
    private val p: SharedPreferences = run {
        val app = ctx.applicationContext
        if (space.isEmpty()) {
            app.getSharedPreferences("nirobo_store", Context.MODE_PRIVATE)
        } else {
            val sp = app.getSharedPreferences("nirobo_store_$space", Context.MODE_PRIVATE)
            if (!sp.getBoolean("seeded", false)) {
                val skip = setOf(
                    "bookmarks", "history", "session", "session_i", "closed_tabs", "downloads",
                    "sitePerms", "sites", "cur_max", "cur_min", "news_saved_at"
                )
                val ed = sp.edit()
                try {
                    for ((k, v) in app.getSharedPreferences("nirobo_store", Context.MODE_PRIVATE).all) {
                        if (k in skip) continue
                        when (v) {
                            is Boolean -> ed.putBoolean(k, v)
                            is Int -> ed.putInt(k, v)
                            is Long -> ed.putLong(k, v)
                            is Float -> ed.putFloat(k, v)
                            is String -> ed.putString(k, v)
                        }
                    }
                } catch (e: Exception) {
                }
                ed.putBoolean("seeded", true).apply()
            }
            sp
        }
    }

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

    /** Third-party cookies. Off is safer. Some work and school sign-ins need them. */
    var thirdCookies: Boolean
        get() = p.getBoolean("third_cookies", false)
        set(v) = p.edit().putBoolean("third_cookies", v).apply()

    /** True = the address bar sits at the bottom of the screen (Chrome has this choice too). */
    var barBottom: Boolean
        get() = p.getBoolean("bar_bottom", false)
        set(v) = p.edit().putBoolean("bar_bottom", v).apply()

    /** Text size of web pages in percent (Accessibility). */
    /** Language for voice search: "en-US" or "bn-BD". */
    var voiceLang: String
        get() = p.getString("voice_lang", "en-US") ?: "en-US"
        set(v) = p.edit().putString("voice_lang", v).apply()

    var adPatterns: Boolean
        get() = p.getBoolean("ad_patterns", true)
        set(v) = p.edit().putBoolean("ad_patterns", v).apply()

    var adHide: Boolean
        get() = p.getBoolean("ad_hide", true)
        set(v) = p.edit().putBoolean("ad_hide", v).apply()

    var popupBlock: Boolean
        get() = p.getBoolean("popup_block", true)
        set(v) = p.edit().putBoolean("popup_block", v).apply()

    var cleanLinks: Boolean
        get() = p.getBoolean("clean_links", true)
        set(v) = p.edit().putBoolean("clean_links", v).apply()

    var localShield: Boolean
        get() = p.getBoolean("local_shield", true)
        set(v) = p.edit().putBoolean("local_shield", v).apply()

    var dangerFiles: Boolean
        get() = p.getBoolean("danger_files", true)
        set(v) = p.edit().putBoolean("danger_files", v).apply()

    /** Highest and lowest battery current seen so far (mA), for the charging screen. */
    var curMax: Int
        get() = p.getInt("cur_max", 0)
        set(v) = p.edit().putInt("cur_max", v).apply()

    var curMin: Int
        get() = p.getInt("cur_min", 0)
        set(v) = p.edit().putInt("cur_min", v).apply()

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

    // ---------------- per-site permissions (camera, microphone, location) ----------------

    private fun permMap(): JSONObject = try {
        JSONObject(p.getString("sitePerms", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    /** "allow", "deny" or "" (not asked yet) for kind "cam", "mic" or "loc" on this host. */
    fun sitePerm(host: String, kind: String): String = try {
        permMap().optJSONObject(host)?.optString(kind, "") ?: ""
    } catch (e: Exception) {
        ""
    }

    fun setSitePerm(host: String, kind: String, value: String) {
        val m = permMap()
        try {
            val o = m.optJSONObject(host) ?: JSONObject()
            if (value.isEmpty()) o.remove(kind) else o.put(kind, value)
            m.put(host, o)
        } catch (e: Exception) {
        }
        p.edit().putString("sitePerms", m.toString()).apply()
    }

    fun clearSitePerms() {
        p.edit().remove("sitePerms").apply()
    }

    // ---------------- bookmarks ----------------

    /** Search engines the person added: [{id, name, url, icon}] */
    fun engines(): JSONArray = try {
        JSONArray(p.getString("engines", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    fun saveEngines(a: JSONArray) {
        p.edit().putString("engines", a.toString()).apply()
    }

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

    // ---------------- session restore and recently closed tabs ----------------

    /** Open the tabs from last time when the app starts, like Chrome does. */
    var restoreTabs: Boolean
        get() = p.getBoolean("restore_tabs", true)
        set(v) = p.edit().putBoolean("restore_tabs", v).apply()

    fun saveSession(urls: List<String>, index: Int) {
        val a = JSONArray()
        for (u in urls) a.put(u)
        p.edit().putString("session", a.toString()).putInt("session_i", index).apply()
    }

    fun sessionUrls(): List<String> = try {
        val a = JSONArray(p.getString("session", "[]") ?: "[]")
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }

    fun sessionIndex(): Int = p.getInt("session_i", 0)

    fun closedTabs(): JSONArray = try {
        JSONArray(p.getString("closed_tabs", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    /** Newest first, at most 10, no repeats. */
    fun pushClosed(url: String, title: String) {
        if (url.isEmpty() || url == "about:blank" || url == homeUrl) return
        val a = closedTabs()
        val out = JSONArray()
        val o = JSONObject()
        o.put("u", url)
        o.put("t", if (title.isEmpty()) url else title)
        out.put(o)
        for (i in 0 until a.length()) {
            if (out.length() >= 10) break
            val e = a.optJSONObject(i) ?: continue
            if (e.optString("u") != url) out.put(e)
        }
        p.edit().putString("closed_tabs", out.toString()).apply()
    }

    /** Takes the newest closed tab off the list and returns its address (null when there is none). */
    fun popClosed(): String? {
        val a = closedTabs()
        if (a.length() == 0) return null
        val first = a.optJSONObject(0)?.optString("u")
        val out = JSONArray()
        for (i in 1 until a.length()) a.optJSONObject(i)?.let { out.put(it) }
        p.edit().putString("closed_tabs", out.toString()).apply()
        return first
    }

    fun clearClosed() {
        p.edit().putString("closed_tabs", "[]").apply()
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

    /** Email sites the person added: [{id, name, url, icon}] */
    fun mailSites(): JSONArray = try {
        JSONArray(p.getString("mailsites", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    fun saveMailSites(a: JSONArray) {
        p.edit().putString("mailsites", a.toString()).apply()
    }

    fun removeDownload(id: Long) {
        val a = downloads()
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optLong("id", -1L) != id) out.put(o)
        }
        p.edit().putString("downloads", out.toString()).apply()
    }

    fun downloadUrl(id: Long): String {
        val a = downloads()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optLong("id", -1L) == id) return o.optString("u")
        }
        return ""
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
