package com.local.pickup;

import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;

public class PickupWidget extends AppWidgetProvider {
  public static void refresh(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    int[] ids = m.getAppWidgetIds(new ComponentName(c, PickupWidget.class));
    if (ids.length > 0) new PickupWidget().onUpdate(c, m, ids);
  }

  @Override
  public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    StringBuilder s = new StringBuilder();
    int n = 0;
    for (Store.Parcel p : Store.load(c))
      if (p.completed == 0) {
        n++;
        if (n <= 3) s.append(p.carrier).append("  ").append(p.code).append("\n");
      }
    for (int id : ids) {
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
      v.setTextViewText(R.id.widget_title, "取件助手 · " + n + " 件待取");
      v.setTextViewText(R.id.widget_codes, n == 0 ? "都取完啦，轻松出门" : s.toString().trim());
      v.setOnClickPendingIntent(R.id.widget_root, ReminderReceiver.open(c));
      m.updateAppWidget(id, v);
    }
  }
}
