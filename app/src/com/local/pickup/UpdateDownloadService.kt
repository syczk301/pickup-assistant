package com.local.pickup

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import java.io.*
import java.util.UUID
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Locale

class UpdateDownloadService : Service() {
    companion object {
        private val lock = Any()
        private const val NOTIFICATION = 3
        @Volatile private var instance: UpdateDownloadService? = null
        @Volatile private var runningJob = ""
        private val activePhases = setOf("connecting", "downloading", "verifying")

        fun busy(c: Context) = Store.prefs(c).getString("update_phase", "") in activePhases
        fun running(c: Context) = runningJob.isNotEmpty() && runningJob == Store.prefs(c).getString("update_job", "")
        fun hasTask(c: Context) = Store.prefs(c).getString("update_engine", "") == "app" &&
            Store.prefs(c).getString("update_release", "").orEmpty().isNotEmpty() &&
            Store.prefs(c).getString("update_phase", "") != "cancelled"

        fun open(c: Context) {
            c.startActivity(Intent(c, UpdateDownloadActivity::class.java).apply {
                if (c !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }

        private fun part(c: Context, r: UpdateProtocol.Release) = File(UpdateFiles.file(c, r).path + ".part")

        private fun stopLegacy(c: Context) {
            val id = Store.prefs(c).getLong("update_download_id", -1)
            if (id != -1L) try { (c.getSystemService(DOWNLOAD_SERVICE) as DownloadManager).remove(id) } catch (_: RuntimeException) { }
        }

        fun start(c: Context, r: UpdateProtocol.Release) {
            synchronized(lock) {
                if (busy(c) && running(c)) return
                val prefs = Store.prefs(c)
                val previous = try { UpdateFiles.pending(c) } catch (_: IOException) { null }
                instance?.transfer?.cancel()
                stopLegacy(c)
                if (previous != null && (previous.code != r.code || previous.hash != r.hash)) {
                    try { part(c, previous).delete(); UpdateFiles.file(c, previous).delete() } catch (_: IOException) { }
                }
                val job = UUID.randomUUID().toString()
                prefs.edit().putString("update_engine", "app").putString("update_job", job)
                    .putString("update_release", r.json).putString("update_phase", "connecting")
                    .putLong("update_download_id", -1).putBoolean("update_selecting", false)
                    .putBoolean("update_progress_visible", false).putBoolean("update_ready", false)
                    .putInt("update_ready_prompted", 0).putString("update_error", "")
                    .putLong("update_bytes", try { part(c, r).length() } catch (_: IOException) { 0 })
                    .putString("update_log", "").commit()
                log(c, "开始下载 ${r.name}；Android ${Build.VERSION.SDK_INT}")
                try {
                    c.startForegroundService(Intent(c, UpdateDownloadService::class.java).putExtra("job", job))
                } catch (e: RuntimeException) {
                    fail(c, job, "无法启动下载，请重新打开下载页面后重试。", e.javaClass.simpleName)
                }
            }
        }

        fun cancel(c: Context) {
            synchronized(lock) {
                val r = try { UpdateFiles.pending(c) } catch (_: IOException) { null }
                stopLegacy(c)
                Store.prefs(c).edit().putString("update_job", "").putString("update_phase", "cancelled")
                    .putLong("update_download_id", -1).putBoolean("update_ready", false)
                    .putBoolean("update_selecting", false).putBoolean("update_progress_visible", false)
                    .putString("update_error", "").putLong("update_bytes", 0).commit()
                instance?.transfer?.cancel()
                c.stopService(Intent(c, UpdateDownloadService::class.java))
                if (r != null) try { part(c, r).delete(); UpdateFiles.file(c, r).delete() } catch (_: IOException) { }
                log(c, "用户取消下载")
            }
        }

        fun recover(c: Context) {
            synchronized(lock) {
                if (busy(c) && !running(c)) {
                    val job = Store.prefs(c).getString("update_job", "").orEmpty()
                    fail(c, job, "下载已中断，进度已保留。点击重试可继续下载。", "进程或服务已停止")
                }
                // Convert old system tasks to an explicit retry; never silently close the screen.
                if (!hasTask(c) && !Store.prefs(c).getBoolean("update_ready", false) &&
                    (Store.prefs(c).getLong("update_download_id", -1) != -1L || Store.prefs(c).getBoolean("update_selecting", false))) {
                    stopLegacy(c)
                    Store.prefs(c).edit().putLong("update_download_id", -1).putBoolean("update_selecting", false)
                        .putString("update_engine", "app").putString("update_phase", "failed")
                        .putString("update_error", "旧下载任务已停止，请点击重试使用新的下载方式。").commit()
                    log(c, "旧版系统下载任务已转为手动重试")
                }
            }
        }

        private fun owns(c: Context, job: String) = job.isNotEmpty() && job == Store.prefs(c).getString("update_job", "")

        private fun fail(c: Context, job: String, message: String, detail: String) {
            synchronized(lock) {
                if (!owns(c, job)) return
                val safe = message.replace(Regex("https?://\\S+"), "更新服务器").take(400)
                val saved = try { part(c, UpdateFiles.pending(c)).length() } catch (_: IOException) { 0 }
                Store.prefs(c).edit().putString("update_phase", "failed").putBoolean("update_ready", false)
                    .putString("update_error", safe).putLong("update_bytes", saved).commit()
                log(c, "失败：$detail；$safe")
            }
        }

        private fun log(c: Context, message: String) {
            synchronized(lock) {
                val prefs = Store.prefs(c)
                val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(java.util.Date())
                val lines = (prefs.getString("update_log", "").orEmpty().lines().filter { it.isNotBlank() } + "$time $message").takeLast(20)
                prefs.edit().putString("update_log", lines.joinToString("\n").takeLast(4000)).apply()
            }
        }

        fun diagnostics(c: Context): String {
            val p = Store.prefs(c)
            val version = c.packageManager.getPackageInfo(c.packageName, 0).versionName
            val target = try { UpdateFiles.pending(c).name } catch (_: IOException) { "无" }
            return "拾件簿 $version → $target\n${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "状态：${p.getString("update_phase", "无")}；已下载 ${p.getLong("update_bytes", 0)} 字节\n" +
                "${p.getString("update_error", "")}\n${p.getString("update_log", "")}".trim()
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var transfer: UpdateTransfer? = null
    private var job = ""

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("update_download", "更新下载", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(message: String, bytes: Long, size: Long) =
        Notification.Builder(this, "update_download").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("拾件簿更新").setContentText(message).setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 3, Intent(this, UpdateDownloadActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setProgress(100, if (size > 0) (bytes * 100 / size).toInt() else 0, bytes <= 0).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra("job").orEmpty()
        if (!owns(this, requested)) { stopSelf(startId); return START_NOT_STICKY }
        val release = try { UpdateFiles.pending(this) } catch (e: IOException) {
            fail(this, requested, UpdateProtocol.failureMessage(e), e.javaClass.simpleName)
            stopSelf(startId); return START_NOT_STICKY
        }
        try {
            val n = notification("正在连接下载服务器", 0, release.size)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, n)
        } catch (e: RuntimeException) {
            fail(this, requested, "无法启动下载服务，请重新打开应用后重试。", e.javaClass.simpleName)
            stopSelf(startId); return START_NOT_STICKY
        }
        if (job == requested && running(this)) return START_NOT_STICKY
        transfer?.cancel()
        job = requested
        runningJob = requested
        executor.execute { runDownload(release, requested, startId) }
        return START_NOT_STICKY
    }

    private fun runDownload(r: UpdateProtocol.Release, requested: String, startId: Int) {
        val downloader = UpdateTransfer()
        transfer = downloader
        try {
            if (!owns(this, requested)) return
            val source = UpdateProtocol.selectDownloadUrl(r)
            if (!owns(this, requested)) return
            log(this, "下载线路：${UpdateProtocol.https(source).host}")
            val partial = part(this, r)
            downloader.download(r, source, partial, { bytes ->
                synchronized(lock) {
                    if (!owns(this, requested)) { downloader.cancel(); return@synchronized }
                    Store.prefs(this).edit().putString("update_phase", "downloading").putLong("update_bytes", bytes).apply()
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification("已下载 ${bytes * 100 / r.size}%", bytes, r.size))
                }
            }, { host, status, offset -> if (owns(this, requested)) log(this, "$host HTTP $status；续传位置 $offset") })
            synchronized(lock) {
                if (!owns(this, requested)) return
                Store.prefs(this).edit().putString("update_phase", "verifying").commit()
                log(this, "下载完成，开始校验安装包")
            }
            try { UpdateFiles.verify(this, partial, r) }
            catch (e: Exception) { synchronized(lock) { if (owns(this, requested)) partial.delete() }; throw e }
            synchronized(lock) {
                if (!owns(this, requested)) return
                val final = UpdateFiles.file(this, r)
                if (final.exists() && !final.delete()) throw IOException("无法清理旧安装包")
                if (!partial.renameTo(final)) throw IOException("无法保存安装包，请检查存储空间。")
                Store.prefs(this).edit().putString("update_phase", "ready").putBoolean("update_ready", true)
                    .putString("update_error", "").putLong("update_bytes", r.size).commit()
                log(this, "大小、SHA-256、包名、版本、签名校验通过")
            }
        } catch (e: Exception) {
            fail(this, requested, UpdateProtocol.failureMessage(e), e.javaClass.simpleName)
        } finally {
            if (transfer === downloader) transfer = null
            Handler(mainLooper).post {
                if (instance === this && job == requested) {
                    runningJob = ""
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (owns(this, requested) &&
                        (Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                        val ready = Store.prefs(this).getBoolean("update_ready", false)
                        val state = Store.prefs(this).getString("update_phase", "")
                        if (ready || state == "failed") {
                            val message = if (ready) "校验通过，点击确认安装" else "下载未完成，点击查看原因并重试"
                            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION,
                                Notification.Builder(this, "update_download").setSmallIcon(R.drawable.ic_notification)
                                    .setContentTitle(if (ready) "拾件簿 ${r.name} 已下载" else "拾件簿更新下载中断")
                                    .setContentText(message).setAutoCancel(true)
                                    .setContentIntent(PendingIntent.getActivity(this, 3, Intent(this, UpdateDownloadActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
                        }
                    }
                    stopSelf(startId)
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        fail(this, job, "下载时间过长，进度已保留，请重试。", "系统下载服务超时")
        transfer?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        transfer?.cancel()
        executor.shutdownNow()
        synchronized(lock) {
            if (owns(this, job) && busy(this)) fail(this, job, "下载已中断，进度已保留，请重试。", "下载服务停止")
            if (runningJob == job) runningJob = ""
            if (instance === this) instance = null
        }
        super.onDestroy()
    }
}
