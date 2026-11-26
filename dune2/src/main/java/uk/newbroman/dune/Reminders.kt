package uk.newbroman.dune

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reminders by voice: "remind me on Monday 30th November to read the gas meter", "set a reminder for
 * tomorrow at 6 to put the bins out", "remind me in 2 hours to ring Mum". Dune holds them itself (the
 * same exact-alarm engine as its own alarms) and shows a notification with a sound when one falls due –
 * not an alarm that rings until stopped. A date without a time means 9 in the morning. "Remind me to buy
 * milk", with no when at all, stays a task.
 */
object Reminders {
    sealed class R : Cmd()
    /** [inMinutes] for "in 2 hours"; otherwise [date] (null = today/tomorrow by the clock) and [hour]:[minute] (null = 9:00). */
    data class Set(val what: String, val date: LocalDate?, val hour: Int?, val minute: Int?, val inMinutes: Int? = null,
                   val dayMismatch: Boolean = false) : R()
    object ListAll : R()
    data class Cancel(val what: String?) : R()

    private const val WD = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|weds|wed|thurs|thur|thu|fri|sat|sun"
    private const val MO = "january|february|march|april|may|june|july|august|september|october|november|december|" +
        "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
    private val ordinals = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
        "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
        "twentieth", "twenty first", "twenty second", "twenty third", "twenty fourth", "twenty fifth", "twenty sixth",
        "twenty seventh", "twenty eighth", "twenty ninth", "thirtieth", "thirty first")
    private val ORD = ordinals.reversed().joinToString("|") { it.replace(" ", "[ -]") }
    private val D = "(\\d{1,2})(?:st|nd|rd|th)?|($ORD)"

    private fun day(n: String?, word: String?): Int? =
        n?.toIntOrNull() ?: word?.let { w -> ordinals.indexOf(w.replace('-', ' ')).takeIf { it >= 0 }?.plus(1) }
    private fun month(s: String): Int = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .indexOfFirst { s.startsWith(it) } + 1
    private fun weekday(s: String): DayOfWeek = DayOfWeek.values().first { it.name.lowercase().startsWith(s.take(3)) }

    private val lead = Regex("^(?:(?:set|add|create|make|put in|put|give me|schedule)(?: me)? (?:a |an |another |the )?(?:new )?reminder|reminder|remind me|don'?t let me forget|i need reminding)(?: |$)")

    fun parse(raw: String, today: LocalDate = LocalDate.now()): Cmd? {
        val s = Alarms.tidy(raw.trim().trimEnd('.', '!', '?'))
        if (Regex("^(?:what|which|list|show|read|tell me|any|do i have|have i got)\\b.*\\breminders?\\b|^(?:my|next) reminders?$").containsMatchIn(s)) return ListAll
        Regex("^(?:cancel|delete|remove|clear|scrap|get rid of|forget)(?: all)?(?: of)?(?: my| the)? ?(.*?) ?reminders?(?: (?:about|for|to|called) (.+))?$").find(s)?.let { m ->
            val w = listOf(m.groupValues[1], m.groupValues[2]).joinToString(" ").trim().removePrefix("the ").trim()
            return Cancel(w.ifEmpty { null }?.takeIf { it != "next" })
        }
        val l = lead.find(s) ?: return null
        var r = " " + s.substring(l.range.last + 1).trim() + " "
        var date: LocalDate? = null; var hour: Int? = null; var minute: Int? = null; var inMin: Int? = null; var mismatch = false
        fun cut(m: MatchResult) { r = r.replaceRange(m.range, " ") }

        // "in 2 hours", "in 20 minutes", "in 3 days", "in a week"
        Regex(" in (\\S+|an|a) (minute|min|hour|day|week)s?(?: and a half)? ").find(r)?.let { m ->
            val n = if (m.groupValues[1] in setOf("a", "an")) 1 else Assistant.num(m.groupValues[1])
            if (n != null) {
                when (m.groupValues[2]) {
                    "day" -> date = today.plusDays(n.toLong())
                    "week" -> date = today.plusWeeks(n.toLong())
                    "hour" -> inMin = n * 60 + if (m.value.contains("half")) 30 else 0
                    else -> inMin = n
                }
                cut(m)
            }
        }
        // a calendar date: "(on) (mon) 30th (of) november (2026)", "november 30th", "30/11"
        val wdOpt = "(?:(?:on |for |by )?(?:the )?(?:($WD)),? )?"
        Regex(" (?:on |for |by )?$wdOpt(?:the )?(?:$D)(?: of)? ($MO)(?: (\\d{4}))? ").find(r)?.let { m ->
            val g = m.groupValues
            date = dated(today, g[4], day(g[2].ifEmpty { null }, g[3].ifEmpty { null }), g[5].toIntOrNull())
            if (g[1].isNotEmpty() && date != null) mismatch = date!!.dayOfWeek != weekday(g[1])
            cut(m)
        } ?: Regex(" (?:on |for |by )?$wdOpt($MO) (?:the )?(?:$D)(?:,? (\\d{4}))? ").find(r)?.let { m ->
            val g = m.groupValues
            date = dated(today, g[2], day(g[3].ifEmpty { null }, g[4].ifEmpty { null }), g[5].toIntOrNull())
            if (g[1].isNotEmpty() && date != null) mismatch = date!!.dayOfWeek != weekday(g[1])
            cut(m)
        } ?: Regex(" (?:on |for |by )?(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))? ").find(r)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            date = runCatching { LocalDate.of(y ?: today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
                ?.let { if (y == null && it.isBefore(today)) it.plusYears(1) else it }
            if (date != null) cut(m)
        } ?: Regex(" (?:on |for |by )?$wdOpt(?:the )($D) ").find(r)?.let { m ->
            // "on the 30th": this month, or next if it's gone
            val g = m.groupValues
            val d = day(g[3].ifEmpty { null }, g[4].ifEmpty { null })
            if (d != null && d in 1..31) {
                var x = runCatching { today.withDayOfMonth(d) }.getOrNull()
                if (x == null || x.isBefore(today)) x = runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull()
                date = x
                if (g[1].isNotEmpty() && date != null) mismatch = date!!.dayOfWeek != weekday(g[1])
                cut(m)
            }
        }
        // a part of the day said with the day: "tomorrow morning", "monday evening", "tonight"
        var part: String? = null
        if (date == null) Regex(" (?:on |for |by )?(today|tonight|tomorrow|the day after tomorrow|next week|(?:next |this )?(?:$WD))(?: (morning|afternoon|evening|night))? ").find(r)?.let { m ->
            val v = m.groupValues[1]
            date = when {
                v == "today" || v == "tonight" -> today
                v == "tomorrow" -> today.plusDays(1)
                v == "the day after tomorrow" -> today.plusDays(2)
                v == "next week" -> today.plusWeeks(1).with(DayOfWeek.MONDAY)
                else -> { val want = weekday(v.removePrefix("next ").removePrefix("this ")); var d = today.plusDays(1)
                    while (d.dayOfWeek != want) d = d.plusDays(1); d }
            }
            part = m.groupValues[2].ifEmpty { if (v == "tonight") "evening" else null }
            cut(m)
        }
        // a clock time: "at 6", "at 6:30 pm", "6 pm", "noon"
        Regex(" (?:at|by|for|around|about) (noon|midday|midnight|(?:half past|quarter past|quarter to) \\S+|\\d{1,2}(?:[:.]\\d{2})?(?: ?(?:am|pm|o'?clock))?) | (\\d{1,2}(?:[:.]\\d{2})? ?(?:am|pm)|noon|midday) ").find(r)?.let { m ->
            val t = m.groupValues[1].ifEmpty { m.groupValues[2] }
            Alarms.time(t, evening = part in setOf("afternoon", "evening", "night"))?.let { (h, mi) -> hour = h; minute = mi; cut(m) }
        }
        Regex(" in the (morning|afternoon|evening) | (morning|afternoon|evening) ").find(r)?.let { m -> part = part ?: m.groupValues[1].ifEmpty { m.groupValues[2] }; cut(m) }
        if (hour == null && part != null) { hour = when (part) { "afternoon" -> 14; "evening", "night" -> 18; else -> 9 }; minute = 0 }
        if (hour != null && hour!! in 1..6 && part == null && !s.contains(Regex("\\d ?am|morning"))) hour = hour!! + 12   // "at 6" for a reminder is 6 pm

        if (date == null && hour == null && inMin == null) return null       // no when: a task, not a reminder
        val what = r.replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(?:to|about|that|i need to|i have to|i must|i should|we need to|for|of)\\s+"), "")
            .replace(Regex("\\s+(?:to|on|at|for)$"), "").trim()
        if (what.isEmpty()) return null
        return Set(what.replaceFirstChar { it.uppercase() }, date, hour, minute, inMin, mismatch)
    }

    private fun dated(today: LocalDate, mo: String, d: Int?, y: Int?): LocalDate? {
        if (d == null) return null
        val x = runCatching { LocalDate.of(y ?: today.year, month(mo), d) }.getOrNull() ?: return null
        return if (y == null && x.isBefore(today)) x.plusYears(1) else x
    }

    /** When it falls due. A date with no time is 9:00; a time today that has gone means tomorrow. */
    fun moment(c: Set, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        c.inMinutes?.let { return now.plusMinutes(it.toLong()).withSecond(0).withNano(0) }
        val d = c.date ?: now.toLocalDate()
        var t = d.atTime(c.hour ?: 9, c.minute ?: 0)
        if (c.date == null && !t.isAfter(now)) t = t.plusDays(1)
        // "today" with no time, after 9: an hour from now
        if (c.date != null && c.hour == null && !t.isAfter(now) && d == now.toLocalDate()) t = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
        return t
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
    private fun whenText(t: LocalDateTime, now: LocalDateTime): String {
        val d = t.toLocalDate()
        val day = when (d) { now.toLocalDate() -> "today"; now.toLocalDate().plusDays(1) -> "tomorrow"; else -> d.format(dayFmt) +
            if (d.year != now.year) " ${d.year}" else "" }
        return "$day at %02d:%02d".format(t.hour, t.minute)
    }

    fun run(act: MainActivity, c: R): String = when (c) {
        is Set -> {
            val now = LocalDateTime.now()
            val t = moment(c, now)
            if (!t.isAfter(now)) "That time has already gone – say when again?"
            else {
                val ok = Alarms.schedule(act, Alarms.Own(System.currentTimeMillis(), t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), c.what, REMINDER))
                if (!ok) "Dune isn't allowed to set exact alarms, which reminders need – allow it in the screen that just opened, then ask again"
                else "I'll remind you ${whenText(t, now)}: “${c.what}”." +
                    (if (c.dayMismatch) " (Note: that date is a ${t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}.)" else "") +
                    (if (c.hour == null && c.inMinutes == null) " Say a time to change it, e.g. “…at 6 pm”." else "")
            }
        }
        ListAll -> {
            val now = LocalDateTime.now()
            val mine = Alarms.load(act).filter { it.kind == REMINDER }.sortedBy { it.at }
            if (mine.isEmpty()) "No reminders set."
            else "Your reminders: " + mine.joinToString("; ") { o ->
                whenText(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(o.at), ZoneId.systemDefault()), now) + " – " + (o.label ?: "reminder") } + "."
        }
        is Cancel -> {
            val mine = Alarms.load(act).filter { it.kind == REMINDER }.sortedBy { it.at }
            val hit = when {
                c.what == null || c.what == "all" -> if (c.what == "all") mine else listOfNotNull(mine.firstOrNull())
                else -> mine.filter { o -> o.label?.contains(c.what, true) == true || c.what.split(" ").filter { it.length > 3 }.any { w -> o.label?.contains(w, true) == true } }
            }
            hit.forEach { Alarms.unschedule(act, it) }
            when { mine.isEmpty() -> "No reminders to cancel."; hit.isEmpty() -> "No reminder matching “${c.what}”."
                else -> "Cancelled " + hit.joinToString("; ") { "“${it.label}”" } }
        }
    }

    const val REMINDER = "reminder"
    private const val CHANNEL = "dune_reminder"
    const val DONE = "uk.newbroman.dune.REMINDER_DONE"; const val LATER = "uk.newbroman.dune.REMINDER_LATER"

    /** Show a reminder that has fallen due: a notification with a sound, Done and "In an hour". */
    fun show(ctx: Context, o: Alarms.Own) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val nid = (o.id % 100_000).toInt() + 20_000
        fun pi(action: String, code: Int) = PendingIntent.getBroadcast(ctx, nid * 10 + code,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra("nid", nid).putExtra("label", o.label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(ctx, nid, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder").setContentText(o.label ?: "Dune reminder")
            .setStyle(android.app.Notification.BigTextStyle().bigText(o.label ?: "Dune reminder"))
            .setCategory(android.app.Notification.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true)
            .addAction(android.app.Notification.Action.Builder(null, "Done", pi(DONE, 1)).build())
            .addAction(android.app.Notification.Action.Builder(null, "In an hour", pi(LATER, 2)).build())
            .build()
        nm.notify(nid, n)
    }

    fun later(ctx: Context, nid: Int, label: String?) {
        ctx.getSystemService(NotificationManager::class.java).cancel(nid)
        Alarms.schedule(ctx, Alarms.Own(System.currentTimeMillis(), System.currentTimeMillis() + 60 * 60_000L, label, REMINDER))
    }
}
