// Нютаг. Процесс приложения: настройки, двери и сессия живут, пока жив процесс, а не экран.

package chat.nyutag.messenger

import android.app.Application
import chat.nyutag.messenger.data.Prefs
import chat.nyutag.messenger.doors.DoorHub
import chat.nyutag.messenger.push.Notices
import chat.nyutag.messenger.session.AppSession

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
    }
}
