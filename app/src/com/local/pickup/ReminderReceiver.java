package com.local.pickup;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.Calendar;

public class ReminderReceiver extends BroadcastReceiver {
  static PendingIntent open(Context c) {
    return PendingIntent.getActivity(
        c,
        0,
        new Intent(c, MainActivity.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public static void notify(Context c, String title, String message) {
    if (Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED) return;
    NotificationManager m = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    m.createNotificationChannel(
        new NotificationChannel("pickup", "取件提醒", NotificationManager.IMPORTANCE_DEFAULT));
    m.notify(
        1,
        new Notification.Builder(c, "pickup")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(open(c))
            .setAutoCancel(true)
            .build());
  }

  public static void schedule(Context c) {
    AlarmManager a = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    PendingIntent p =
        PendingIntent.getBroadcast(
            c,
            2,
            new Intent(c, ReminderReceiver.class).setAction("com.local.pickup.REMIND"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    a.cancel(p);
    if (!Store.prefs(c).getBoolean("remind", false)) return;
    Calendar t = Calendar.getInstance();
    t.set(Calendar.HOUR_OF_DAY, Store.prefs(c).getInt("hour", 18));
    t.set(Calendar.MINUTE, Store.prefs(c).getInt("minute", 0));
    t.set(Calendar.SECOND, 0);
    t.set(Calendar.MILLISECOND, 0);
    if (t.getTimeInMillis() <= System.currentTimeMillis()) t.add(Calendar.DATE, 1);
    a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), p);
  }

  @Override
  public void onReceive(Context c, Intent i) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
        && Store.prefs(c).getBoolean("remind", false)) {
      int n = Store.pending(c);
      if (n > 0) notify(c, "记得取快递", "还有 " + n + " 件包裹等待领取");
    }
    schedule(c);
  }
}
