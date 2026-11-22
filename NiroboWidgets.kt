package org.nirobo.browser

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.RemoteViews
import org.json.JSONObject
import java.io.File

/**
 * Home screen widgets. All of them are small (one row high).
 *
 * - Card widgets (battery, speed, news, voice): an icon, a title and a line of text. One tap = one job.
 * - Search box: type or tap the microphone, in one widget.
 * - Quick bar: six buttons in a row (search, voice, new tab, private tab, downloads, bookmarks).
 *
 * A tap opens Nirobo Browser and does the job named by an action word
 * (MainActivity.handleWidget understands these words).
 */

/** The tap that opens the browser and says what to do. Each action word gets its own PendingIntent. */
internal fun widgetTap(ctx: Context, action: String): PendingIntent {
    val i = Intent(ctx, MainActivity::class.java)
    i.putExtra("nirobo_widget", action)
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        ctx, action.hashCode(), i,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

internal abstract class NiroboWidget : AppWidgetProvider() {

    /** The job the browser does after a tap. */
    abstract val action: String
    abstract fun icon(ctx: Context): String
    abstract fun title(ctx: Context): String
    abstract fun subtitle(ctx: Context): String

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // plugging in, unplugging and low battery refresh the charging widget at once
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_BATTERY_OKAY -> refresh(context)
        }
    }

    private fun refresh(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, this::class.java))
        for (id in ids) mgr.updateAppWidget(id, views(ctx))
    }

    private fun views(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_card)
        v.setTextViewText(R.id.w_icon, icon(ctx))
        v.setTextViewText(R.id.w_title, title(ctx))
        v.setTextViewText(R.id.w_sub, subtitle(ctx))
        v.setOnClickPendingIntent(R.id.w_root, widgetTap(ctx, action))
        return v
    }
}

/** A search box with a microphone inside: tap the box to type, tap the microphone to speak. */
internal class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val v = RemoteViews(context.packageName, R.layout.widget_searchbox)
            v.setTextViewText(R.id.w_sbox, "🔍  Search with Nirobo")
            v.setTextViewText(R.id.w_smic, "🎤")
            v.setOnClickPendingIntent(R.id.w_sbox, widgetTap(context, "search"))
            v.setOnClickPendingIntent(R.id.w_smic, widgetTap(context, "voice"))
            appWidgetManager.updateAppWidget(id, v)
        }
    }
}

/** Six quick buttons in one row. */
internal class QuickBarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val buttons = listOf(
            Triple(R.id.b_1, "🔍", "search"),
            Triple(R.id.b_2, "🎤", "voice"),
            Triple(R.id.b_3, "＋", "newtab"),
            Triple(R.id.b_4, "🕶", "private"),
            Triple(R.id.b_5, "↓", "downloads"),
            Triple(R.id.b_6, "★", "bookmarks")
        )
        for (id in appWidgetIds) {
            val v = RemoteViews(context.packageName, R.layout.widget_bar)
            for ((view, glyph, act) in buttons) {
                v.setTextViewText(view, glyph)
                v.setOnClickPendingIntent(view, widgetTap(context, act))
            }
            appWidgetManager.updateAppWidget(id, v)
        }
    }
}

/** Voice assistant: tap and speak. */
internal class AssistantWidget : NiroboWidget() {
    override val action = "voice"
    override fun icon(ctx: Context) = "🎤"
    override fun title(ctx: Context) = "Voice search"
    override fun subtitle(ctx: Context) = "Tap and speak"
}

/** Battery level and state. */
internal class ChargeWidget : NiroboWidget() {
    override val action = "charge"

    private fun battery(ctx: Context): Pair<Int, String> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val text = when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            else -> "On battery"
        }
        return Pair(pct, text)
    }

    override fun icon(ctx: Context) = if (battery(ctx).second == "Charging") "⚡" else "🔋"
    override fun title(ctx: Context) = "Battery " + battery(ctx).first + "%"
    override fun subtitle(ctx: Context) = battery(ctx).second + " - tap for details"
}

/** Internet speed test. */
internal class SpeedWidget : NiroboWidget() {
    override val action = "speed"
    override fun icon(ctx: Context) = "🚀"
    override fun title(ctx: Context) = "Internet speed"
    override fun subtitle(ctx: Context) = "Tap to run a test"
}

/** The newest saved headline. */
internal class NewsWidget : NiroboWidget() {
    override val action = "news"

    private fun saved(ctx: Context): Pair<String, Int> {
        return try {
            val o = JSONObject(File(ctx.filesDir, "news_saved.json").readText())
            val a = o.optJSONArray("items")
            if (a == null || a.length() == 0) Pair("", 0)
            else Pair(a.optJSONObject(0)?.optString("t") ?: "", a.length())
        } catch (e: Exception) {
            Pair("", 0)
        }
    }

    override fun icon(ctx: Context) = "📰"
    override fun title(ctx: Context): String {
        val t = saved(ctx).first
        return if (t.isEmpty()) "Saved news" else t
    }

    override fun subtitle(ctx: Context): String {
        val n = saved(ctx).second
        return if (n == 0) "Open the browser once to save the news" else "$n stories - works offline"
    }
}
