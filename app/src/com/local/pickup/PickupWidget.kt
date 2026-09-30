package com.local.pickup

import android.appwidget.*
import android.content.*
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

class PickupWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context, manager.getAppWidgetOptions(id)))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, views(context, options))
    }

    companion object {
        // RemoteViews uses only platform widgets so launcher hosts can inflate it safely.
        fun views(context: Context, options: Bundle): RemoteViews {
            val pending = Store.load(context).filter { it.completed == 0L }.sortedByDescending { it.created }
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
            val capacity = when { height >= 280 -> 3; height >= 210 -> 2; else -> 1 }
            val result = RemoteViews(context.packageName, R.layout.widget)
            result.setTextViewText(R.id.widget_title, "${pending.size} 件待取")
            result.setViewVisibility(R.id.widget_empty, if (pending.isEmpty()) View.VISIBLE else View.GONE)
            val rows = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)
            val codes = intArrayOf(R.id.widget_code1, R.id.widget_code2, R.id.widget_code3)
            val meta = intArrayOf(R.id.widget_meta1, R.id.widget_meta2, R.id.widget_meta3)
            rows.forEachIndexed { index, row ->
                val parcel = pending.getOrNull(index)
                result.setViewVisibility(row, if (parcel != null && index < capacity) View.VISIBLE else View.GONE)
                if (parcel != null) {
                    result.setTextViewText(codes[index], parcel.code)
                    result.setTextViewText(meta[index], if (parcel.station.isEmpty()) parcel.carrier else "${parcel.station} · ${parcel.carrier}")
                }
            }
            val remaining = (pending.size - capacity).coerceAtLeast(0)
            result.setTextViewText(R.id.widget_more, if (remaining > 0) "还有 $remaining 件 · 点击查看" else "点击打开取件助手")
            result.setOnClickPendingIntent(R.id.widget_root, ReminderReceiver.open(context))
            return result
        }

        fun refresh(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, PickupWidget::class.java))
            if (ids.isNotEmpty()) PickupWidget().onUpdate(c, manager, ids)
        }
    }
}
