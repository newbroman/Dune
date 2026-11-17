package uk.newbroman.dune

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import java.time.LocalDate
import java.time.LocalDateTime

/** What a typed or spoken request means. Kept separate from doing it so it can be unit-tested. */
sealed class Cmd {
    data class Open(val app: String) : Cmd()
    data class AddTask(val text: String) : Cmd()
    /** [days] repeat weekly (Calendar.MONDAY…); [on] a one-off day (0 today, 1 tomorrow, 10 + DayOfWeek); [inMinutes] from now. */
    data class Alarm(val hour: Int, val minute: Int, val days: List<Int> = emptyList(), val on: Int? = null,
                     val inMinutes: Int? = null, val label: String? = null) : Cmd()
    data class AlarmCancel(val hour: Int? = null, val minute: Int? = null, val label: String? = null, val all: Boolean = false) : Cmd()
    object AlarmList : Cmd()
    data class Timer(val seconds: Int) : Cmd()
    data class Navigate(val dest: String?) : Cmd()      // null = home
    data class Search(val q: String) : Cmd()
    /** [to] "pl" or "en", or null to tell from the text; an empty [text] means listen for it. */
    data class Translate(val text: String, val to: String? = null) : Cmd()
    data class Spotify(val q: String?) : Cmd()
    data class Media(val action: String) : Cmd()        // play, pause, next, previous
    data class Reply(val text: String) : Cmd()
    /** "text Anna I'm late": [rest] is the contact's name then the message (split against your contacts later). */
    data class Send(val rest: String, val via: String?) : Cmd()
    data class Dial(val number: String) : Cmd()
    /** "call Anna", "WhatsApp call Mum", "video call Jo" */
    data class CallPerson(val name: String, val whatsapp: Boolean, val video: Boolean) : Cmd()
    data class DriveMode(val on: Boolean) : Cmd()
    object TimeInPolish : Cmd()
    object PhraseOfDay : Cmd()
    object ReadMessages : Cmd()
    data class Unknown(val text: String) : Cmd()
}

