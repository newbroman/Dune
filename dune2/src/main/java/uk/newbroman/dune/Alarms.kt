package uk.newbroman.dune

import kotlinx.coroutines.launch

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * Alarms by voice. What the phone's Clock app can hold goes there (it rings as normal): a time in the
 * next 24 hours, a time "in 40 minutes", a weekly repeat ("every weekday at 7"), each with an optional
 * label. A one-off on a later day ("Thursday at 7") the Clock app can't be given – Android's alarm
 * request has no date – so Dune keeps and rings that one itself (setAlarmClock, so it shows in the
 * status bar and survives doze; re-set after a restart).
 */
object Alarms {
    // ---------- what was said ----------

    /** [days]: repeating weekdays (Calendar.MONDAY…); [on]: a one-off day (0 today, 1 tomorrow, or a DayOfWeek 1-7 + 10). */
    data class Spec(val hour: Int, val minute: Int, val days: List<Int> = emptyList(), val on: Int? = null,
                    val inMinutes: Int? = null, val label: String? = null)

    private val dayNames = DayOfWeek.values().associateBy { it.getDisplayName(TextStyle.FULL, Locale.UK).lowercase() }
    private const val DAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private fun cal(d: DayOfWeek) = d.value % 7 + 1      // DayOfWeek MONDAY=1 → Calendar.MONDAY=2, SUNDAY=7 → 1

