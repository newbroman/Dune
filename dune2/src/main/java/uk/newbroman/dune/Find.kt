package uk.newbroman.dune

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** A person from the phone's contacts, for the command bar. */
data class Person(val name: String, val number: String)

/** The command bar's searches beyond apps: contacts, tasks, messages and Dune's own commands. */
object Find {
    fun canReadContacts(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun people(ctx: Context, q: String, limit: Int = 4): List<Person> {
        if (q.length < 2 || !canReadContacts(ctx)) return emptyList()
        val out = LinkedHashMap<String, Person>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 60) {
                    val name = c.getString(0) ?: continue; val num = c.getString(1) ?: continue
                    // first-name or surname matches first
                    out.putIfAbsent(name, Person(name, num))
                }
            }
        }
        return out.values.sortedBy { p -> if (p.name.split(' ').any { it.lowercase().startsWith(q.lowercase()) }) 0 else 1 }.take(limit)
    }

    /** WhatsApp wants an international number without "+": 07700 900123 → 447700900123 (UK). */
    fun whatsappNumber(n: String): String {
        val d = n.replace("(0)", "").filter { it.isDigit() || it == '+' }
        return when {
            d.startsWith("+") -> d.drop(1)
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") -> "44" + d.drop(1)
            else -> d
        }
    }

    fun call(ctx: Context, p: Person) = ctx.tryStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number))))
    fun text(ctx: Context, p: Person) = ctx.tryStart(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))))
    fun whatsapp(ctx: Context, p: Person) = ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + whatsappNumber(p.number))))

    /** Words that match [q] anywhere, for tasks and messages. */
    fun matches(text: String, q: String): Boolean {
        val words = q.lowercase().split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return false
        val t = text.lowercase()
        return words.all { t.contains(it) }
    }

    /** A plain-English description of a recognised command, or null if [q] isn't one. */
    fun describe(cmd: Cmd): String? = when (cmd) {
        is Cmd.AddTask -> "Add task: ${cmd.text}"
        is Cmd.Alarm -> "Set an alarm for %02d:%02d".format(cmd.hour, cmd.minute)
        is Cmd.Timer -> "Start a timer for " + (if (cmd.seconds >= 60) "${cmd.seconds / 60} min" else "${cmd.seconds} s")
        is Cmd.Navigate -> "Navigate to " + (cmd.dest ?: "home")
        is Cmd.Search -> "Search the web for “${cmd.q}”"
        is Cmd.Translate -> "Translate “${cmd.text}” into Polish"
        is Cmd.Spotify -> if (cmd.q != null) "Play “${cmd.q}” on Spotify" else "Open Spotify"
        is Cmd.Dial -> "Call ${cmd.number}"
        is Cmd.Media -> "Media: ${cmd.action}"
        is Cmd.TimeInPolish -> "The time in Polish"
        is Cmd.PhraseOfDay -> "Phrase of the day"
        is Cmd.ReadMessages -> "Read my messages"
        is Cmd.DriveMode -> if (cmd.on) "Start Drive mode" else "Leave Drive mode"
        is Cmd.Reply, is Cmd.Open, is Cmd.Unknown -> null
    }
}
