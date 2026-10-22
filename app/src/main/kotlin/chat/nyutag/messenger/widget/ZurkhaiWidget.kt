// Нютаг. Виджет «Зурхай» на рабочем столе, как на iOS: лунные сутки, стрижка и совет на сегодня, сколько дней до
// Сагаалгана. Данные те же, что во вкладке «Календарь» (zurkhai-days.json в движке). Язык — выбранный в приложении:
// виджет живёт в том же процессе и читает те же настройки. Новый день — будильник на ближайшую полночь.

package chat.nyutag.messenger.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import chat.nyutag.core.nyutag.Sagaalgan
import chat.nyutag.core.nyutag.ZurkhaiDays
import chat.nyutag.messenger.MainActivity
import chat.nyutag.messenger.R
import chat.nyutag.messenger.data.Prefs
import chat.nyutag.messenger.ui.Lang
import chat.nyutag.messenger.ui.P
import chat.nyutag.messenger.ui.Words
import java.time.LocalDate
import java.time.ZoneId

class ZurkhaiWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Полночь, перевод часов или смена пояса: день мог смениться.
            MIDNIGHT, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refresh(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, views(context, options))
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(midnight(context))
    }

    companion object {
        private const val MIDNIGHT = "chat.nyutag.messenger.ZURKHAI_MIDNIGHT"
        /** С такой ширины помещается и совет дня. */
        private const val WIDE_DP = 180

        private val days: ZurkhaiDays? by lazy { runCatching { ZurkhaiDays.bundled() }.getOrNull() }

        /** Перерисовать все виджеты и завести будильник на следующую полночь. Зовётся и при смене языка. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ZurkhaiWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) manager.updateAppWidget(id, views(context, manager.getAppWidgetOptions(id)))
            schedule(context)
        }

        private fun views(context: Context, options: Bundle?): RemoteViews {
            val words = Words(Prefs(context))
            val today = LocalDate.now()
            val day = days?.day(today, english = words.lang == Lang.en)
            val wide = (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0) >= WIDE_DP
            val views = RemoteViews(context.packageName, R.layout.widget_zurkhai)
            if (day != null) {
                views.setTextViewText(R.id.zurkhai_day, words(P.lunarDay, "${day.lunarDay}"))
                views.setTextViewText(R.id.zurkhai_haircut, "${words(P.haircut)}: ${day.haircut}")
                views.setTextColor(R.id.zurkhai_haircut,
                                   context.getColor(if (day.haircutGood) R.color.widget_good else R.color.widget_quiet))
                views.setTextViewText(R.id.zurkhai_favorable, "${words(P.favorable)}: ${day.favorable}")
                views.setViewVisibility(R.id.zurkhai_favorable, if (wide) View.VISIBLE else View.GONE)
            } else {
                // Таблица кончилась (последний день — ZurkhaiDays.last): сутки не выдумываем.
                views.setTextViewText(R.id.zurkhai_day, words(P.calendar))
                views.setTextViewText(R.id.zurkhai_haircut, "")
                views.setViewVisibility(R.id.zurkhai_favorable, View.GONE)
            }
            val left = Sagaalgan.next(today)?.second
            views.setTextViewText(R.id.zurkhai_sagaalgan, when (left) {
                null -> ""
                0 -> words(P.sagaalganToday)
                else -> "${words(P.sagaalgan)} · ${words(P.sagaalganLeft, "$left")}"
            })
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.OPEN_CALENDAR, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(R.id.zurkhai_root, PendingIntent.getActivity(
                context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            return views
        }

        /** Окно в 10 минут после полуночи: точный будильник требует отдельного разрешения, а простой `set` система
         *  вправе отложить на полсуток (на Xiaomi видели +16 ч). */
        private fun schedule(context: Context) {
            val next = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            context.getSystemService(AlarmManager::class.java)
                .setWindow(AlarmManager.RTC, next + 5_000, 10 * 60_000L, midnight(context))
        }

        private fun midnight(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, ZurkhaiWidget::class.java).setAction(MIDNIGHT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