    /** A spoken time: "7", "7:30", "6 15", "6 pm", "noon", "midnight", "half past 6", "quarter to 7". */
    fun time(t: String, evening: Boolean = false): Pair<Int, Int>? {
        val s = tidy(t.trim())
        when (s) { "noon", "midday" -> return 12 to 0; "midnight" -> return 0 to 0 }
        Regex("^(half past|quarter past|quarter to) (\\S+)( am| pm| a\\.m\\.| p\\.m\\.)?$").find(s)?.let { m ->
            var h = Assistant.num(m.groupValues[2]) ?: return null
            val ap = m.groupValues[3].trim()
            var min = when (m.groupValues[1]) { "half past" -> 30; "quarter past" -> 15; else -> { h -= 1; 45 } }
            if (h < 0) h += 12
            if (ap.startsWith("p") && h < 12 || ap.isEmpty() && evening && h in 1..11) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            return if (h in 0..23) h to min else null
        }
        val m = Regex("^(\\S+?)(?:[:. ](\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.|o'clock|o clock)?$").find(s) ?: return null
        var h = Assistant.num(m.groupValues[1]) ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (ap.startsWith("p") && h < 12 || !ap.startsWith("a") && !ap.startsWith("p") && evening && h in 1..11) h += 12
        if (ap.startsWith("a") && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    private fun minutesIn(s: String): Int? {
        Regex("^in (half an hour|an hour and a half|a quarter of an hour)$").find(s)?.let {
            return when (it.groupValues[1]) { "half an hour" -> 30; "a quarter of an hour" -> 15; else -> 90 }
        }
        val m = Regex("^in (\\S+|an|a) (minute|min|hour)s?(?: and (\\S+) (minute|min)s?| and a half)?$").find(s) ?: return null
        val n = if (m.groupValues[1] in setOf("a", "an")) 1 else Assistant.num(m.groupValues[1]) ?: return null
        var mins = if (m.groupValues[2] == "hour") n * 60 else n
        if (m.groupValues[3].isNotEmpty()) mins += Assistant.num(m.groupValues[3]) ?: return null
        if (s.endsWith(" and a half")) mins += if (m.groupValues[2] == "hour") 30 else 0
        return mins.takeIf { it in 1..(24 * 60) }
    }

    /** "set an alarm for 7 every weekday called gym", "wake me up tomorrow at 6", "alarm in 40 minutes", … */
    /** Speech-to-text writes "a.m.", "a.m", "A.M.", "a. m." – and the sentence's full stop may take the last dot. */
    fun tidy(s: String) = s.replace(Regex("\\b([ap])\\. ?m\\b\\.?"), "$1m").replace(Regex("(\\d)\\s*([ap]m)\\b"), "$1 $2")

    fun parse(raw: String): Cmd? {
        val s = tidy(raw)
        val lead = Regex("^(?:(?:set|make|create|add|put)(?: me)? (?:an |a |the |my |another )?(?:new )?alarm|alarm|wake me(?: up)?)(?: |$)").find(s) ?: return null
        var r = s.substring(lead.range.last + 1).trim()
        if (!s.contains("alarm") && !s.startsWith("wake me")) return null
        // label: "called bins", "labelled gym", "saying take pills", "to put the bins out", "for the bins"
        var label: String? = null
        Regex("(?:^| )(?:called|named|labelled|labeled|saying|to say) (.+)$").find(r)?.let { label = it.groupValues[1]; r = r.substring(0, it.range.first).trim() }
        if (label == null) Regex("(?:^| )(?:to|for)(?: the)? (?!(?:\\d|$DAY|tomorrow|today|tonight|noon|midday|midnight|half|quarter|every|weekday|weekend|in )\\b)([a-z].+)$").find(r)?.let {
            // "for seven", "for 7 o'clock", "for tomorrow at 6" are times, not labels
            if (parseWhen(it.groupValues[1]) == null) { label = it.groupValues[1]; r = r.substring(0, it.range.first).trim() }
        }
        // a time or day the speaker put after the label ("to take pills at 9") belongs to the alarm
        label?.let { l ->
            Regex("^(.*?) ((?:at|for|on|every|tomorrow|tonight|today|in) .+)$").find(l)?.let { m ->
                if (parseWhen(m.groupValues[2]) != null) { label = m.groupValues[1]; r = (r + " " + m.groupValues[2]).trim() }
            }
        }
        val w = parseWhen(r) ?: return null
        return Cmd.Alarm(w.hour, w.minute, w.days, w.on, w.inMinutes, label?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun parseWhen(text: String): Spec? {
        var r = " " + text.trim() + " "
        val inMin = minutesIn(text.trim().removePrefix("for ").trim())
        if (inMin != null) return Spec(0, 0, inMinutes = inMin)
        // repeats
        var days = emptyList<Int>()
        fun take(re: String, d: List<Int>) { Regex(re).find(r)?.let { days = d; r = r.replaceRange(it.range, " ") } }
        val weekdays = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        take(" (?:every day|daily|each day) ", (1..7).toList())
        take(" (?:on |every )?weekdays? | every weekday | each weekday ", weekdays)
        take(" (?:at |on )?(?:the )?weekends? | every weekend ", listOf(Calendar.SATURDAY, Calendar.SUNDAY))
        Regex(" (?:every|each|on) ((?:$DAY)s?(?:(?:,| and|, and) (?:$DAY)s?)*) | ((?:$DAY)s(?:(?:,| and|, and) (?:$DAY)s?)*) ").find(r)?.let { m ->
            val names = Regex(DAY).findAll(m.value).map { cal(dayNames[it.value]!!) }.toList()
            if (m.value.contains("every") || m.value.contains("each") || Regex("(?:$DAY)s\\b").containsMatchIn(m.value)) {
                days = names.distinct(); r = r.replaceRange(m.range, " ")
            }
        }
        // a one-off day
        var on: Int? = null
        Regex(" (today|tonight|tomorrow(?: morning| night| evening| afternoon)?|this (?:morning|evening|afternoon)|(?:on |next |this )?($DAY)(?: morning| night| evening| afternoon)?) ").find(r)?.let { m ->
            if (days.isEmpty()) {
                val v = m.groupValues[1]
                on = when {
                    v.startsWith("today") || v.startsWith("tonight") || v.startsWith("this ") && m.groupValues[2].isEmpty() -> 0
                    v.startsWith("tomorrow") -> 1
                    else -> 10 + dayNames[m.groupValues[2]]!!.value
                }
                r = r.replaceRange(m.range, " ")
            }
        }
        val evening = Regex("tonight|evening|night|afternoon").containsMatchIn(text)
        var t = r.replace(Regex("\\s+"), " ").trim().replace(Regex(" (?:in the morning|in the evening|at night)$"), "")
        while (true) { val u = t.replace(Regex("^(?:at|for|on|to|of) "), "").replace(Regex(" (?:at|for|on)$"), "").trim(); if (u == t) break; t = u }
        val tm = (if (t.isEmpty()) null else time(t, evening)) ?: return null
        val (h, mi) = if (Regex("in the evening|at night").containsMatchIn(text) && tm.first in 1..11) (tm.first + 12) to tm.second else tm
        return Spec(h, mi, days, on)
    }

    // ---------- when it falls ----------

    /** The moment a one-off alarm rings, or null for a plain/repeating one. */
    fun moment(a: Cmd.Alarm, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        a.inMinutes?.let { return now.plusMinutes(it.toLong()).withSecond(0).withNano(0) }
        val on = a.on ?: return null
        val t = now.toLocalDate().atTime(a.hour, a.minute)
        return when {
            on == 0 -> if (t.isAfter(now)) t else t.plusDays(1)
            on == 1 -> t.plusDays(1)
            else -> {
                val want = DayOfWeek.of(on - 10)
                var d = now.toLocalDate()
                while (d.dayOfWeek != want) d = d.plusDays(1)
                val at = d.atTime(a.hour, a.minute)
                if (at.isAfter(now)) at else at.plusWeeks(1)
            }
        }
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
    private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)
    fun daysText(days: List<Int>): String {
        val set = days.toSet()
        if (set.size == 7) return "every day"
        if (set == setOf(2, 3, 4, 5, 6)) return "every weekday"
        if (set == setOf(1, 7)) return "at weekends"
        return "every " + days.sortedBy { (it + 5) % 7 }.joinToString(", ") {
            DayOfWeek.of((it + 5) % 7 + 1).getDisplayName(TextStyle.FULL, Locale.UK)
        }
    }

    // ---------- setting ----------

    fun set(act: MainActivity, a: Cmd.Alarm): String {
        val now = LocalDateTime.now()
        val at = moment(a, now)
        val named = a.label?.let { " – “$it”" }.orEmpty()
        // a one-off more than 24 h away: the Clock app can't take a date, so Dune rings it
        if (at != null && at.isAfter(now.plusHours(24))) {
            val ok = schedule(act, Own(System.currentTimeMillis(), at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), a.label))
            return if (ok) "Alarm set for ${at.format(dayFmt)} at ${hm(at.hour, at.minute)}$named. Dune will ring this one itself."
            else "Dune isn't allowed to set exact alarms – allow it in the screen that just opened, then ask again"
        }
        val h = at?.hour ?: a.hour; val m = at?.minute ?: a.minute
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (a.days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(a.days))
        a.label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val err = clockError(act, i)
        if (err != null) {
            // the Clock app wouldn't take it: a one-off Dune can ring itself; a weekly repeat it can't
            if (a.days.isNotEmpty()) return "The Clock app refused the alarm ($err), and Dune can't hold repeating alarms itself"
            val t = at ?: now.toLocalDate().atTime(h, m).let { if (it.isAfter(now)) it else it.plusDays(1) }
            val ok = schedule(act, Own(System.currentTimeMillis(), t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), a.label))
            return if (ok) "Alarm set for ${hm(t.hour, t.minute)} ${relDay(t)}$named. Dune will ring it itself – the Clock app refused ($err)."
            else "Dune isn't allowed to set exact alarms – allow it in the screen that just opened, then ask again"
        }
        val whenText = when {
            a.days.isNotEmpty() -> " " + daysText(a.days)
            a.inMinutes != null -> ", in " + spanText(a.inMinutes)
            at != null && at.toLocalDate() != now.toLocalDate() -> " tomorrow"
            at != null -> " today"
            else -> ""
        }
        return "Alarm set for ${hm(h, m)}$whenText$named"
    }

    /** An alarm at a given moment (from Dune AI): within a day it goes to the Clock app, later Dune rings it. */
    fun setAt(act: MainActivity, t: LocalDateTime, label: String?): String {
        val now = LocalDateTime.now()
        if (!t.isAfter(now)) return "That alarm time has already gone"
        if (!t.isAfter(now.plusHours(24))) return set(act, Cmd.Alarm(t.hour, t.minute, emptyList(), 0, null, label))
        val ok = schedule(act, Own(System.currentTimeMillis(), t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), label))
        return if (ok) "Alarm set for ${t.format(dayFmt)} at ${hm(t.hour, t.minute)}" + (label?.let { " – “$it”" } ?: "")
            else "Dune isn't allowed to set exact alarms – allow it in the screen that just opened, then ask again"
    }

    /** Hand [i] to the Clock app; null if it took it, otherwise why not (logged, for adb logcat -s Dune). */
    fun clockError(ctx: Context, i: Intent): String? = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null
    } catch (e: android.content.ActivityNotFoundException) {
        android.util.Log.w("Dune", "no app for ${i.action}", e); "no Clock app answers alarm requests"
    } catch (e: SecurityException) {
        android.util.Log.w("Dune", "Clock app refused ${i.action}", e); "permission refused"
    } catch (e: Exception) {
        android.util.Log.w("Dune", "Clock app failed ${i.action}", e); e.javaClass.simpleName
    }

