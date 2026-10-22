// Нютаг. Пуши через Firebase Cloud Messaging (исключение по слову владельца 21.10: без него телефон с Google
// не будит приложение в фоне). Шлюз на ядре кладёт в пуш только room_id и event_id, поэтому Google не видит
// ни текста, ни имён: уведомление собирает само устройство из своего хранилища и расшифровывает своими ключами.

package chat.nyutag.messenger.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import chat.nyutag.core.push.AndroidPusher
import chat.nyutag.core.push.PushPreview
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.MainActivity
import chat.nyutag.messenger.NyutagApp
import chat.nyutag.messenger.R
import chat.nyutag.messenger.data.Prefs
import chat.nyutag.messenger.ui.P
import chat.nyutag.messenger.ui.Words
import chat.nyutag.messenger.ui.kindLabel
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class PushService : FirebaseMessagingService() {
    private val app get() = application as NyutagApp

    override fun onNewToken(token: String) {
        app.session.pushToken(token)
    }

    /** Свой поток Firebase: ждать здесь можно, пока система держит процесс (у пуша высокой важности — секунды). */
    override fun onMessageReceived(message: RemoteMessage) {
        val (roomID, eventID) = AndroidPusher.target(message.data) ?: return
        if (BuildConfig.DEBUG) Log.d("ui-check", "пуш: ${roomID.take(8)}… приоритет ${message.priority}")
        // Пустой список — показывать нечего (чат открыт, сигнал звонка); null — вышло время, и тогда, как iOS,
        // показываем нейтральное «Новое сообщение»: молча потерять сообщение хуже.
        val outcome = runBlocking { withTimeoutOrNull(18_000) { listOfNotNull(app.session.pushPreview(roomID, eventID)) } }
        val preview = outcome?.firstOrNull() ?: (if (outcome == null) app.session.neutralPreview(roomID, eventID) else null) ?: return
        app.notices.show(preview)
    }
}

/** Уведомления о сообщениях: одно на чат, новое заменяет прежнее; нажатие открывает этот чат. */
class Notices(private val context: Context, private val prefs: Prefs) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun show(preview: PushPreview) {
        val words = Words(prefs)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, words(P.searchMessages), NotificationManager.IMPORTANCE_HIGH))
        val label = kindLabel(preview.kind, words)
        val title = preview.roomName?.takeIf { it.isNotEmpty() } ?: preview.senderName ?: "Нютаг"
        val text = if (!preview.isDirect && preview.senderName != null) "${preview.senderName}: $label" else label
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.OPEN_ROOM, preview.roomID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, preview.roomID.hashCode(), open,
                                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.notice)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { manager.notify(preview.roomID, ID, notification) }
    }

    /** Чат открыт: его уведомление больше не нужно. */
    fun clear(roomID: String) = manager.cancel(roomID, ID)

    private companion object {
        const val CHANNEL = "messages"
        const val ID = 1
    }
}
