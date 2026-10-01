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
    private var checking = false
    private var verifying = false
    private var closed = false

    private fun live() = !closed && !activity.isFinishing && !activity.isDestroyed

    private fun ui(action: () -> Unit) {
        handler.post { if (live()) action() }
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    private fun source() = Store.prefs(context).getString("update_source", DEFAULT_SOURCE).orEmpty().ifEmpty { DEFAULT_SOURCE }

    fun version() =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private fun versionCode() =
        UpdateFiles.code(context.packageManager.getPackageInfo(context.packageName, 0))

    private fun message(title: String, message: String?) {
        if (live())
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
    }

    private fun cancelDownload() =
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
                .remove("update_release")
                .remove("update_error")
                .commit()
            Unit
        }

    fun resume() {
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
        when {
            id != -1L ->
                worker.execute {
                    UpdateDownloadReceiver.complete(context, id)
                    ui { offerReady(false) }
                }
            Store.prefs(context).getBoolean("update_ready", false) -> offerReady(false)
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
        checking = true
        Store.prefs(context).edit().putLong("update_last_check", System.currentTimeMillis()).apply()
        if (manual) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        worker.execute {
            try {
                val release = UpdateProtocol.fetchAny(UpdateProtocol.sourceCandidates(url), context.packageName)
                ui {
                    checking = false
                    if (url == source())
                        when {
                            release.code <= versionCode() ->
                                if (manual) message("已是最新版本", "当前版本 ${version()}")
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
                                AlertDialog.Builder(activity)
                                    .setTitle("发现新版本 ${release.name}")
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
                    checking = false
                    if (manual) message("检查更新失败", UpdateProtocol.failureMessage(e))
                }
            }
        }
    }

    private fun download(release: UpdateProtocol.Release) {
        try {
            synchronized(UpdateFiles) {
                if (Store.prefs(context).getLong("update_download_id", -1) != -1L) {
                    progress()
                    return
                }
                val file = UpdateFiles.file(context, release)
                if (file.exists() && !file.delete()) throw IOException("无法清理旧安装包")
                val request =
                    DownloadManager.Request(Uri.parse(release.url))
                        .setTitle("取件助手 ${release.name}")
                        .setDescription("正在下载更新")
                        .setMimeType("application/vnd.android.package-archive")
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
                Store.prefs(context).edit().putLong("update_download_id", id).commit()
            }
            progress()
        } catch (e: Exception) {
            message("无法开始下载", e.message)
        }
    }

    private fun progress() {
        val dialog =
            AlertDialog.Builder(activity)
                .setTitle("下载更新")
                .setMessage("正在下载，完成后会校验安装包。")
                .setNegativeButton("取消下载") { _, _ -> cancelDownload() }
                .setPositiveButton("后台下载", null)
                .create()
        dialog.show()
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
                    if (id == -1L) {
                        dialog.dismiss()
                        Store.prefs(context)
                            .getString("update_error", "")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { message("下载更新失败", it) }
                        return
                    }
                    try {
                        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
                            .query(DownloadManager.Query().setFilterById(id))
                            .use { cursor ->
                                if (cursor == null || !cursor.moveToFirst()) {
                                    dialog.dismiss()
                                    cancelDownload()
                                    message("下载已中断", "请重新检查更新并下载。")
                                    return
                                }
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
                                dialog.setMessage(
                                    when {
                                        status == DownloadManager.STATUS_PAUSED ->
                                            "网络不可用或下载暂停，恢复网络后将继续。"
                                        status == DownloadManager.STATUS_SUCCESSFUL -> "下载完成，正在校验…"
                                        total > 0 -> "下载进度 ${bytes * 100 / total}%"
                                        else -> "正在下载…"
                                    }
                                )
                                if (
                                    (status == DownloadManager.STATUS_SUCCESSFUL ||
                                        status == DownloadManager.STATUS_FAILED) && !verifying
                                ) {
                                    verifying = true
                                    worker.execute {
                                        UpdateDownloadReceiver.complete(context, id)
                                        ui { verifying = false }
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        dialog.dismiss()
                        message("无法读取下载进度", e.message)
                        return
                    }
                    handler.postDelayed(this, 600)
                }
            }
        handler.post(poll)
    }

    private fun offerReady(manual: Boolean) {
        if (!live() || !Store.prefs(context).getBoolean("update_ready", false)) return
        try {
            val release = UpdateFiles.pending(context)
            if (!manual && Store.prefs(context).getInt("update_ready_prompted", 0) == release.code)
                return
            Store.prefs(context).edit().putInt("update_ready_prompted", release.code).apply()
            AlertDialog.Builder(activity)
                .setTitle("更新已准备好")
                .setMessage("取件助手 ${release.name} 已完成校验。安装将保留已有取件记录。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("安装") { _, _ -> install() }
                .show()
        } catch (e: IOException) {
            message("更新不可用", e.message)
        }
    }

    private fun install() {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("允许安装更新")
                .setMessage("请在接下来的系统设置中允许取件助手安装应用，返回后继续安装。")
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
        worker.execute {
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
