package uk.newbroman.dune

import android.app.Notification
import android.app.PendingIntent
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
)

/** Mirrors the phone's dismissable notifications so the home screen can list them. */
class NotifService : NotificationListenerService() {
    companion object {
        @Volatile var instance: NotifService? = null
        val items = MutableStateFlow<List<NotifItem>>(emptyList())
    }

    override fun onListenerConnected() { instance = this; refresh() }
    override fun onListenerDisconnected() { instance = null; items.value = emptyList() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        items.value = active
            .filter { !it.isOngoing && it.packageName != packageName }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .map { s ->
                val ex = s.notification.extras
                NotifItem(
                    key = s.key,
                    pkg = s.packageName,
                    title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    time = s.postTime,
                    intent = s.notification.contentIntent,
                    autoCancel = s.notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
                )
            }
    }
}
