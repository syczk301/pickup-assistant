package com.local.pickup

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

class UpdateController(private val activity: Activity) {
    companion object {
        const val DEFAULT_SOURCE = UpdateProtocol.API_SOURCE
    }

    private val context = activity.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var startingDownload = false
    private var downloadGeneration = 0
    private var progressDialog: UpdatePanel? = null
    private var readyDialog: UpdatePanel? = null
    private var visible = false
    private val deferredUi = mutableListOf<() -> Unit>()
    private var checkGeneration = 0
    private var checking = false
    private var verifying = false
    private var closed = false

    private fun live() = !closed && !activity.isFinishing && !activity.isDestroyed

    private fun ui(action: () -> Unit) {
        handler.post { if (live()) {
            if (!visible) deferredUi.add(action)
            else try { action() } catch (e: RuntimeException) {
                checking = false; verifying = false
                android.util.Log.e("PickupUpdate", "Update window failed", e)
                Store.prefs(context).edit().putString("update_error", "无法显示更新窗口，请重新检查更新").apply()
                Toast.makeText(context, "无法显示更新窗口，请重新检查更新", Toast.LENGTH_LONG).show()
            }
        } }
    }

    private fun background(action: () -> Unit) {
        if (!live()) return
        try { worker.execute(action) } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    fun close() {
        closed = true
        UpdatePanel.close(activity)
        deferredUi.clear()
        startingDownload = false
        downloadGeneration++
        progressDialog?.dismiss()
        progressDialog = null
        readyDialog = null
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    private fun source() = Store.prefs(context).getString("update_source", DEFAULT_SOURCE).orEmpty().ifEmpty { DEFAULT_SOURCE }

    fun channel() = UpdateProtocol.channel(Store.prefs(context).getString("update_channel", UpdateProtocol.STABLE).orEmpty())
    fun channelLabel() = if (channel() == UpdateProtocol.BETA) "测试版" else "正式版"
    fun changeChannel(value: String) {
        val next = UpdateProtocol.channel(value)
        if (next == channel()) return
        checkGeneration++
        checking = false
        deferredUi.clear()
        UpdatePanel.close(activity)
        cancelDownload()
        Store.prefs(context).edit().putString("update_channel", next)
            .putLong("update_last_check", 0).remove("update_prompted")
            .putBoolean("update_install_permission", false).apply()
    }

    fun version() =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private fun versionCode() =
        UpdateFiles.code(context.packageManager.getPackageInfo(context.packageName, 0))

    private fun message(title: String, message: String?) {
        if (live())
            UpdatePanel.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
    }

    private fun cancelDownload() {
        startingDownload = false
        downloadGeneration++
        progressDialog?.dismiss()
        synchronized(UpdateFiles) {
            val id = Store.prefs(context).getLong("update_download_id", -1)
            if (id != -1L)
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
            try {
                UpdateFiles.file(context, UpdateFiles.pending(context)).delete()
            } catch (_: Exception) {}
            Store.prefs(context)
                .edit()
                .putLong("update_download_id", -1)
                .putBoolean("update_ready", false)
                .putBoolean("update_selecting", false)
                .putBoolean("update_progress_visible", false)
                .remove("update_release")
                .remove("update_error")
                .commit()
            Unit
        }
    }

    // This panel belongs to the Activity's decor. Keep it through a temporary pause.
    fun pause() { visible = false }

    fun dismissPanel() = UpdatePanel.dismissTop(activity)

    fun resume() {
        visible = true
        val deferred = deferredUi.toList(); deferredUi.clear(); deferred.forEach { ui(it) }
        try {
            if (UpdateFiles.pending(context).code <= versionCode()) cancelDownload()
        } catch (_: IOException) {}
        if (Store.prefs(context).getBoolean("update_install_permission", false)) {
            Store.prefs(context).edit().putBoolean("update_install_permission", false).apply()
            if (activity.packageManager.canRequestPackageInstalls()) install()
            else message("安装权限未开启", "可以稍后在检查更新中继续安装。")
            return
        }
        val id = Store.prefs(context).getLong("update_download_id", -1)
        val restoreProgress = Store.prefs(context).getBoolean("update_progress_visible", false)
        when {
            id != -1L -> {
                if (restoreProgress) progress()
                background {
                    UpdateDownloadReceiver.complete(context, id)
                    ui { if (progressDialog?.isOpen != true) offerReady(false) }
                }
            }
            Store.prefs(context).getBoolean("update_ready", false) -> {
                if (progressDialog?.isOpen != true) offerReady(restoreProgress)
            }
            Store.prefs(context).getBoolean("update_selecting", false) && !startingDownload -> {
                try { download(UpdateFiles.pending(context), restoreProgress) }
                catch (_: IOException) {
                    Store.prefs(context).edit().putBoolean("update_selecting", false).apply()
                }
            }
            restoreProgress && Store.prefs(context).getString("update_error", "").orEmpty().isNotEmpty() -> progress()
            Store.prefs(context).getBoolean("auto_update", true) &&
                source().isNotEmpty() &&
                System.currentTimeMillis() - Store.prefs(context).getLong("update_last_check", 0) >
                    86400000L -> check(false)
        }
    }

    fun check(manual: Boolean) {
        if (Store.prefs(context).getBoolean("update_ready", false)) {
            offerReady(true)
            return
        }
        if (Store.prefs(context).getLong("update_download_id", -1) != -1L) {
            progress()
            return
        }
        if (checking) {
            if (manual) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
            return
        }
        val url = source()
        val selectedChannel = channel()
        val generation = ++checkGeneration
        val candidates = UpdateProtocol.sourceCandidates(url, selectedChannel)
        checking = true
        Store.prefs(context).edit().putLong("update_last_check", System.currentTimeMillis()).apply()
        if (manual) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        background {
            try {
                val release = UpdateProtocol.fetchAny(candidates, context.packageName, selectedChannel)
                ui {
                    if (generation != checkGeneration || selectedChannel != channel()) return@ui
                    checking = false
                    if (url == source())
                        when {
                            release.code <= versionCode() ->
                                if (manual) message("暂无可用更新", "${channelLabel()}渠道 · 当前版本 ${version()}\n此渠道没有更高版本；切换渠道不会自动降级。")
                            release.minSdk > Build.VERSION.SDK_INT ->
                                if (manual)
                                    message("暂不支持此更新", "新版本需要 Android API ${release.minSdk} 或更高版本。")
                            manual ||
                                Store.prefs(context).getInt("update_prompted", 0) !=
                                    release.code -> {
                                Store.prefs(context)
                                    .edit()
                                    .putInt("update_prompted", release.code)
                                    .apply()
                                UpdatePanel.Builder(activity)
                                    .setTitle("${channelLabel()}更新 ${release.name}")
                                    .setMessage(
                                        release.notes.ifEmpty { "有新版本可用。" } +
                                            String.format(
                                                Locale.CHINA,
                                                "\n\n下载大小：%.2f MB",
                                                release.size / 1048576.0,
                                            )
                                    )
                                    .setNegativeButton("稍后", null)
                                    .setPositiveButton("下载更新") { _, _ -> download(release) }
                                    .show()
                            }
                        }
                }
            } catch (e: Exception) {
                ui {
                    if (generation != checkGeneration || selectedChannel != channel()) return@ui
                    checking = false
                    if (manual) message("检查更新失败", UpdateProtocol.failureMessage(e))
                }
            }
        }
    }

    private fun download(release: UpdateProtocol.Release, showProgress: Boolean = true) {
        if (!live() || startingDownload) return
        startingDownload = true
        val generation = ++downloadGeneration
        Store.prefs(context).edit().putString("update_release", release.json)
            .putString("update_error", "").putBoolean("update_ready", false)
            .putBoolean("update_selecting", true)
            .putBoolean("update_progress_visible", showProgress).commit()
        if (showProgress) progress()
        background {
            try {
                val downloadUrl = UpdateProtocol.selectDownloadUrl(release)
                ui {
                    if (!startingDownload || generation != downloadGeneration) return@ui
                    enqueue(release, downloadUrl)
                }
            } catch (e: Exception) {
                ui {
                    if (generation != downloadGeneration) return@ui
                    startingDownload = false
                    Store.prefs(context).edit().putBoolean("update_selecting", false)
                        .putString("update_error", UpdateProtocol.failureMessage(e)).commit()
                    if (Store.prefs(context).getBoolean("update_progress_visible", false)) progress()
                }
            }
        }
    }

    private fun enqueue(release: UpdateProtocol.Release, downloadUrl: String) {
        try {
            synchronized(UpdateFiles) {
                if (Store.prefs(context).getLong("update_download_id", -1) != -1L) {
                    startingDownload = false
                    if (Store.prefs(context).getBoolean("update_progress_visible", false)) progress()
                    return
                }
                val file = UpdateFiles.file(context, release)
                if (file.exists() && !file.delete()) throw IOException("无法清理旧安装包")
                val request =
                    DownloadManager.Request(Uri.parse(downloadUrl))
                        .setTitle("拾件簿 ${release.name}")
                        .setDescription("正在下载更新")
                        .setMimeType("application/vnd.android.package-archive")
                        .addRequestHeader("User-Agent", "PickupAssistant-Android")
                        .addRequestHeader("Accept", "application/octet-stream")
                        .addRequestHeader("Cache-Control", "no-cache")
                        .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setAllowedOverRoaming(false)
                        .setDestinationUri(Uri.fromFile(file))
                Store.prefs(context)
                    .edit()
                    .putString("update_release", release.json)
                    .putBoolean("update_ready", false)
                    .putInt("update_ready_prompted", 0)
                    .putString("update_error", "")
                    .commit()
                val id =
                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(
                        request
                    )
                Store.prefs(context).edit().putLong("update_download_id", id)
                    .putBoolean("update_selecting", false).commit()
            }
            startingDownload = false
            if (Store.prefs(context).getBoolean("update_progress_visible", false)) progress()
        } catch (e: Exception) {
            startingDownload = false
            Store.prefs(context).edit().putBoolean("update_selecting", false)
                .putString("update_error", e.message ?: "无法开始下载，请重试").commit()
            if (Store.prefs(context).getBoolean("update_progress_visible", false)) progress()
        }
    }

    private fun progress() {
        if (!live() || progressDialog?.isOpen == true) return
        Store.prefs(context).edit().putBoolean("update_progress_visible", true).apply()
        val dialog =
            UpdatePanel.Builder(activity)
                .setTitle("下载更新")
                .setMessage("正在下载，完成后会校验安装包。")
                .setNegativeButton("取消下载") { _, _ -> cancelDownload() }
                .setPositiveButton("后台下载") { _, _ ->
                    Store.prefs(context).edit().putBoolean("update_progress_visible", false).apply()
                }
                .create()
        progressDialog = dialog
        dialog.setOnDismissListener {
            if (progressDialog === dialog) progressDialog = null
            if (!closed) Store.prefs(context).edit().putBoolean("update_progress_visible", false).apply()
        }
        var previousBytes = 0L
        var previousTime = System.currentTimeMillis()
        var missingReads = 0
        val poll =
            object : Runnable {
                override fun run() {
                    if (!live() || !dialog.isShowing) return
                    val id = Store.prefs(context).getLong("update_download_id", -1)
                    if (Store.prefs(context).getBoolean("update_ready", false)) {
                        dialog.dismiss()
                        offerReady(true)
                        return
                    }
                    if (id == -1L && startingDownload) {
                        dialog.setMessage("正在选择可用下载线路…")
                        handler.postDelayed(this, 600)
                        return
                    }
                    if (id == -1L) {
                        showDownloadFailure(dialog, Store.prefs(context).getString("update_error", "").orEmpty()
                            .ifEmpty { "下载任务已中断，请重试。" })
                        return
                    }
                    try {
                        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
                            .query(DownloadManager.Query().setFilterById(id))
                            .use { cursor ->
                                if (cursor == null || !cursor.moveToFirst()) {
                                    missingReads++
                                    if (missingReads >= 5) {
                                        val error = "暂时无法读取系统下载任务。可以重试下载，或稍后重新查看进度。"
                                        Store.prefs(context).edit().putString("update_error", error).apply()
                                        showDownloadFailure(dialog, error)
                                    } else if (Store.prefs(context).getString("update_error", "").orEmpty().isEmpty()) {
                                        dialog.setMessage("正在读取下载进度…")
                                    }
                                    handler.postDelayed(this, 600)
                                    return
                                }
                                if (missingReads >= 5 || Store.prefs(context).getString("update_error", "").orEmpty().isNotEmpty()) {
                                    Store.prefs(context).edit().putString("update_error", "").apply()
                                    dialog.setTitle("下载更新")
                                    dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "取消下载") { _, _ -> cancelDownload() }
                                    dialog.setButton(DialogInterface.BUTTON_POSITIVE, "后台下载") { _, _ ->
                                        Store.prefs(context).edit().putBoolean("update_progress_visible", false).apply()
                                    }
                                }
                                missingReads = 0
                                val status =
                                    cursor.getInt(
                                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                                    )
                                val bytes =
                                    cursor.getLong(
                                        cursor.getColumnIndexOrThrow(
                                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                        )
                                    )
                                val total =
                                    cursor.getLong(
                                        cursor.getColumnIndexOrThrow(
                                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                                        )
                                    )
                                val now = System.currentTimeMillis()
                                val speed = ((bytes - previousBytes).coerceAtLeast(0) * 1000.0 / (now - previousTime).coerceAtLeast(1)) / 1024
                                previousBytes = bytes; previousTime = now
                                dialog.setMessage(
                                    when {
                                        status == DownloadManager.STATUS_FAILED -> "下载失败，正在读取原因…"
                                        status == DownloadManager.STATUS_PAUSED ->
                                            "网络不可用或下载暂停，恢复网络后将继续。"
                                        status == DownloadManager.STATUS_SUCCESSFUL -> "下载完成，正在校验…"
                                        total > 0 -> String.format(Locale.CHINA, "下载进度 %d%%\n%.2f / %.2f MB · %.0f KB/s\n可关闭窗口，在后台继续下载。", bytes * 100 / total, bytes / 1048576.0, total / 1048576.0, speed)
                                        else -> "正在下载…"
                                    }
                                )
                                if (
                                    (status == DownloadManager.STATUS_SUCCESSFUL ||
                                        status == DownloadManager.STATUS_FAILED) && !verifying
                                ) {
                                    verifying = true
                                    background {
                                        UpdateDownloadReceiver.complete(context, id)
                                        ui { verifying = false }
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        dialog.setMessage("暂时无法读取下载进度，正在重试…\n${e.message.orEmpty()}")
                    }
                    handler.postDelayed(this, 600)
                }
            }
        dialog.setOnShowListener {
            Store.prefs(context).getString("update_error", "").orEmpty().takeIf { it.isNotEmpty() }
                ?.let { showDownloadFailure(dialog, it) }
            handler.post(poll)
        }
        dialog.show()
    }

    private fun showDownloadFailure(dialog: UpdatePanel, error: String) {
        dialog.setTitle("下载更新失败")
        dialog.setMessage(error)
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "关闭") { _, _ ->
            Store.prefs(context).edit().putBoolean("update_progress_visible", false).apply()
        }
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, "重试下载") { _, _ ->
            try {
                val release = UpdateFiles.pending(context)
                cancelDownload()
                download(release)
            } catch (e: Exception) { message("无法重试下载", e.message) }
        }
    }

    private fun offerReady(manual: Boolean) {
        if (!live() || !Store.prefs(context).getBoolean("update_ready", false) || readyDialog?.isOpen == true) return
        try {
            val release = UpdateFiles.pending(context)
            if (!manual && Store.prefs(context).getInt("update_ready_prompted", 0) == release.code)
                return
            val dialog = UpdatePanel.Builder(activity)
                .setTitle("更新已准备好")
                .setMessage("拾件簿 ${release.name} 已完成校验。安装将保留已有取件记录。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("安装") { _, _ -> install() }
                .create()
            readyDialog = dialog
            dialog.setOnDismissListener { if (readyDialog === dialog) readyDialog = null }
            dialog.setOnShowListener {
                Store.prefs(context).edit().putInt("update_ready_prompted", release.code)
                    .putBoolean("update_progress_visible", false).apply()
            }
            dialog.show()
        } catch (e: IOException) {
            message("更新不可用", e.message)
        }
    }

    private fun install() {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            UpdatePanel.Builder(activity)
                .setTitle("允许安装更新")
                .setMessage("请在接下来的系统设置中允许拾件簿安装应用，返回后继续安装。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往设置") { _, _ ->
                    try {
                        Store.prefs(context)
                            .edit()
                            .putBoolean("update_install_permission", true)
                            .apply()
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    } catch (e: Exception) {
                        Store.prefs(context)
                            .edit()
                            .putBoolean("update_install_permission", false)
                            .apply()
                        message("无法打开安装设置", e.message)
                    }
                }
                .show()
            return
        }
        background {
            try {
                val release = UpdateFiles.pending(context)
                UpdateFiles.verify(context, UpdateFiles.file(context, release), release)
                ui {
                    try {
                        val uri = Uri.parse("content://${context.packageName}.updates/apk")
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .apply { clipData = ClipData.newRawUri("安装包", uri) }
                        )
                    } catch (e: Exception) {
                        message("无法打开安装程序", e.message)
                    }
                }
            } catch (e: Exception) {
                ui {
                    Store.prefs(context).edit().putBoolean("update_ready", false).apply()
                    message("安装包校验失败", e.message)
                }
            }
        }
    }
}
