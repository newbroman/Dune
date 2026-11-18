package uk.newbroman.dune

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable

/**
 * Settings › Voice commands check: for each thing you might ask Dune, is everything it needs actually
 * on – the permission granted, the special access turned on, the app it hands over to installed? Read
 * live from the phone each time the page is shown; a ✗ row is a button that opens the fix.
 */
object VoiceCheck {
    data class Row(val what: String, val ok: Boolean, val why: String, val fix: ((MainActivity) -> Unit)?)

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun handles(ctx: Context, i: Intent) = ctx.packageManager.resolveActivity(i, 0) != null
    private fun installed(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null
    private fun perm(p: String): (MainActivity) -> Unit = { act -> act.requestPerm(p) { } }
    private fun appSettings(act: MainActivity) =
        act.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName)))

    fun rows(ctx: Context): List<Pair<String, List<Row>>> {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val clock = handles(ctx, Intent(AlarmClock.ACTION_SET_ALARM))
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        val notes = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        val fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        val contacts = granted(ctx, Manifest.permission.READ_CONTACTS)
        val listener = NotifService.instance != null
        return listOf(
            "Alarms and timers" to listOf(
                Row("A Clock app that takes alarms", clock, if (clock) "“set an alarm for 7”, timers" else "no app answers alarm requests – Dune will ring one-offs itself", null),
                Row("Exact alarms", exact, "for alarms Dune rings itself (a day more than 24 h ahead)",
                    { act -> act.tryStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + act.packageName))) }),
                Row("Notifications", notes, "Dune's own alarms ring as a notification", perm(Manifest.permission.POST_NOTIFICATIONS)),
                Row("Full screen over the lock screen", fullScreen, "so Dune's alarm wakes the screen, not just a banner",
                    { act -> if (Build.VERSION.SDK_INT >= 34) act.tryStart(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + act.packageName))) }),
            ),
            "Calls and messages" to listOf(
                Row("Contacts", contacts, "“call Anna”, “text Tom…”, “WhatsApp Mum…”", perm(Manifest.permission.READ_CONTACTS)),
                Row("Make calls", granted(ctx, Manifest.permission.CALL_PHONE), "rings straight away (without it, the dialler opens with the number in)", perm(Manifest.permission.CALL_PHONE)),
                Row("Send texts", granted(ctx, Manifest.permission.SEND_SMS), "“text Tom running late” sends without opening Messages", perm(Manifest.permission.SEND_SMS)),
                Row("Notification access", listener, "read messages aloud, “reply…”, play/pause/next, Comms",
                    { act -> Actions.notificationAccess(act) }),
                Row("WhatsApp installed", installed(ctx, "com.whatsapp"), "WhatsApp messages and calls", null),
                Row("Dune split screen (accessibility)", SplitService.enabled(), "presses WhatsApp's Send for a new message to someone who hasn't messaged you",
                    { act -> act.tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
            ),
            "Listening" to listOf(
                Row("Speech recognition", SpeechRecognizer.isRecognitionAvailable(ctx) || handles(ctx, Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)),
                    "the Speak button (Google's speech service)", null),
                Row("Microphone", granted(ctx, Manifest.permission.RECORD_AUDIO), "the “OK Computer” wake word in Drive mode", perm(Manifest.permission.RECORD_AUDIO)),
                Row("Open over other apps", Settings.canDrawOverlays(ctx), "the wake word brings Dune forward from another app",
                    { act -> act.tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName))) }),
            ),
            "Getting about" to listOf(
                Row("Location switched on", ctx.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true,
                    "the phone's own Location switch (quick settings)", { act -> act.tryStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }),
                Row("Location", granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION), "maps, Drive mode, “what's the weather”, “where am I”", perm(Manifest.permission.ACCESS_FINE_LOCATION)),
                Row("Google Maps", installed(ctx, MAPS), if (installed(ctx, MAPS)) "“navigate to…”, “take me home”" else "“navigate to…” falls back to any maps app", null),
                Row("Bluetooth", Drive.hasBtPermission(ctx), "Drive mode starts when the car connects", perm(Manifest.permission.BLUETOOTH_CONNECT)),
            ),
            "Phone settings by voice" to listOf(
                Row("Torch", ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH), "“torch on”, “turn off the flashlight”", null),
                Row("Do Not Disturb access", nm.isNotificationPolicyAccessGranted, "“silent mode”, “do not disturb on”",
                    { act -> act.tryStart(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }),
                Row("Modify system settings", Settings.System.canWrite(ctx), "“brighter”, “brightness to 50%”",
                    { act -> act.tryStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + act.packageName))) }),
                Row("Calendar", granted(ctx, Manifest.permission.READ_CALENDAR), "“what's on today”, “my next appointment”", perm(Manifest.permission.READ_CALENDAR)),
                Row("Dune split screen (accessibility)", SplitService.enabled(), "“lock the phone”, “take a screenshot”",
                    { act -> act.tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
            ),
            "Other apps it hands over to" to listOf(
                Row("Spotify", installed(ctx, SPOTIFY), "“play … on Spotify”", null),
                Row("Google Translate", installed(ctx, TRANSLATE), if (installed(ctx, TRANSLATE)) "“translate…”" else "“translate…” uses the website instead", null),
            ),
        )
    }

    @Composable
    fun Panel(vm: LauncherVM) {
        val act = LocalContext.current as MainActivity
        var tick by remember { mutableIntStateOf(0) }
        // re-read whenever Dune comes back from a settings screen
        val resume = vm.resumeTick
        val groups = remember(tick, resume) { rows(act) }
        val bad = groups.sumOf { g -> g.second.count { !it.ok } }
        Text(if (bad == 0) "Everything Dune's voice commands need is on." else "$bad thing${if (bad == 1) "" else "s"} to turn on – tap a ✗ row to fix it.",
            color = if (bad == 0) Fg else Sand, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
        groups.forEach { (title, rows) ->
            Text(title, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().then(if (!r.ok && r.fix != null) Modifier.clickable { r.fix.invoke(act); tick++ } else Modifier)
                    .padding(vertical = 5.dp)) {
                    Text(if (r.ok) "✓" else "✗", color = if (r.ok) Color(0xFF7FB77E) else Color(0xFFE07A5F), fontSize = 16.sp,
                        modifier = Modifier.width(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.what + if (!r.ok && r.fix != null) "  ›" else "", color = Fg, fontSize = 15.sp)
                        Text(r.why, color = Dim, fontSize = 13.sp)
                    }
                }
            }
        }
        LinkRow("All of Dune's permissions (Android settings)") { appSettings(act) }
    }
}
