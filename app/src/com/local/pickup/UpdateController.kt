package com.local.pickup

import android.app.Activity
import android.os.*
import android.widget.Toast
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

class UpdateController(private val activity: Activity) {
    companion object { const val DEFAULT_SOURCE = UpdateProtocol.API_SOURCE }
    private val context = activity.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val deferredUi = mutableListOf<() -> Unit>()
    private var visible = false
    private var checking = false
    private var generation = 0
    private var closed = false
    private fun live() = !closed && !activity.isFinishing && !activity.isDestroyed

    private fun ui(action: () -> Unit) {
        handler.post { if (live()) {
            if (!visible) deferredUi.add(action)
            else try { action() } catch (e: RuntimeException) {
                checking = false
                android.util.Log.e("PickupUpdate", "Update UI failed", e)
                Toast.makeText(context, "无法打开更新页面，请重新检查更新", Toast.LENGTH_LONG).show()
            }
        } }
    }
    fun close() {
        closed = true; generation++; deferredUi.clear()
        UpdatePanel.close(activity); handler.removeCallbacksAndMessages(null); worker.shutdownNow()
    }
    private fun source() = Store.prefs(context).getString("update_source", DEFAULT_SOURCE).orEmpty().ifEmpty { DEFAULT_SOURCE }
    fun channel() = UpdateProtocol.channel(Store.prefs(context).getString("update_channel", UpdateProtocol.STABLE).orEmpty())
    fun channelLabel() = if (channel() == UpdateProtocol.BETA) "测试版" else "正式版"
    fun version() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    private fun versionCode() = UpdateFiles.code(context.packageManager.getPackageInfo(context.packageName, 0))

    fun changeChannel(value: String) {
        val next = UpdateProtocol.channel(value)
        if (next == channel()) return
        generation++; checking = false; deferredUi.clear(); UpdatePanel.close(activity)
        UpdateDownloadService.cancel(context)
        Store.prefs(context).edit().putString("update_channel", next).putLong("update_last_check", 0)
            .remove("update_prompted").remove("update_release").remove("update_phase").remove("update_engine")
            .putBoolean("update_install_permission", false).apply()
    }
    private fun message(title: String, content: String?) {
        if (live()) UpdatePanel.Builder(activity).setTitle(title).setMessage(content).setPositiveButton("知道了", null).show()
    }
    fun pause() { visible = false }
    fun dismissPanel() = UpdatePanel.dismissTop(activity)

    fun resume() {
        visible = true
        val deferred = deferredUi.toList(); deferredUi.clear(); deferred.forEach { ui(it) }
        try {
            if (UpdateFiles.pending(context).code <= versionCode()) {
                UpdateDownloadService.cancel(context)
                Store.prefs(context).edit().remove("update_release").remove("update_phase").remove("update_engine").apply()
            }
        } catch (_: IOException) { }
        UpdateDownloadService.recover(context)
        if (Store.prefs(context).getBoolean("update_install_permission", false)) {
            Store.prefs(context).edit().putBoolean("update_install_permission", false).apply()
            UpdateDownloadService.open(activity)
        } else if (!UpdateDownloadService.hasTask(context) && !Store.prefs(context).getBoolean("update_ready", false) &&
            Store.prefs(context).getBoolean("auto_update", true) &&
            System.currentTimeMillis() - Store.prefs(context).getLong("update_last_check", 0) > 86400000L) check(false)
    }

    fun check(manual: Boolean) {
        if (Store.prefs(context).getBoolean("update_ready", false) || UpdateDownloadService.hasTask(context)) {
            if (manual) UpdateDownloadService.open(activity)
            return
        }
        if (checking) {
            if (manual) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
            return
        }
        val url = source()
        val selectedChannel = channel()
        val current = ++generation
        checking = true
        Store.prefs(context).edit().putLong("update_last_check", System.currentTimeMillis()).apply()
        if (manual) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        worker.execute {
            try {
                val r = UpdateProtocol.fetchAny(UpdateProtocol.sourceCandidates(url, selectedChannel), context.packageName, selectedChannel)
                ui {
                    if (current != generation || selectedChannel != channel()) return@ui
                    checking = false
                    when {
                        r.code <= versionCode() -> if (manual) message("暂无可用更新", "${channelLabel()}渠道 · 当前版本 ${version()}\n此渠道没有更高版本；切换渠道不会自动降级。")
                        r.minSdk > Build.VERSION.SDK_INT -> if (manual) message("暂不支持此更新", "新版本需要 Android API ${r.minSdk} 或更高版本。")
                        manual || Store.prefs(context).getInt("update_prompted", 0) != r.code -> {
                            Store.prefs(context).edit().putInt("update_prompted", r.code).apply()
                            UpdatePanel.Builder(activity).setTitle("${channelLabel()}更新 ${r.name}")
                                .setMessage(r.notes.ifEmpty { "有新版本可用。" } + String.format(Locale.CHINA, "\n\n下载大小：%.2f MB", r.size / 1048576.0))
                                .setNegativeButton("稍后", null).setPositiveButton("下载更新") { _, _ ->
                                    UpdateDownloadService.start(activity, r)
                                    UpdateDownloadService.open(activity)
                                }.show()
                        }
                    }
                }
            } catch (e: Exception) {
                ui { if (current == generation && selectedChannel == channel()) {
                    checking = false
                    if (manual) message("检查更新失败", UpdateProtocol.failureMessage(e))
                } }
            }
        }
    }
}
