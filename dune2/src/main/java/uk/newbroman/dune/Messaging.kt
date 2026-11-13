package uk.newbroman.dune

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** A message Dune is about to send to one of your contacts. [via] is "sms" or "whatsapp". */
data class Outgoing(val to: Person, val text: String, val via: String) {
    val how get() = if (via == "sms") "by text" else "on WhatsApp"
}

/**
 * "Send a message to Anna saying I'm on my way", "text Tom running late", "WhatsApp Mum see you soon".
 * Text messages go straight out (SMS permission, asked once). WhatsApp has no way for another app to send,
 * so Dune either answers a WhatsApp notification from that person (as Android Auto does), or opens the chat
 * with the message written in and has "Dune split screen" (the accessibility helper) press Send.
 */
object Messaging {
    data class Contact(val name: String, val number: String, val id: Long, val starred: Boolean)
    data class Match(val contact: Contact, val text: String)

    private val joiners = listOf("saying that ", "saying ", "that says ", "and say ", "and tell them ", "to say ", "that ", "with ", "message ")

    /**
     * Splits "anna kowalska i'm running late" into the contact and the message: the longest run of opening
     * words (up to four) that names someone – every word must be a whole word of their name. Starred contacts win ties.
     */
    fun match(rest: String, contacts: List<Contact>): Match? {
        val words = rest.trim().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        for (k in minOf(4, words.size) downTo 1) {
            val said = words.take(k).map { norm(it) }
            if (said.any { it.isEmpty() }) continue
            val hits = contacts.filter { c ->
                val parts = norm(c.name).split(' ').filter { it.isNotEmpty() }
                said.all { it in parts }
            }
            if (hits.isEmpty()) continue
            // exact full name, then first-name match, then starred, then shortest name
            val best = hits.sortedWith(compareByDescending<Contact> { norm(it.name) == said.joinToString(" ") }
                .thenByDescending { norm(it.name).split(' ').firstOrNull() == said[0] }
                .thenByDescending { it.starred }
                .thenBy { it.name.length }).first()
            var text = words.drop(k).joinToString(" ")
            joiners.firstOrNull { text.lowercase().startsWith(it) }?.let { text = text.drop(it.length) }
            return Match(best, text.trim())
        }
        return null
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()

    fun contacts(ctx: Context): List<Contact> {
        if (!Find.canReadContacts(ctx)) return emptyList()
        val out = LinkedHashMap<Long, Contact>()
        runCatching {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.STARRED,
                    ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue; val num = c.getString(1) ?: continue; val id = c.getLong(2)
                    val mobile = c.getInt(4) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE || c.getInt(5) == 1
                    // one number per person, a mobile if they have one
                    if (id !in out || mobile) out[id] = Contact(name, num, id, c.getInt(3) == 1)
                }
            }
        }
        return out.values.toList()
    }

    /** Does WhatsApp know this person? (It adds a profile row to each contact that uses it.) */
    fun onWhatsApp(ctx: Context, id: Long): Boolean = runCatching {
        ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(id.toString(), "vnd.android.cursor.item/vnd.com.whatsapp.profile"), null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    fun whatsappInstalled(ctx: Context) = ctx.packageManager.getLaunchIntentForPackage("com.whatsapp") != null

    /**
     * Turns what was said into a message ready to confirm, or a line saying why not.
     * [via] "sms", "whatsapp", or null for "message"/"tell" (WhatsApp if they're on it, otherwise a text).
     */
    fun prepare(ctx: Context, rest: String, via: String?): Pair<Outgoing?, String?> {
        if (!Find.canReadContacts(ctx)) return null to "Dune needs to see your contacts first"
        val m = match(rest, contacts(ctx)) ?: return null to "I couldn't find ${rest.split(' ').take(2).joinToString(" ")} in your contacts"
        val p = Person(m.contact.name, m.contact.number, m.contact.id)
        val how = via ?: if (whatsappInstalled(ctx) && onWhatsApp(ctx, m.contact.id)) "whatsapp" else "sms"
        return Outgoing(p, m.text, how) to null
    }

    /** Sends it. [done] gets whether it went, and what to say. */
    fun send(act: MainActivity, o: Outgoing, done: (Boolean, String) -> Unit) {
        if (o.via == "sms") {
            val go = {
                val ok = runCatching {
                    val sm = act.getSystemService(android.telephony.SmsManager::class.java)
                    sm.sendMultipartTextMessage(o.to.number, null, sm.divideMessage(o.text), null, null)
                }.isSuccess
                done(ok, if (ok) "Sent" else "Sorry, the text didn't send")
            }
            if (act.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) go()
            else act.requestPerm(Manifest.permission.SEND_SMS) { ok -> if (ok) go() else done(false, "Dune isn't allowed to send texts") }
            return
        }
        // 1) a WhatsApp notification from them that can be answered: no screens at all
        val first = o.to.name.lowercase().split(' ').first()
        NotifService.items.value.firstOrNull { n ->
            n.pkg.startsWith("com.whatsapp") && n.reply != null &&
                (n.title.equals(o.to.name, true) || n.title.lowercase().split(' ').firstOrNull() == first)
        }?.let { n -> val ok = NotifService.sendReply(act, n, o.text); done(ok, if (ok) "Sent" else "Sorry, that didn't send"); return }
        // 2) open the chat with the message written in; the accessibility helper presses Send and comes back
        val url = "https://api.whatsapp.com/send?phone=" + Find.whatsappNumber(o.to.number) + "&text=" + Uri.encode(o.text)
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!act.tryStart(open)) { done(false, "Couldn't open WhatsApp"); return }
        val helper = SplitService.instance
        if (helper == null) { done(false, "Tap send in WhatsApp. Turn on Dune split screen in accessibility settings and I'll press it for you"); return }
        helper.whatsappSend(Drive.intent(act)) { ok -> done(ok, if (ok) "Sent" else "I couldn't press send – check WhatsApp") }
    }
}
