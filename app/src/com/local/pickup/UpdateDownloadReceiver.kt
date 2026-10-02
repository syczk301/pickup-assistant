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
        Thread(
                {
                    try {
                        complete(context, id)
                    } finally {
                        pending.finish()
                    }
                },
                "update-verify",
            )
            .start()
    }

    companion object {
        private fun downloadFailure(reason: Int): String = when {
            reason == DownloadManager.ERROR_INSUFFICIENT_SPACE -> "手机空间不足，请清理空间后重试。"
            reason == DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载目录不可用，请检查存储空间后重试。"
            reason == DownloadManager.ERROR_CANNOT_RESUME -> "网络中断后无法续传，请重试下载。"
            reason == DownloadManager.ERROR_HTTP_DATA_ERROR -> "下载连接中断，请切换网络后重试。"
            reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载地址跳转异常，请重试。"
            reason in 400..599 -> "下载服务器返回 HTTP $reason，请切换网络或重试下载。"
            else -> "系统下载失败（原因 $reason），请重试下载。"
        }

        fun complete(c: Context, id: Long): Boolean {
            try {
                val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    // A temporary provider miss must not discard an active download.
                    if (cursor == null || !cursor.moveToFirst()) return false
                    when (
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    ) {
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            throw IOException(downloadFailure(reason))
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> Unit
                        else -> return false
                    }
                }
                synchronized(UpdateFiles) {
                    if (id != Store.prefs(c).getLong("update_download_id", -1)) return false
                    val release = UpdateFiles.pending(c)
                    UpdateFiles.verify(c, UpdateFiles.file(c, release), release)
                    Store.prefs(c)
                        .edit()
                        .putBoolean("update_ready", true)
                        .putLong("update_download_id", -1)
                        .putString("update_error", "")
                        .commit()
                    if (
                        Build.VERSION.SDK_INT < 33 ||
                            c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                                PackageManager.PERMISSION_GRANTED
                    ) {
                        val manager =
                            c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.createNotificationChannel(
                            NotificationChannel(
                                "updates",
                                "应用更新",
                                NotificationManager.IMPORTANCE_DEFAULT,
                            )
                        )
                        manager.notify(
                            2,
                            Notification.Builder(c, "updates")
                                .setSmallIcon(R.drawable.ic_notification)
                                .setContentTitle("拾件簿 ${release.name} 已下载")
                                .setContentText("打开应用，确认安装新版本")
                                .setContentIntent(ReminderReceiver.open(c))
                                .setAutoCancel(true)
                                .build(),
                        )
                    }
                    return true
                }
            } catch (e: Exception) {
                synchronized(UpdateFiles) {
                    if (id == Store.prefs(c).getLong("update_download_id", -1))
                        Store.prefs(c)
                            .edit()
                            .putLong("update_download_id", -1)
                            .putBoolean("update_ready", false)
                            .putString("update_error", e.message ?: "更新下载失败")
                            .commit()
                }
                return false
            }
        }
    }
}