object Assistant {
    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "forty-five" to 45, "sixty" to 60, "ninety" to 90,
    )

    internal fun num(s: String): Int? = s.toIntOrNull() ?: numberWords[s]

    fun interpret(raw: String): Cmd {
        val t = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        // How people ask: "can you…", "please…", "…for me please" – the command is what's left
        var s = t.lowercase()
        while (true) {
            val before = s
            s = s.replace(Regex("^(please|ok|okay|hey|hi|dune|ok computer|okay computer|computer|can you|could you|would you|will you|would you mind|i want you to|i'd like you to|i would like you to)[, ]+"), "")
                .replace(Regex("[, ]+(please|for me|thanks|thank you|dune)$"), "")
                .replace(Regex("^(i need|i want|i'd like|i would like|give me|get me) (an alarm|a timer|alarms?)\\b"), "set $2")
            if (s == before) break
        }
        fun after(re: String): String? = Regex(re).find(s)?.let { m -> cased(t, s).substring(m.range.last + 1).trim().ifEmpty { null } }

        // Drive mode
        if (Regex("^(exit|stop|end|leave|close) (drive|driving)( mode)?$").matches(s)) return Cmd.DriveMode(false)
        if (Regex("^(start )?(drive|driving)( mode)?$|^i'?m driving$|^let'?s drive$").matches(s)) return Cmd.DriveMode(true)

        // Media
        when (s) {
            "pause", "stop", "stop music", "pause music", "pause the music", "stop the music" -> return Cmd.Media("pause")
            "play", "resume", "play music", "resume music", "carry on" -> return Cmd.Media("play")
            "next", "skip", "next track", "next song", "skip this", "skip track" -> return Cmd.Media("next")
            "previous", "back", "previous track", "previous song", "go back" -> return Cmd.Media("previous")
        }

        // Messages
        if (Regex("^(read|what are) (out )?(my |the )?(new |latest )?messages?$").matches(s)) return Cmd.ReadMessages
        Regex("^(reply|answer|respond)( with| saying| say)? (.+)$").find(s)?.let {
            return Cmd.Reply(cased(t, s).substring(it.groups[3]!!.range.first).trim())
        }
        // New messages: "send a message to Anna saying …", "text Tom …", "WhatsApp Mum …", "tell Jo …"
        Regex("^(?:send |write )?(?:a |an )?(whatsapp|text|sms|message)(?: message)?(?: to)? (?!call\\b)(.+)$|^(tell) (?!me\\b)(.+)$").find(s)?.let { m ->
            val word = m.groups[1]?.value ?: m.groups[3]!!.value
            val g = m.groups[2] ?: m.groups[4]!!
            val via = when (word) { "whatsapp" -> "whatsapp"; "text", "sms" -> "sms"; else -> null }
            return Cmd.Send(cased(t, s).substring(g.range.first).trim(), via)
        }

        // Time / phrase
        if (Regex("^(what'?s|what is) the time( in polish)?$|^time in polish$|^(tell me )?the time$|^która (jest )?godzina$").matches(s)) return Cmd.TimeInPolish
        if (Regex("^(polish )?phrase( of the day)?$|^(say|read) (the )?phrase( of the day)?$").matches(s)) return Cmd.PhraseOfDay

        // Navigation
        if (Regex("^(navigate|directions|take me|drive|go|get me) (home|back home)$|^home$|^(go|going) home$").matches(s)) return Cmd.Navigate(null)
        Regex("^(navigate|directions|take me|drive|get me|route|go)( me)? (to|towards) ").find(s)?.let { m ->
            return Cmd.Navigate(cased(t, s).substring(m.range.last + 1).trim())
        }
        Regex("^navigate ").find(s)?.let { return Cmd.Navigate(after("^navigate ")!!) }

        // Alarm and timer
        (Alarms.parseOther(s) ?: Alarms.parse(s))?.let { return it }
        Regex("^(set )?(a |the )?timer (for )?(\\S+) (second|minute|hour|min)s?$|^(\\S+) (second|minute|hour|min)s? timer$").find(s)?.let { m ->
            val n = num(m.groupValues[4].ifEmpty { m.groupValues[6] }) ?: return@let
            val unit = m.groupValues[5].ifEmpty { m.groupValues[7] }
            val sec = when (unit) { "second" -> n; "hour" -> n * 3600; else -> n * 60 }
            return Cmd.Timer(sec)
        }
        if (Regex("^(set )?(a |the )?timer (for )?half an hour$").matches(s)) return Cmd.Timer(1800)

        // The phone itself: torch, volume, battery, weather, calendar, camera, sums, "open calculator"…
        Phone.parse(s)?.let { return it }

        // Tasks
        Regex("^(add (a )?task|new task|remind me to|add to (my )?tasks|task)[: ]+").find(s)?.let { m ->
            return Cmd.AddTask(cased(t, s).substring(m.range.last + 1).trim().replaceFirstChar { it.uppercase() })
        }
        Regex("^add (.+) to (my |the )?(tasks|task list|to-?do( list)?)$").find(s)?.let { m ->
            return Cmd.AddTask(cased(t, s).substring(m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1).replaceFirstChar { it.uppercase() })
        }

        // Translation, English ⇄ Polish, done on the phone and said aloud
        if (Regex("^(?:translate )?(?:from )?polish (?:to|into) english$|^translate (?:from )?polish$|^(?:listen|translate) in polish$|^what does this mean$|^i'?ll say it in polish$").matches(s))
            return Cmd.Translate("", "en")
        if (Regex("^translate (?:to|into) polish$|^(?:translate )?(?:from )?english (?:to|into) polish$").matches(s)) return Cmd.Translate("", "pl")
        Regex("^how (?:do|would|can) (?:you|i) say (.+?) in (polish|english)$").find(s)?.let { return Cmd.Translate(orig(t, s, it.groups[1]!!.range), Translation.code(it.groupValues[2])) }
        Regex("^(?:translate|what is|what's|whats|say) (.+?) (?:in|into|to) (polish|english)$").find(s)?.let { return Cmd.Translate(orig(t, s, it.groups[1]!!.range), Translation.code(it.groupValues[2])) }
        Regex("^(?:what is |what's )?(?:the )?(polish|english) (?:word )?for (.+)$").find(s)?.let { return Cmd.Translate(orig(t, s, it.groups[2]!!.range), Translation.code(it.groupValues[1])) }
        Regex("^what does (.+?) mean(?: in english)?$").find(s)?.let { return Cmd.Translate(orig(t, s, it.groups[1]!!.range), "en") }
        Regex("^translate (.+)$").find(s)?.let { return Cmd.Translate(orig(t, s, it.groups[1]!!.range)) }
        Regex("^(.+?) in (polish|english)$").find(s)?.let { if (it.groupValues[1].split(' ').size <= 12) return Cmd.Translate(orig(t, s, it.groups[1]!!.range), Translation.code(it.groupValues[2])) }

        // Music
        Regex("^play (.+?) on spotify$").find(s)?.let { return Cmd.Spotify(orig(t, s, it.groups[1]!!.range)) }
        if (s == "spotify" || s == "play spotify" || s == "open spotify") return Cmd.Spotify(null)

        // Calls
        Regex("^(call|ring|phone|dial) ([+\\d][\\d ]{5,})$").find(s)?.let { return Cmd.Dial(it.groupValues[2].replace(" ", "")) }
        Regex("^(?:make a )?(whatsapp |video )?(?:call|ring|phone)(?: up)? (.+?)(?: on (whatsapp|video))?$").find(s)?.let { m ->
            val kind = m.groupValues[1].trim() + " " + m.groupValues[3]
            return Cmd.CallPerson(m.groupValues[2], whatsapp = "whatsapp" in kind || "video" in kind, video = "video" in kind)
        }

        // Web
        Regex("^(search|google|look up|search for|search the web for)( for)? (.+)$").find(s)?.let { return Cmd.Search(orig(t, s, it.groups[3]!!.range)) }

        // Open an app
        Regex("^(open|launch|start|show|play) (.+)$").find(s)?.let { return Cmd.Open(it.groupValues[2]) }

        return Cmd.Unknown(t)
    }

    /** Substring of the original text (keeps capitals) for a range found in the lower-cased text. */
    /** [s] as the person wrote it (their capitals), found in [t] – it may have lost words at either end. */
    internal fun cased(t: String, s: String): String {
        val off = t.lowercase().indexOf(s)
        return if (off >= 0) t.substring(off, off + s.length) else s
    }

    private fun orig(t: String, s: String, r: IntRange): String {
        return cased(t, s).substring(r.first, r.last + 1).trim()
    }

    /** Best app for a spoken name: exact, then starts-with, then contains. */
    fun findApp(apps: List<AppEntry>, name: String): AppEntry? {
        val n = name.lowercase().removePrefix("the ").removeSuffix(" app").trim()
        val aliases = mapOf("spheres" to "music of the spheres", "mots" to "music of the spheres", "sounds" to "bbc sounds",
            "radio" to "bbc sounds", "maps" to "maps", "camera" to "camera", "polish" to "polish phrase")
        val q = aliases[n] ?: n
        return apps.firstOrNull { it.label.equals(q, true) }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { q.contains(it.label.lowercase()) && it.label.length > 3 }
    }

    /** Carry out [cmd]. Returns a short line to show (and in Drive mode, to speak). */
    /** Commands that read notifications (messages, replies, what's playing) need Dune's notification access. */
    private fun needsNotifications(act: MainActivity): String? {
        if (NotifService.instance != null) return null
        Actions.notificationAccess(act)
        return "Dune can't see your notifications yet – turn on “Dune notifications” in the screen that's opening, then ask again"
    }

    /** Commands that look people up need the contacts permission: ask for it, and carry on once it's given. */
    private fun needsContacts(act: MainActivity, cmd: Cmd): String? {
        if (Find.canReadContacts(act)) return null
        act.requestPerm(android.Manifest.permission.READ_CONTACTS) { ok -> if (ok) act.runCmd(cmd) else act.toast("Dune can't look people up without contacts permission") }
        return "Allow Dune to see your contacts and I'll carry on"
    }

    fun run(act: MainActivity, vm: LauncherVM, cmd: Cmd): String? {
        val s = vm.store
        return when (cmd) {
            is Cmd.Open -> {
                val a = findApp(vm.apps, cmd.app)
                when {
                    a != null -> { vm.launch(a); "Opening ${a.label}" }
                    cmd.app.contains("spheres") -> { Actions.openSpheres(act); "Opening Music of the Spheres" }
                    vm.webApps.firstOrNull { it.label.lowercase().contains(cmd.app.lowercase()) } != null -> {
                        val w = vm.webApps.first { it.label.lowercase().contains(cmd.app.lowercase()) }
                        vm.openWeb(w); "Opening ${w.label}"
                    }
                    cmd.app.startsWith("polish") || cmd.app.contains("phrase") -> {
                        act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(Phrases.APP_URL))); "Opening the Polish app"
                    }
                    else -> { vm.assistantFallback = cmd.app; "I couldn't find an app called “${cmd.app}”" }
                }
            }
            is Cmd.AddTask -> { s.addTask(cmd.text); "Added task: ${cmd.text}" }
            is Cmd.Alarm -> Alarms.set(act, cmd)
            is Cmd.AlarmCancel -> Alarms.cancel(act, cmd)
            is Cmd.AlarmList -> Alarms.list(act, vm.screen == Screen.DRIVE)
            is Cmd.Timer -> {
                Alarms.clockError(act, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))?.let { return "The Clock app wouldn't start the timer ($it)" }
                val m = cmd.seconds / 60
                "Timer set for " + if (cmd.seconds < 60) "${cmd.seconds} seconds" else if (cmd.seconds % 3600 == 0) "${cmd.seconds / 3600} hour(s)" else "$m minute${if (m == 1) "" else "s"}"
            }
            is Cmd.Navigate -> {
                val dest = cmd.dest ?: s.homeAddress.value.trim().ifEmpty {
                    return "Set your home address in Dune settings › Drive mode"
                }
                Actions.navigate(act, dest); "Navigating to ${cmd.dest ?: "home"}"
            }
            is Cmd.Search -> { Actions.webSearch(act, cmd.q); "Searching for ${cmd.q}" }
            is Cmd.Translate -> Translation.run(act, vm, cmd)
            is Cmd.Spotify -> {
                if (cmd.q == null) { act.launchPackage(SPOTIFY) || act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:"))); "Opening Spotify" }
                else { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(cmd.q))), "Spotify isn't installed"); "Searching Spotify for ${cmd.q}" }
            }
            is Cmd.Media -> { needsNotifications(act)?.let { return it }; if (vm.media.control(cmd.action)) null else "Nothing is playing" }
            is Cmd.ReadMessages -> { needsNotifications(act)?.let { return it }; Drive.readMessages(act); null }
            is Cmd.Reply -> {
                needsNotifications(act)?.let { return it }
                val n = NotifService.items.value.firstOrNull { it.isMessage && it.reply != null }
                    ?: return "There's no message to reply to"
                vm.pendingReply = PendingReply(n, cmd.text)
                if (vm.screen != Screen.DRIVE) vm.screen = Screen.DRIVE   // the confirm-and-send panel lives there
                null
            }
            is Cmd.Send -> {
                needsContacts(act, cmd)?.let { return it }
                val (o, why) = Messaging.prepare(act, cmd.rest, cmd.via)
                if (o == null) return why
                if (vm.screen != Screen.DRIVE) vm.screen = Screen.DRIVE   // the confirm-and-send panel lives there
                if (o.text.isBlank()) {
                    Speaker.speak(act, "What's the message for ${o.to.name}?")
                    act.window.decorView.postDelayed({
                        act.listen("Message for ${o.to.name}") { t -> if (!t.isNullOrBlank()) vm.pendingReply = PendingReply(null, t, o.copy(text = t)) }
                    }, 1800)
                } else vm.pendingReply = PendingReply(null, o.text, o)
                null
            }
            is Cmd.CallPerson -> {
                needsContacts(act, cmd)?.let { return it }
                val c = Messaging.match(cmd.name, Messaging.contacts(act))?.contact ?: return "I couldn't find ${cmd.name} in your contacts"
                val p = Person(c.name, c.number, c.id)
                val what = if (cmd.video) "Video calling" else if (cmd.whatsapp) "WhatsApp calling" else "Calling"
                act.window.decorView.postDelayed({ if (cmd.whatsapp) Find.whatsappCall(act, p, cmd.video) else Find.callNow(act, p) }, 1500)
                "$what ${c.name}"
            }
            // rings straight away with the call permission (asked once); without it, the dialler opens with the number in
            is Cmd.Dial -> { Find.callNow(act, Person(cmd.number, cmd.number)); "Calling ${cmd.number}" }
            is Cmd.DriveMode -> { Drive.set(act, cmd.on); if (cmd.on) vm.screen = Screen.DRIVE else vm.goHome(); null }
            Cmd.TimeInPolish -> { Speaker.speak(act, PolishTime.spoken(LocalDateTime.now()), PolishTime.PL); PolishTime.spoken(LocalDateTime.now()) }
            Cmd.PhraseOfDay -> {
                val p = vm.phrase ?: return "No phrase yet"
                Speaker.speak(act, p.pl, PolishTime.PL); Speaker.speak(act, p.en, flush = false)
                "${p.pl} – ${p.en}"
            }
            is PhoneCmd -> Phone.run(act, vm, cmd)
            is Cmd.Unknown -> { vm.assistantFallback = cmd.text; null }
        }
    }

    // ---------- hand-offs for anything Dune can't do itself

    fun askClaude(act: MainActivity, q: String) = shareTo(act, CLAUDE, q, "Claude")
    fun askGemini(act: MainActivity, q: String) = shareTo(act, GEMINI, q, "Gemini")
    fun askGoogle(act: MainActivity, q: String) {
        val ok = act.tryStart(Intent(Intent.ACTION_WEB_SEARCH).setPackage(GOOGLE_APP).putExtra("query", q))
        if (!ok) Actions.webSearch(act, q)
    }

    private fun shareTo(act: MainActivity, pkg: String, q: String, name: String) {
        if (!act.isInstalled(pkg)) { act.toast("$name isn't installed"); return }
        val ok = act.tryStart(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, q).setPackage(pkg))
        if (!ok) {
            act.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("question", q))
            act.launchPackage(pkg); act.toast("Question copied – paste it into $name")
        }
    }
}

/** A message waiting to go: a reply to [to]'s notification, or a new message [out]. */
data class PendingReply(val to: NotifItem?, val text: String, val out: Outgoing? = null) {
    val who get() = out?.let { "${it.to.name} ${it.how}" } ?: to?.title.orEmpty()
}

const val SPOTIFY = "com.spotify.music"
const val BBC_SOUNDS = "com.bbc.sounds"
const val MAPS = "com.google.android.apps.maps"
const val TRANSLATE = "com.google.android.apps.translate"
const val CLAUDE = "com.anthropic.claude"
const val GEMINI = "com.google.android.apps.bard"
const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
