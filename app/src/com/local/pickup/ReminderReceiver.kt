package com.local.pickup

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import java.util.Calendar

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && Store.prefs(context).getBoolean("remind", false)) {
            val count = Store.pending(context)
            if (count > 0) notify(context, "记得取快递", "还有 $count 件包裹等待领取")
        }
        schedule(context)
    }
    companion object {
        fun open(c: Context): PendingIntent = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun notify(c: Context, title: String, message: String) {
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return
            val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("pickup", "取件提醒", NotificationManager.IMPORTANCE_DEFAULT))
            manager.notify(1, Notification.Builder(c, "pickup").setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(message).setContentIntent(open(c)).setAutoCancel(true).build())
        }
        fun schedule(c: Context) {
            val manager = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = PendingIntent.getBroadcast(c, 2, Intent(c, ReminderReceiver::class.java).setAction("com.local.pickup.REMIND"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.cancel(pending)
            if (!Store.prefs(c).getBoolean("remind", false)) return
            val time = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, Store.prefs(c).getInt("hour", 18)); set(Calendar.MINUTE, Store.prefs(c).getInt("minute", 0))
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DATE, 1)
            }
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time.timeInMillis, pending)
        }
    }
}
