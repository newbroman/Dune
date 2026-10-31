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
    data class Alarm(val hour: Int, val minute: Int) : Cmd()
    data class Timer(val seconds: Int) : Cmd()
    data class Navigate(val dest: String?) : Cmd()      // null = home
    data class Search(val q: String) : Cmd()
    data class Translate(val text: String) : Cmd()
    data class Spotify(val q: String?) : Cmd()
    data class Media(val action: String) : Cmd()        // play, pause, next, previous
    data class Reply(val text: String) : Cmd()
    data class Dial(val number: String) : Cmd()
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

    private fun num(s: String): Int? = s.toIntOrNull() ?: numberWords[s]

    fun interpret(raw: String): Cmd {
        val t = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        val s = t.lowercase().removePrefix("please ").removePrefix("hey dune ").removePrefix("dune ")
        fun after(re: String): String? = Regex(re).find(s)?.let { m -> t.substring(t.length - s.length).substring(m.range.last + 1).trim().ifEmpty { null } }

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
            return Cmd.Reply(t.substring(t.length - s.length).substring(it.groups[3]!!.range.first).trim())
        }

        // Time / phrase
        if (Regex("^(what'?s|what is) the time( in polish)?$|^time in polish$|^(tell me )?the time$|^która (jest )?godzina$").matches(s)) return Cmd.TimeInPolish
        if (Regex("^(polish )?phrase( of the day)?$|^(say|read) (the )?phrase( of the day)?$").matches(s)) return Cmd.PhraseOfDay

        // Navigation
        if (Regex("^(navigate|directions|take me|drive|go|get me) (home|back home)$|^home$|^(go|going) home$").matches(s)) return Cmd.Navigate(null)
        Regex("^(navigate|directions|take me|drive|get me|route|go)( me)? (to|towards) ").find(s)?.let { m ->
            return Cmd.Navigate(t.substring(t.length - s.length).substring(m.range.last + 1).trim())
        }
        Regex("^navigate ").find(s)?.let { return Cmd.Navigate(after("^navigate ")!!) }

        // Alarm and timer
        Regex("^(set |wake me )(an |the |my )?(alarm )?(for |at |up at )?(\\d{1,2})(?:[:. ](\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.)?$").find(s)?.let { m ->
            var h = m.groupValues[5].toInt(); val min = m.groupValues[6].toIntOrNull() ?: 0
            val ap = m.groupValues[7]
            if (ap.startsWith("p") && h < 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            if (h in 0..23 && min in 0..59 && (s.contains("alarm") || s.startsWith("wake"))) return Cmd.Alarm(h, min)
        }
        Regex("^(set )?(a |the )?timer (for )?(\\S+) (second|minute|hour|min)s?$|^(\\S+) (second|minute|hour|min)s? timer$").find(s)?.let { m ->
            val n = num(m.groupValues[4].ifEmpty { m.groupValues[6] }) ?: return@let
            val unit = m.groupValues[5].ifEmpty { m.groupValues[7] }
            val sec = when (unit) { "second" -> n; "hour" -> n * 3600; else -> n * 60 }
            return Cmd.Timer(sec)
        }
        if (Regex("^(set )?(a |the )?timer (for )?half an hour$").matches(s)) return Cmd.Timer(1800)

        // Tasks
        Regex("^(add (a )?task|new task|remind me to|add to (my )?tasks|task)[: ]+").find(s)?.let { m ->
            return Cmd.AddTask(t.substring(t.length - s.length).substring(m.range.last + 1).trim().replaceFirstChar { it.uppercase() })
        }
        Regex("^add (.+) to (my |the )?(tasks|task list|to-?do( list)?)$").find(s)?.let { m ->
            return Cmd.AddTask(t.substring(t.length - s.length).substring(m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1).replaceFirstChar { it.uppercase() })
        }

        // Translation
        Regex("^how (do|would) (you|i) say (.+?) in polish$").find(s)?.let { return Cmd.Translate(orig(t, s, it.groups[3]!!.range)) }
        Regex("^(translate|what is|what's) (.+?) (in|into|to) (polish|english)$").find(s)?.let { return Cmd.Translate(orig(t, s, it.groups[2]!!.range)) }
        Regex("^translate (.+)$").find(s)?.let { return Cmd.Translate(orig(t, s, it.groups[1]!!.range)) }

        // Music
        Regex("^play (.+?) on spotify$").find(s)?.let { return Cmd.Spotify(orig(t, s, it.groups[1]!!.range)) }
        if (s == "spotify" || s == "play spotify" || s == "open spotify") return Cmd.Spotify(null)

        // Calls
        Regex("^(call|ring|phone|dial) ([+\\d][\\d ]{5,})$").find(s)?.let { return Cmd.Dial(it.groupValues[2].replace(" ", "")) }

        // Web
        Regex("^(search|google|look up|search for|search the web for)( for)? (.+)$").find(s)?.let { return Cmd.Search(orig(t, s, it.groups[3]!!.range)) }

        // Open an app
        Regex("^(open|launch|start|show|play) (.+)$").find(s)?.let { return Cmd.Open(it.groupValues[2]) }

        return Cmd.Unknown(t)
    }

    /** Substring of the original text (keeps capitals) for a range found in the lower-cased text. */
    private fun orig(t: String, s: String, r: IntRange): String {
        val off = t.length - s.length
        return t.substring(off + r.first, off + r.last + 1).trim()
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
            is Cmd.Alarm -> {
                val ok = act.tryStart(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, cmd.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                if (ok) "Alarm set for %02d:%02d".format(cmd.hour, cmd.minute) else "No alarm app found"
            }
            is Cmd.Timer -> {
                val ok = act.tryStart(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                if (!ok) return "No timer app found"
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
            is Cmd.Translate -> { Actions.translate(act, cmd.text); "Translating “${cmd.text}”" }
            is Cmd.Spotify -> {
                if (cmd.q == null) { act.launchPackage(SPOTIFY) || act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:"))); "Opening Spotify" }
                else { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(cmd.q))), "Spotify isn't installed"); "Searching Spotify for ${cmd.q}" }
            }
            is Cmd.Media -> { if (vm.media.control(cmd.action)) null else "Nothing is playing" }
            is Cmd.ReadMessages -> { Drive.readMessages(act); null }
            is Cmd.Reply -> {
                val n = NotifService.items.value.firstOrNull { it.isMessage && it.reply != null }
                    ?: return "There's no message to reply to"
                vm.pendingReply = PendingReply(n, cmd.text)
                if (vm.screen != Screen.DRIVE) vm.screen = Screen.DRIVE   // the confirm-and-send panel lives there
                null
            }
            is Cmd.Dial -> { act.tryStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + cmd.number))); "Calling ${cmd.number}" }
            is Cmd.DriveMode -> { Drive.set(act, cmd.on); if (cmd.on) vm.screen = Screen.DRIVE else vm.goHome(); null }
            Cmd.TimeInPolish -> { Speaker.speak(act, PolishTime.spoken(LocalDateTime.now()), PolishTime.PL); PolishTime.spoken(LocalDateTime.now()) }
            Cmd.PhraseOfDay -> {
                val p = vm.phrase ?: return "No phrase yet"
                Speaker.speak(act, p.pl, PolishTime.PL); Speaker.speak(act, p.en, flush = false)
                "${p.pl} – ${p.en}"
            }
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

data class PendingReply(val to: NotifItem, val text: String)

const val SPOTIFY = "com.spotify.music"
const val BBC_SOUNDS = "com.bbc.sounds"
const val MAPS = "com.google.android.apps.maps"
const val TRANSLATE = "com.google.android.apps.translate"
const val CLAUDE = "com.anthropic.claude"
const val GEMINI = "com.google.android.apps.bard"
const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
