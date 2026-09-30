package com.local.pickup

import android.appwidget.*
import android.content.*
import android.widget.RemoteViews

class PickupWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = Store.load(context).filter { it.completed == 0L }
        val codes = pending.take(3).joinToString("\n") { "${it.carrier}  ${it.code}" }
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.widget_title, "取件助手 · ${pending.size} 件待取")
            views.setTextViewText(R.id.widget_codes, if (pending.isEmpty()) "都取完啦，轻松出门" else codes)
            views.setOnClickPendingIntent(R.id.widget_root, ReminderReceiver.open(context))
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun refresh(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, PickupWidget::class.java))
            if (ids.isNotEmpty()) PickupWidget().onUpdate(c, manager, ids)
        }
    }
}
