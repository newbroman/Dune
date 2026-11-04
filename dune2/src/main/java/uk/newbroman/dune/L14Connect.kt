package uk.newbroman.dune

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * One tap from Dune to a shell on the L14, through Termux.
 *
 * The work is done by two scripts kept in the repo at l14/termux/ and bundled in the APK as assets:
 * `l14` (connect: wake lock, bring Tailscale up – logging in through the browser if it must – then
 * mosh or ssh into a tmux session) and `l14-setup.sh` (one-time install). Dune writes both into Termux
 * with a RUN_COMMAND heredoc, so nothing needs downloading; where that isn't possible (Google Play
 * Termux, or allow-external-apps still off) it copies a `curl | bash` line to paste instead.
 */
object L14Connect {
    const val SCRIPT_ASSET = "termux/l14"
    const val SETUP_ASSET = "termux/l14-setup.sh"
    const val SETUP_URL = "https://raw.githubusercontent.com/newbroman/Dune/main/l14/termux/l14-setup.sh"
    const val TAILSCALE_APP = "com.tailscale.ipn"

    /** Single-quoted for a POSIX shell. */
    fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** The settings the scripts read, as environment assignments: L14_HOST='…' L14_USER='…' L14_PORT='…' TS_APP=1 */
    fun env(host: String, user: String, port: String, tsApp: Boolean): String =
        "L14_HOST=${sq(host.trim())} L14_USER=${sq(user.trim().ifEmpty { "john" })} " +
            "L14_PORT=${sq(port.trim().ifEmpty { "22" })} TS_APP=${if (tsApp) 1 else 0}"

    fun env(s: Store, ctx: Context) = env(s.l14Host.value, s.l14User.value, s.l14Port.value, ctx.isInstalled(TAILSCALE_APP))

    /** What the Connect chip runs in a new Termux session. */
    fun connectCommand(env: String): String =
        "if [ -x \"\$HOME/bin/l14\" ]; then $env \"\$HOME/bin/l14\"; " +
            "else echo; echo 'Dune: ~/bin/l14 is missing - tap \"set up Termux\" under L14 in Dune'; fi"

    /** A heredoc delimiter that is on no line of [bodies]. */
    fun delimiter(vararg bodies: String): String {
        var d = "DUNE_EOF"; var n = 0
        while (bodies.any { b -> b.lineSequence().any { it == d } }) d = "DUNE_EOF_${++n}"
        return d
    }

    /** Writes both scripts into Termux and runs the setup with Dune's settings – no download needed. */
    fun installCommand(script: String, setup: String, env: String): String {
        val d = delimiter(script, setup)
        return "mkdir -p \"\$HOME/bin\" && cat > \"\$HOME/bin/l14\" <<'$d'\n${script.trimEnd('\n')}\n$d\n" +
            "cat > \"\$HOME/bin/l14-setup.sh\" <<'$d'\n${setup.trimEnd('\n')}\n$d\n" +
            "chmod +x \"\$HOME/bin/l14\" \"\$HOME/bin/l14-setup.sh\" && $env L14_LOCAL=1 bash \"\$HOME/bin/l14-setup.sh\""
    }

    /** The one line to paste into Termux when Dune can't run commands there yet. */
    fun pasteLine(env: String) = "curl -fsSL $SETUP_URL | $env bash"

    /** Once, on the L14 with the phone plugged in by USB: stop Android 12+ killing Termux's background processes. */
    val adbLines = listOf(
        "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent\"",
        "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\"",
        "adb shell \"settings put global settings_enable_monitor_phantom_procs false\"",
        "adb shell \"dumpsys deviceidle whitelist +com.termux\"",
    )

    /** On the L14 itself, for mosh (a session that survives the phone sleeping) and tmux. */
    val l14Lines = listOf(
        "sudo apt install -y mosh tmux",
        "sudo ufw allow in on tailscale0 to any port 60000:61000 proto udp   # only if ufw is on",
    )

    // ---------- actions

    private fun asset(ctx: Context, name: String) = ctx.assets.open(name).bufferedReader().use { it.readText() }

    /** The Connect chip: Termux opens and ~/bin/l14 takes it from there. */
    fun connect(act: Activity, s: Store) {
        if (s.l14Host.value.isBlank()) { act.toast("Set the L14's address in Settings › L14 first"); return }
        Actions.termux(act, connectCommand(env(s, act)))
    }

    /** One-time setup, pushed into Termux by Dune. The paste line is on the clipboard too, in case Termux can't be driven yet. */
    fun setup(act: Activity, s: Store) {
        if (s.l14Host.value.isBlank()) { act.toast("Set the L14's address in Settings › L14 first"); return }
        val env = env(s, act)
        val paste = pasteLine(env)
        if (!Actions.termuxSupportsRunCommand(act)) { Actions.termux(act, paste); return }
        act.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Termux command", paste))
        act.toast("Setting Termux up… If Termux shows nothing (allow-external-apps is off): long-press in Termux, Paste, Enter")
        Actions.termux(act, installCommand(asset(act, SCRIPT_ASSET), asset(act, SETUP_ASSET), env), paste)
    }

    fun ignoringBattery(ctx: Context, pkg: String): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(pkg) == true

    /** Android's own "let this app always run in the background?" for [pkg]; else its battery settings page. */
    fun requestNoBatteryLimits(ctx: Context, pkg: String) {
        if (ctx.tryStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))) return
        if (ctx.tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        ctx.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    fun copy(ctx: Context, label: String, text: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        ctx.toast("Copied")
    }
}
