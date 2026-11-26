package uk.newbroman.dune

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Good morning", "brief me": the day in one go. */
object Brief : Cmd()

/**
 * The daily briefing: weather, today's calendar, reminders due today, open tasks and messages since last
 * night – said aloud. Ask for it any time ("good morning", "brief me", "what do I need to know today"), or
 * have it ready each morning: Settings › Dune AI › Morning briefing puts up a notification at that time,
 * and tapping it reads the briefing out.
 */
object Briefing {
    const val FIRE = "uk.newbroman.dune.BRIEFING"
    private const val CHANNEL = "dune_briefing"
    private const val NOTE = 7072

    fun parse(s: String): Cmd? =
        if (Regex("^(?:good morning|morning|good afternoon|good evening)(?: dune| computer)?$|^(?:brief me|give me (?:my|a|the) (?:briefing|rundown|run-down|run down)|(?:my |the |daily |morning |today'?s )+briefing|briefing|" +
                "what do i need to know(?: today| this morning)?|what'?s happening today|run me through (?:my|the) day|what'?s on my plate(?: today)?|rundown)$").matches(s)) Brief else null

    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

    /** The briefing itself, built from what's on the phone. Each part is left out if it can't be had. */
    suspend fun compose(act: MainActivity, vm: LauncherVM): String {
        val now = LocalDateTime.now(); val today = now.toLocalDate()
        val parts = ArrayList<String>()
        parts += (when (now.hour) { in 4..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }) + ", Martin. It's ${today.format(dayFmt)}."
        if (Geo.hasPermission(act)) runCatching { Phone.weather(act, PhoneCmd.Weather(0, null, false)) }.getOrNull()?.let { parts += "$it." }
        if (act.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
            runCatching { Phone.agenda(act, PhoneCmd.Agenda(0)) }.getOrNull()?.let { parts += it.replaceFirst("today:", "today –") + "." }
        val z = ZoneId.systemDefault()
        val dune = Alarms.load(act).map { it to LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.at), z) }.filter { it.second.toLocalDate() == today && it.second.isAfter(now) }
        val rem = dune.filter { it.first.kind == Reminders.REMINDER }.sortedBy { it.second }
        if (rem.isNotEmpty()) parts += "Reminders today: " + rem.joinToString("; ") { "%02d:%02d %s".format(it.second.hour, it.second.minute, it.first.label ?: "reminder") } + "."
        val open = vm.store.tasks.filter { !it.done }
        if (open.isNotEmpty()) parts += "${open.size} open task${if (open.size == 1) "" else "s"}" + ": " + open.take(3).joinToString(", ") { it.text } + if (open.size > 3) ", and more." else "."
        val since = System.currentTimeMillis() - if (now.hour < 12) 12 * 3600_000L else 6 * 3600_000L
        val msgs = InboxLog.all(act).filter { it.time >= since }
        if (msgs.isNotEmpty()) {
            val who = msgs.map { it.title.ifBlank { it.app } }.distinct()
            parts += "${msgs.size} message${if (msgs.size == 1) "" else "s"} since ${if (now.hour < 12) "last night" else "earlier"}, from " +
                who.take(4).joinToString(", ") + (if (who.size > 4) " and others" else "") + ". Say “catch me up” to hear them."
        }
        Alarms.load(act).filter { it.kind != Reminders.REMINDER }.map { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.at), z) }
            .filter { it.toLocalDate() == today.plusDays(1) }.minOrNull()?.let { parts += "Alarm tomorrow at %02d:%02d.".format(it.hour, it.minute) }
        if (parts.size == 1) parts += "Nothing in the calendar, no reminders and no new messages."
        return parts.joinToString(" ")
    }

    fun run(act: MainActivity, vm: LauncherVM, speak: Boolean): String {
        act.lifecycleScope.launch {
            val b = runCatching { compose(act, vm) }.getOrElse { "Couldn't put the briefing together (${it.javaClass.simpleName})" }
            vm.assistantReply = b
            if (speak) Speaker.speak(act, b)
            if (Brain.ready(act)) Brain.Chat.add("Brief me", b)
        }
        return "Putting your briefing together…"
    }

    // ---- every morning

    /** "07:30", or null when the morning briefing is off. */
    fun time(ctx: Context): String? = ctx.getSharedPreferences("dune_ai", 0).getString("briefTime", null)
    fun setTime(ctx: Context, t: String?) { ctx.getSharedPreferences("dune_ai", 0).edit().putString("briefTime", t).apply(); schedule(ctx) }

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 7072, Intent(ctx, AlarmReceiver::class.java).setAction(FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Set (or clear) the next morning's notification. Not an alarm clock: it doesn't show in the status bar. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pi(ctx))
        val t = time(ctx)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return
        val now = LocalDateTime.now()
        var at = LocalDate.now().atTime(t); if (!at.isAfter(now)) at = at.plusDays(1)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi(ctx))
    }

    /** It's briefing time: a quiet notification; tapping it opens Dune and reads the briefing. */
    fun ready(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, NOTE, Intent(ctx, MainActivity::class.java).putExtra("briefing", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTE, android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("Good morning").setContentText("Tap to hear your briefing")
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(6 * 3600_000L).build())
    }
}
