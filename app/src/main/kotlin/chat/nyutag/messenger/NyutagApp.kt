// Нютаг. Процесс приложения: настройки, двери и сессия живут, пока жив процесс, а не экран.

package chat.nyutag.messenger

import android.app.Application
import chat.nyutag.messenger.data.Prefs
import chat.nyutag.messenger.doors.DoorHub
import chat.nyutag.messenger.push.Notices
import chat.nyutag.messenger.session.AppSession
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class NyutagApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var doors: DoorHub
        private set
    lateinit var session: AppSession
        private set
    lateinit var notices: Notices
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        doors = DoorHub(this, prefs)
        session = AppSession(this, doors)
        notices = Notices(this, prefs)
        doors.begin()
        // Чат прочитан (здесь или на другом устройстве) — его уведомление и точка на значке больше не нужны.
        // Снимаем только на переходе «было непрочитанное → нет»: свежий пуш может опередить синхронизацию.
        MainScope().launch {
            var unread = emptySet<String>()
            session.lines.collect { lines ->
                val now = lines.filter { it.unread > 0 }.map { it.id }.toSet()
                for (roomID in unread - now) notices.clear(roomID)
                unread = now
            }
        }
    }
}