    private fun spanText(min: Int) = when {
        min < 60 -> "$min minute${if (min == 1) "" else "s"}"
        min % 60 == 0 -> "${min / 60} hour${if (min == 60) "" else "s"}"
        else -> "${min / 60} h ${min % 60} min"
    }

    // ---------- cancelling and listing ----------

    /** "cancel all alarms", "cancel my 7 am alarm", "cancel the bins alarm", "turn off my alarm", "what alarms do I have" */
    fun parseOther(raw: String): Cmd? {
        val s = tidy(raw)
        if (!s.contains("alarm")) return null
        if (Regex("^(?:what|which|list|show|read|tell me|any|do i have|have i got)\\b.*alarms?|^when(?:'s| is) my (?:next )?alarm|^(?:my|next) alarms?$|^what time is my alarm").containsMatchIn(s))
            return Cmd.AlarmList
        val m = Regex("^(?:cancel|delete|remove|turn off|switch off|clear|stop|get rid of|scrap)(?: all)?(?: of)?(?: my| the)? ?(.*?) ?alarms?(?: (?:at|for|called|named|labelled) (.+))?$").find(s) ?: return null
        val all = Regex("^\\S+(?: \\S+)? all\\b|^clear (?:my |the )?alarms$").containsMatchIn(s)
        val what = listOf(m.groupValues[1], m.groupValues[2]).joinToString(" ").trim()
        if (all) return Cmd.AlarmCancel(all = true)
        if (what.isEmpty() || what == "next") return Cmd.AlarmCancel()
        time(what.removePrefix("the ").trim())?.let { (h, mi) -> return Cmd.AlarmCancel(hour = h, minute = mi) }
        return Cmd.AlarmCancel(label = what.removePrefix("the ").trim())
    }

