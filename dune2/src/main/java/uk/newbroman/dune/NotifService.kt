package uk.newbroman.dune

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

data class NotifItem(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    val time: Long,
    val intent: PendingIntent?,
    val autoCancel: Boolean,
    /** A chat/SMS-style notification. */
    val isMessage: Boolean = false,
    /** The app's own "Reply" action, if it offers one. */
    val reply: Notification.Action? = null,
    /** "message", "email" or "other" – for the inbox filters. */
    val kind: String = "other",
)

/** Mirrors the phone's dismissable notifications so the home screen can list them. */
class NotifService : NotificationListenerService() {
    companion object {
        @Volatile var instance: NotifService? = null
        val items = MutableStateFlow<List<NotifItem>>(emptyList())
        private val spoken = LinkedHashSet<String>()

        /** Send [text] through the notification's own reply action (as Android Auto does). */
        fun sendReply(ctx: Context, n: NotifItem, text: String): Boolean {
            val action = n.reply ?: return false
            val inputs = action.remoteInputs ?: return false
            return try {
                val intent = Intent()
                val results = Bundle()
                inputs.forEach { results.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(inputs, intent, results)
                action.actionIntent.send(ctx, 0, intent)
                true
            } catch (e: Exception) { false }
        }
    }

    override fun onListenerConnected() { instance = this; refresh() }
    override fun onListenerDisconnected() { instance = null; items.value = emptyList() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
        val n = items.value.firstOrNull { it.key == sbn?.key } ?: return
        if (n.kind != "other") runCatching {
            val app = packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.pkg, 0)).toString()
            InboxLog.add(this, InboxLog.Entry(n.time, n.pkg, app, n.title, n.text, n.kind))
        }
        if (n.isMessage && Drive.active.value && Store.get(this).driveReadAloud.value) {
            val id = n.key + "|" + n.text
            if (spoken.add(id)) {
                if (spoken.size > 200) spoken.remove(spoken.first())
                val app = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.pkg, 0)).toString()
                }.getOrDefault("")
                Speaker.speak(this, "$app message from ${n.title}. ${n.text}", flush = false)
            }
        }
    }

    private fun refresh() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        items.value = active
            .filter { !it.isOngoing && it.packageName != packageName }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .map { s ->
                val nt = s.notification
                val ex = nt.extras
                val reply = nt.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
                // Messaging-style notifications carry the latest line in their message list.
                val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
                val lastMsg = (msgs?.lastOrNull() as? Bundle)?.getCharSequence("text")?.toString()
                NotifItem(
                    key = s.key,
                    pkg = s.packageName,
                    title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                        ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty(),
                    text = lastMsg ?: (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                    time = s.postTime,
                    intent = nt.contentIntent,
                    autoCancel = nt.flags and Notification.FLAG_AUTO_CANCEL != 0,
                    isMessage = nt.category == Notification.CATEGORY_MESSAGE || msgs != null || reply != null,
                    reply = reply,
                    kind = Kind.of(s.packageName, nt.category, nt.category == Notification.CATEGORY_MESSAGE || msgs != null || reply != null),
                )
            }
    }
}
