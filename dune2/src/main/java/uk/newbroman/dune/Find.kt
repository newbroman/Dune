package uk.newbroman.dune

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** A person from the phone's contacts, for the command bar. */
data class Person(val name: String, val number: String, val id: Long = -1)

/** The command bar's searches beyond apps: contacts, tasks, messages and Dune's own commands. */
object Find {
    fun canReadContacts(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun people(ctx: Context, q: String, limit: Int = 4): List<Person> {
        if (q.length < 2 || !canReadContacts(ctx)) return emptyList()
        val out = LinkedHashMap<String, Person>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 60) {
                    val name = c.getString(0) ?: continue; val num = c.getString(1) ?: continue
                    // first-name or surname matches first
                    out.putIfAbsent(name, Person(name, num, c.getLong(2)))
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

    /** Rings straight away (asks once for permission to make calls; until then it opens the dialler with the number in). */
    fun callNow(act: MainActivity, p: Person) {
        val now = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(p.number)))
        if (act.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) { act.tryStart(now) || call(act, p); return }
        act.requestPerm(Manifest.permission.CALL_PHONE) { ok -> if (ok) act.tryStart(now) || call(act, p) else call(act, p) }
    }

    /** Your starred contacts (favourites in the Contacts app), with a phone number each. */
    fun starred(ctx: Context, limit: Int = 12): List<Person> {
        if (!canReadContacts(ctx)) return emptyList()
        val out = LinkedHashMap<String, Person>()
        runCatching {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1", null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0) ?: continue; val num = c.getString(1) ?: continue
                    out.putIfAbsent(name, Person(name, num, c.getLong(2)))
                }
            }
        }
        return out.values.toList()
    }

    private const val WA_VOICE = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    private const val WA_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"

    /**
     * A WhatsApp voice or video call to this person, straight away. WhatsApp puts a "call" entry on each contact that
     * uses it; opening that entry rings them. If they have none (not on WhatsApp, or not synced), their chat opens instead.
     */
    fun whatsappCall(ctx: Context, p: Person, video: Boolean): Boolean {
        val mime = if (video) WA_VIDEO else WA_VOICE
        val dataId = runCatching {
            val sel = if (p.id >= 0) "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?" else "${ContactsContract.Data.DISPLAY_NAME} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
            val arg = if (p.id >= 0) p.id.toString() else p.name
            ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), sel, arrayOf(arg, mime), null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
        if (dataId != null) {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.contacts/data/$dataId"), mime)
                .setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ctx.tryStart(i)) return true
        }
        ctx.toast("No WhatsApp call entry for ${p.name} – opening the chat (use the call button there)")
        return whatsapp(ctx, p)
    }
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
        is Cmd.Send -> (if (cmd.via == "sms") "Text " else if (cmd.via == "whatsapp") "WhatsApp " else "Message ") + cmd.rest
        is Cmd.ReadMessages -> "Read my messages"
        is Cmd.DriveMode -> if (cmd.on) "Start Drive mode" else "Leave Drive mode"
        is Cmd.Reply, is Cmd.Open, is Cmd.Unknown -> null
    }
}