    fun cancel(act: MainActivity, c: Cmd.AlarmCancel): String {
        // Dune's own first: these it can remove outright
        val mine = load(act)
        val hit = mine.filter { o ->
            val t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(o.at), ZoneId.systemDefault())
            c.all || (c.hour != null && t.hour == c.hour && t.minute == c.minute) ||
                (c.label != null && o.label?.contains(c.label, true) == true) || (!c.all && c.hour == null && c.label == null && o == mine.minByOrNull { it.at })
        }
        hit.forEach { unschedule(act, it) }
        // then the Clock app's: it decides itself (it may ask to confirm; a repeating alarm skips its next ring)
        val i = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        when {
            c.all -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
            c.hour != null -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, c.hour).putExtra(AlarmClock.EXTRA_MINUTES, c.minute ?: 0)
                .putExtra(AlarmClock.EXTRA_IS_PM, c.hour >= 12)
            c.label != null -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                .putExtra(AlarmClock.EXTRA_MESSAGE, c.label)
            else -> if (hit.isNotEmpty()) null else i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        }
        val clockAsked = !(c.all.not() && c.hour == null && c.label == null && hit.isNotEmpty()) && act.tryStart(i)
        val what = when { c.all -> "all alarms"; c.hour != null -> "the ${hm(c.hour, c.minute ?: 0)} alarm"; c.label != null -> "the “${c.label}” alarm"; else -> "your next alarm" }
        return when {
            hit.isNotEmpty() && clockAsked -> "Cancelled ${hit.size} of Dune's alarms, and asked the Clock app to turn off $what"
            hit.isNotEmpty() -> "Cancelled " + if (hit.size == 1) "the alarm for ${describe(hit[0])}" else "${hit.size} alarms"
            clockAsked -> "Asked the Clock app to turn off $what"
            else -> { Actions.openClock(act); "Your Clock app doesn't let other apps turn alarms off – here's its list" }
        }
    }

    fun list(act: MainActivity, driving: Boolean): String {
        val am = act.getSystemService(AlarmManager::class.java)
        val next = am.nextAlarmClock?.triggerTime?.let { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
        val mine = load(act).sortedBy { it.at }
        val b = StringBuilder()
        b.append(if (next == null) "No alarms are set on the phone." else "Your next alarm is ${relDay(next)} at ${hm(next.hour, next.minute)}.")
        if (mine.isNotEmpty()) b.append(" Dune is also holding " + mine.joinToString("; ") { describe(it) } + ".")
        if (!driving) Actions.openClock(act)
        return b.toString()
    }

    private fun relDay(t: LocalDateTime): String {
        val d = t.toLocalDate(); val today = LocalDate.now()
        return when (d) { today -> "today"; today.plusDays(1) -> "tomorrow"; else -> d.format(dayFmt) }
    }
    private fun describe(o: Own): String {
        val t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(o.at), ZoneId.systemDefault())
        return (if (o.kind == Reminders.REMINDER) "a reminder " else "") + relDay(t) + " at " + hm(t.hour, t.minute) + (o.label?.let { " (“$it”)" } ?: "")
    }

    // ---------- Dune's own alarms ----------

    /** [kind]: "alarm" rings until stopped; Reminders.REMINDER is a notification. */
    data class Own(val id: Long, val at: Long, val label: String?, val kind: String = "alarm", val repeat: String? = null, val dom: Int? = null)
    private const val PREFS = "dune_alarms"; private const val CHANNEL = "dune_alarm"
    const val FIRE = "uk.newbroman.dune.ALARM_FIRE"; const val STOP = "uk.newbroman.dune.ALARM_STOP"; const val SNOOZE = "uk.newbroman.dune.ALARM_SNOOZE"

    fun load(ctx: Context): List<Own> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, 0).getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Own(it.getLong("id"), it.getLong("at"), it.optString("label").ifEmpty { null }, it.optString("kind").ifEmpty { "alarm" },
            it.optString("repeat").ifEmpty { null }, it.optInt("dom", 0).takeIf { d -> d > 0 }) }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, l: List<Own>) = ctx.getSharedPreferences(PREFS, 0).edit()
        .putString("list", JSONArray(l.map { JSONObject().put("id", it.id).put("at", it.at).put("label", it.label ?: "").put("kind", it.kind).put("repeat", it.repeat ?: "").put("dom", it.dom ?: 0) }).toString()).apply()

    private fun firePi(ctx: Context, id: Long) = PendingIntent.getBroadcast(ctx, id.toInt(),
        Intent(ctx, AlarmReceiver::class.java).setAction(FIRE).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context, o: Own): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            ctx.tryStart(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        if (ctx is Activity && Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            (ctx as? MainActivity)?.requestPerm(Manifest.permission.POST_NOTIFICATIONS) { }
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(o.at, show), firePi(ctx, o.id))
        save(ctx, load(ctx).filter { it.id != o.id } + o)
        return true
    }

    fun unschedule(ctx: Context, o: Own) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePi(ctx, o.id))
        save(ctx, load(ctx).filter { it.id != o.id })
    }

    /** After a restart: set them all again; alarms that fell due while the phone was off are dropped, reminders shown. */
    fun reschedule(ctx: Context) {
        val now = System.currentTimeMillis()
        val (keep, gone) = load(ctx).partition { it.at > now }
        save(ctx, keep)
        keep.forEach { schedule(ctx, it) }
        gone.filter { it.kind == Reminders.REMINDER }.forEach { Reminders.show(ctx, it); Reminders.again(ctx, it) }
        Briefing.schedule(ctx)
        LeaveNow.plan(ctx)
        if (gone.isNotEmpty()) android.util.Log.i("Dune", "dropped ${gone.size} alarm(s) missed while off")
    }

    fun ring(ctx: Context, id: Long) {
        val o = load(ctx).firstOrNull { it.id == id }
        save(ctx, load(ctx).filter { it.id != id })
        if (o?.kind == Reminders.REMINDER) { Reminders.show(ctx, o); Reminders.again(ctx, o); return }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true); vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
                setBypassDnd(true); lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }
        fun pi(action: String, code: Int) = PendingIntent.getBroadcast(ctx, code,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra("id", id).putExtra("label", o?.label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val full = PendingIntent.getActivity(ctx, id.toInt(), Intent(ctx, AlarmRingActivity::class.java).putExtra("id", id).putExtra("label", o?.label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val now = LocalDateTime.now()
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm " + hm(now.hour, now.minute)).setContentText(o?.label ?: "Dune alarm")
            .setCategory(android.app.Notification.CATEGORY_ALARM).setFullScreenIntent(full, true).setContentIntent(full)
            .addAction(android.app.Notification.Action.Builder(null, "Stop", pi(STOP, 1)).build())
            .addAction(android.app.Notification.Action.Builder(null, "Snooze 10 min", pi(SNOOZE, 2)).build())
            .setOngoing(true).setTimeoutAfter(10 * 60_000L).build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT       // the sound repeats until stopped
        nm.notify(NOTE_ID, n)
    }
    const val NOTE_ID = 7071

    fun stop(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
    fun snooze(ctx: Context, label: String?) {
        stop(ctx)
        schedule(ctx, Own(System.currentTimeMillis(), System.currentTimeMillis() + 10 * 60_000L, label))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            Alarms.FIRE -> Alarms.ring(ctx, i.getLongExtra("id", 0))
            Alarms.STOP -> Alarms.stop(ctx)
            Alarms.SNOOZE -> Alarms.snooze(ctx, i.getStringExtra("label"))
            Briefing.FIRE -> { Briefing.ready(ctx); Briefing.schedule(ctx) }
            LeaveNow.CHECK -> {
                // a journey to work out: keep the receiver alive for it (Android allows up to about half a minute)
                val r = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    try { kotlinx.coroutines.withTimeoutOrNull(25_000) { LeaveNow.check(ctx) } } finally { r.finish() }
                }
            }
            Reminders.DONE -> ctx.getSystemService(NotificationManager::class.java).cancel(i.getIntExtra("nid", 0))
            Reminders.LATER -> Reminders.later(ctx, i.getIntExtra("nid", 0), i.getStringExtra("label"))
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.TIME_SET",
            "android.intent.action.TIMEZONE_CHANGED" -> Alarms.reschedule(ctx)
        }
    }
}

/** Full screen over the lock screen while a Dune alarm rings. */
class AlarmRingActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val label = intent.getStringExtra("label")
        val now = LocalDateTime.now()
        setContent {
            Column(Modifier.fillMaxSize().background(Bg).padding(32.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%02d:%02d".format(now.hour, now.minute), color = Fg, fontSize = 72.sp)
                Text(label ?: "Alarm", color = Dim, fontSize = 24.sp, modifier = Modifier.padding(top = 8.dp, bottom = 48.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("Snooze 10 min", color = Fg, fontSize = 22.sp, modifier = Modifier.background(Faint.copy(alpha = 0.3f))
                        .clickable { Alarms.snooze(this@AlarmRingActivity, label); finish() }.padding(horizontal = 20.dp, vertical = 16.dp))
                    Text("Stop", color = Bg, fontSize = 22.sp, modifier = Modifier.background(Sand)
                        .clickable { Alarms.stop(this@AlarmRingActivity); finish() }.padding(horizontal = 36.dp, vertical = 16.dp))
                }
            }
        }
    }
}

