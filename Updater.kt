package org.nirobo.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Updates Nirobo Browser from its own GitHub releases.
 *
 * Every build on GitHub publishes two files on the "latest" release:
 *   nirobo-browser.apk   the app
 *   version.json         its version number, size and SHA-256 fingerprint
 *
 * When the app opens it reads version.json. If the number is higher than the installed
 * one, it asks: "Update now?". On yes it downloads the APK, checks the fingerprint, and
 * hands it to Android's installer. Android only accepts the update if it is signed with
 * the same key as the installed app, so nobody else can replace the app this way.
 *
 * The repo name is written into the app at build time (assets/update_repo.txt).
 * The repo must be public, otherwise GitHub does not let the app download the files.
 */
internal object Updater {

    private const val FALLBACK_REPO = "bdelectrickbill21/nirobo-browser"
    private const val CHECK_EVERY_MS = 6L * 3600L * 1000L
    private const val MAX_APK_BYTES = 150L * 1024L * 1024L   // refuse absurdly big files

    private val pool = Executors.newSingleThreadExecutor()

    @Volatile
    private var busy = false

    class Info(
        val code: Long,
        val name: String,
        val apk: String,
        val sha: String,
        val size: Long,
        val notes: String
    )

    fun repo(ctx: Context): String = try {
        val r = ctx.assets.open("update_repo.txt").bufferedReader().use { it.readText() }.trim()
        if (r.isEmpty()) FALLBACK_REPO else r
    } catch (e: Exception) {
        FALLBACK_REPO
    }

    private fun feedUrl(ctx: Context): String =
        "https://github.com/" + repo(ctx) + "/releases/download/latest/version.json"

    fun installedCode(ctx: Context): Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    private fun toast(ctx: Context, text: String) {
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    /**
     * Looks for a newer build. manual = true when the person asked (menu or about:update):
     * then it always checks and always says what it found. Otherwise it checks at most
     * once every 6 hours and stays quiet unless there is something new.
     */
    fun check(act: Activity, manual: Boolean) {
        val store = Store(act)
        val now = System.currentTimeMillis()
        if (!manual) {
            if (!store.autoUpdate) return
            val last = store.getString("upd_last", "0").toLongOrNull() ?: 0L
            if (now - last < CHECK_EVERY_MS) return
        }
        if (busy) {
            if (manual) toast(act, "Already checking...")
            return
        }
        busy = true
        if (manual) toast(act, "Checking for updates...")
        val url = feedUrl(act)
        // the app file must come from THIS repo's releases, nowhere else
        val apkPrefix = "https://github.com/" + repo(act) + "/releases/download/"
        val have = installedCode(act)

        pool.execute {
            var info: Info? = null
            var error = ""
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "NiroboBrowser-Updater")
                val code = c.responseCode
                if (code == 200) {
                    val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val apk = o.optString("apk")
                    val shaOk = Regex("[0-9a-fA-F]{64}").matches(o.optString("sha256"))
                    if (apk.startsWith(apkPrefix) && shaOk && o.optLong("size") in 1L..MAX_APK_BYTES) {
                        info = Info(
                            o.optLong("versionCode"), o.optString("versionName"), apk,
                            o.optString("sha256").lowercase(), o.optLong("size"), o.optString("notes")
                        )
                    } else {
                        error = "The update note looks wrong"
                    }
                } else if (code == 404) {
                    error = "No update found (the repo may be private, or no build is published yet)"
                } else {
                    error = "The update server answered $code"
                }
                c.disconnect()
            } catch (e: Exception) {
                error = "Could not reach the update server"
            }

            val got = info
            store.putString("upd_last", System.currentTimeMillis().toString())
            if (got != null) {
                store.putString("upd_latest", got.name + " (" + got.code + ")")
                store.putString(
                    "upd_status",
                    if (got.code > have) "A newer version is ready" else "You have the newest version"
                )
            } else {
                store.putString("upd_status", error)
            }
            busy = false

            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (got == null) {
                    if (manual) toast(act, error)
                } else if (got.code > have) {
                    ask(act, got)
                } else if (manual) {
                    toast(act, "You have the newest version")
                }
            }
        }
    }

    private fun ask(act: Activity, info: Info) {
        val notes = if (info.notes.isEmpty()) "" else "\n\n" + info.notes
        AlertDialog.Builder(act)
            .setTitle("Update available")
            .setMessage("Nirobo Browser " + info.name + " is ready." + notes)
            .setPositiveButton("Update now") { _, _ -> download(act, info) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(act: Activity, info: Info) {
        // Android asks you once to allow this app to install apps
        if (!act.packageManager.canRequestPackageInstalls()) {
            toast(act, "Allow Nirobo Browser to install updates, then tap Update again.")
            try {
                act.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
                )
            } catch (e: Exception) {
            }
            return
        }

        val dlg = AlertDialog.Builder(act)
            .setTitle("Downloading update")
            .setMessage("0%")
            .setCancelable(false)
            .show()

        pool.execute {
            var err = ""
            val f = File(act.cacheDir, "nirobo-update.apk")
            try {
                val c = URL(info.apk).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                if (c.responseCode != 200) throw Exception("the download answered " + c.responseCode)
                val total = if (c.contentLengthLong > 0L) c.contentLengthLong else info.size
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var lastPct = -1
                c.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (done > MAX_APK_BYTES) throw Exception("the file is too big")
                            if (total > 0L) {
                                val pct = (done * 100L / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    act.runOnUiThread { dlg.setMessage(pct.toString() + "%") }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                // the fingerprint and the size are always checked (never skipped)
                if (done != info.size) throw Exception("the file size is wrong, please try again")
                val hex = md.digest().joinToString("") { b -> "%02x".format(b) }
                if (hex != info.sha) throw Exception("the file is damaged, please try again")
            } catch (e: Exception) {
                err = e.message ?: "the download failed"
            }
            act.runOnUiThread {
                try {
                    dlg.dismiss()
                } catch (e: Exception) {
                }
                if (err.isNotEmpty()) toast(act, "Update failed: $err") else install(act, f)
            }
        }
    }

    private fun install(act: Activity, f: File) {
        try {
            val installer = act.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) {
                // on newer Android, later updates can install without asking again
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            val session = installer.openSession(id)
            f.inputStream().use { ins ->
                session.openWrite("nirobo.apk", 0L, f.length()).use { out ->
                    ins.copyTo(out)
                    session.fsync(out)
                }
            }
            val i = Intent(act, InstallReceiver::class.java)
            i.setPackage(act.packageName)
            val pending = PendingIntent.getBroadcast(
                act, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
            session.close()
            toast(act, "Installing the update...")
        } catch (e: Exception) {
            toast(act, "Could not start the install: " + (e.message ?: "unknown error"))
        }
    }
}

/** Hears back from Android's installer. */
class InstallReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    private fun confirmIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            i.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants one tap on "Update"
                val confirm = confirmIntent(intent)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Nirobo Browser is updated", Toast.LENGTH_LONG).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(
                    context,
                    "Update failed: " + msg + ". If it mentions a signature or conflict, uninstall Nirobo Browser once and install the new file.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
