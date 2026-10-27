package uk.newbroman.dune

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

const val TERMUX = "com.termux"
const val TERMIUS = "com.server.auditor.ssh.client"
const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.isInstalled(pkg: String): Boolean {
    if (packageManager.getLaunchIntentForPackage(pkg) != null) return true
    return try { packageManager.getApplicationInfo(pkg, 0); true } catch (e: Exception) { false }
}

fun Context.tryStart(i: Intent, fail: String? = null): Boolean = try {
    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: Exception) { fail?.let { toast(it) }; false }

fun Context.launchPackage(pkg: String): Boolean {
    val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
    return tryStart(i)
}

object Actions {
    fun openClock(ctx: Context) { ctx.tryStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }

    fun openCalendar(ctx: Context) {
        ctx.tryStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
    }

    fun webSearch(ctx: Context, q: String) {
        val ok = ctx.tryStart(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q))
        if (!ok) ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
    }

    const val SPHERES_URL = "https://newbroman.github.io/Music_of_the_Spheres/"

    /** The installed Music of the Spheres app if there is one, otherwise the website. */
    fun openSpheres(ctx: Context) {
        val la = ctx.getSystemService(android.content.pm.LauncherApps::class.java)
        val app = la.getActivityList(null, android.os.Process.myUserHandle())
            .firstOrNull { it.label.toString().contains("spheres", true) && !it.componentName.packageName.startsWith("org.chromium.webapk") }
        if (app == null || !ctx.launchPackage(app.componentName.packageName))
            ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(SPHERES_URL)))
    }

    /** Turn-by-turn directions in Google Maps (or any maps app). */
    fun navigate(ctx: Context, dest: String) {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=d")).setPackage(MAPS)
        if (!ctx.tryStart(nav)) ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))), "No maps app found")
    }

    /** Open Google Translate with [text] filled in. */
    fun translate(ctx: Context, text: String) {
        val viaProcess = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true).setPackage(TRANSLATE)
        if (ctx.tryStart(viaProcess)) return
        val viaShare = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(TRANSLATE)
        if (ctx.tryStart(viaShare)) return
        ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=pl&text=" + Uri.encode(text))))
    }

    fun homeSettings(ctx: Context) {
        if (!ctx.tryStart(Intent(Settings.ACTION_HOME_SETTINGS))) ctx.tryStart(Intent(Settings.ACTION_SETTINGS))
    }

    fun notificationAccess(ctx: Context) {
        ctx.tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Open Settings › Notifications › Device & app notifications")
    }

    fun appSettings(ctx: Context) {
        ctx.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    fun isDefaultHome(ctx: Context): Boolean {
        val r = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )
        return r?.activityInfo?.packageName == ctx.packageName
    }

    /** Pull down the notification shade (works on most phones). */
    @SuppressLint("WrongConstant")
    fun expandNotifications(ctx: Context) {
        try {
            val sb = ctx.getSystemService("statusbar")
            sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Throwable) { }
    }

    fun openNotification(act: Activity, n: NotifItem) {
        val pi = n.intent
        if (pi != null) {
            try {
                val opts = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle() else null
                pi.send(act, 0, null, null, null, null, opts)
                if (n.autoCancel) NotifService.instance?.cancelNotification(n.key)
                return
            } catch (_: Exception) { }
        }
        act.launchPackage(n.pkg)
    }

    // ---------- Termux ----------

    /** Run [command] in a new visible Termux session. Blank command just opens Termux. */
    fun termux(act: Activity, command: String) {
        if (!act.isInstalled(TERMUX)) { act.toast("Termux isn't installed"); return }
        if (command.isBlank()) { act.launchPackage(TERMUX); return }
        if (!termuxSupportsRunCommand(act)) {
            // Google Play Termux has no RUN_COMMAND: copy the command and open Termux to paste it.
            val cm = act.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Termux command", command))
            act.toast("Command copied – long-press in Termux, Paste, Enter")
            act.launchPackage(TERMUX)
            return
        }
        if (act.checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            (act as? MainActivity)?.requestPerm(TERMUX_PERMISSION) { ok ->
                if (ok) termux(act, command) else {
                    act.toast("Allow \"Run commands in Termux environment\" for Dune")
                    appSettings(act)
                }
            }
            return
        }
        val i = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        try {
            act.startService(i)
            // Bring Termux forward ourselves so it works without its "display over other apps" permission.
            Handler(Looper.getMainLooper()).postDelayed({ act.launchPackage(TERMUX) }, 400)
        } catch (e: SecurityException) {
            act.toast("Termux refused: set allow-external-apps = true in ~/.termux/termux.properties")
        } catch (e: Exception) {
            act.toast("Couldn't reach Termux: ${e.message}")
        }
    }

    /** True when the installed Termux defines the RUN_COMMAND permission (F-Droid/GitHub builds). */
    fun termuxSupportsRunCommand(ctx: Context) = try {
        ctx.packageManager.getPermissionInfo(TERMUX_PERMISSION, 0); true
    } catch (e: Exception) { false }

    // ---------- L14 ----------

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun sshCommand(s: Store, remote: String? = null): String {
        val port = s.l14Port.value.trim().ifEmpty { "22" }
        val target = "${s.l14User.value.trim()}@${s.l14Host.value.trim()}"
        return if (remote == null) "ssh -p $port $target"
        else "ssh -t -p $port $target ${q(remote)}"
    }

    suspend fun reachable(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try { Socket().use { it.connect(InetSocketAddress(host, port), 1500) }; true } catch (e: Exception) { false }
    }

    /** Wake-on-LAN magic packet to the local broadcast address. */
    suspend fun wake(mac: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val hex = mac.split(':', '-', '.').joinToString("").trim()
            require(hex.length == 12)
            val m = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val pkt = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) pkt[i] = 0xFF.toByte()
            for (i in 6 until pkt.size) pkt[i] = m[(i - 6) % 6]
            DatagramSocket().use { s ->
                s.broadcast = true
                for (port in intArrayOf(9, 7)) s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName("255.255.255.255"), port))
            }
            true
        } catch (e: Exception) { false }
    }
}
