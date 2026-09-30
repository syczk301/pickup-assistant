package com.local.pickup

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id == -1L || id != Store.prefs(context).getLong("update_download_id", -1)) return
        val pending = goAsync()
        Thread({ try { complete(context, id) } finally { pending.finish() } }, "update-verify").start()
    }
    companion object {
        fun complete(c: Context, id: Long): Boolean {
            try {
                val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (cursor == null || !cursor.moveToFirst()) throw IOException("下载记录不存在，请重试")
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_FAILED -> throw IOException("安装包下载失败，请重试")
                        DownloadManager.STATUS_SUCCESSFUL -> Unit
                        else -> return false
                    }
                }
                synchronized(UpdateFiles) {
                    if (id != Store.prefs(c).getLong("update_download_id", -1)) return false
                    val release = UpdateFiles.pending(c)
                    UpdateFiles.verify(c, UpdateFiles.file(c, release), release)
                    Store.prefs(c).edit().putBoolean("update_ready", true).putLong("update_download_id", -1).putString("update_error", "").commit()
                    if (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
                        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.createNotificationChannel(NotificationChannel("updates", "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
                        manager.notify(2, Notification.Builder(c, "updates").setSmallIcon(R.drawable.ic_launcher).setContentTitle("取件助手 ${release.name} 已下载").setContentText("打开应用，确认安装新版本").setContentIntent(ReminderReceiver.open(c)).setAutoCancel(true).build())
                    }
                    return true
                }
            } catch (e: Exception) {
                synchronized(UpdateFiles) { if (id == Store.prefs(c).getLong("update_download_id", -1)) Store.prefs(c).edit().putLong("update_download_id", -1).putBoolean("update_ready", false).putString("update_error", e.message ?: "更新下载失败").commit() }
                return false
            }
        }
    }
}
