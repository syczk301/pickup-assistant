package com.local.pickup

import android.app.Activity
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

// A normal Activity owns the progress UI; pauses and download errors never finish it.
class UpdateDownloadActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var bar: ProgressBar
    private lateinit var primary: TextView
    private lateinit var secondary: TextView
    private var installing = false
    private var waitingPermission = false
    private var dark = false
    private var ink = 0
    private var accent = 0
    private var lastBytes = 0L
    private var lastTime = 0L
    private var speed = 0.0
    private val poll = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 600) }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
    private fun label(value: String, size: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(ink) }
    private fun button(value: String, filled: Boolean, action: () -> Unit) = label(value, 16).apply {
        gravity = Gravity.CENTER; minimumHeight = dp(52); setPadding(dp(18), dp(14), dp(18), dp(14))
        setTextColor(if (filled) Color.WHITE else accent)
        background = shape(if (filled) accent else if (dark) 0xff23394b.toInt() else 0xffeaf3fc.toInt())
        isFocusable = true; setOnClickListener { action() }
    }

    override fun onCreate(saved: Bundle?) {
        dark = Store.prefs(this).getBoolean("dark", false)
        if (dark) setTheme(R.style.AppThemeDark)
        super.onCreate(saved)
        waitingPermission = saved?.getBoolean("waitingPermission") ?: false
        ink = if (dark) 0xffe1edf7.toInt() else 0xff123043.toInt()
        accent = if (dark) 0xff3994ed.toInt() else 0xff1267d5.toInt()
        window.statusBarColor = if (dark) 0xff111c27.toInt() else 0xfff5f6f8.toInt()
        window.navigationBarColor = window.statusBarColor
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)); setBackgroundColor(window.statusBarColor)
        }
        content.addView(button("‹ 返回", false) { finish() }, LinearLayout.LayoutParams(-2, -2))
        title = label("下载更新", 26).apply { setTypeface(null, Typeface.BOLD); setPadding(0, dp(32), 0, dp(18)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(title)
        val version = try { UpdateFiles.pending(this).name } catch (_: IOException) { "" }
        content.addView(label("拾件簿 $version", 16))
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addView(bar, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(28); bottomMargin = dp(18) })
        detail = label("正在连接下载服务器…", 16).apply { setLineSpacing(dp(5).toFloat(), 1f); setTextIsSelectable(true) }
        content.addView(detail)
        content.addView(label("下载完成后校验安装包，再由系统确认安装。返回或切换应用可继续下载；中断时保留进度供重试。", 13).apply { setPadding(0, dp(24), 0, dp(24)) })
        primary = button("后台下载", true) { primaryAction() }
        content.addView(primary, LinearLayout.LayoutParams(-1, -2))
        secondary = button("取消下载", false) { UpdateDownloadService.cancel(this); refresh() }
        content.addView(secondary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(button("复制下载诊断", false) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("下载诊断", UpdateDownloadService.diagnostics(this)))
            Toast.makeText(this, "下载诊断已复制", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(content) })
        refresh()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putBoolean("waitingPermission", waitingPermission) }
    override fun onResume() {
        super.onResume()
        handler.postDelayed({ if (!isDestroyed) { UpdateDownloadService.recover(this); refresh() } }, 1200)
        handler.post(poll)
        if (waitingPermission) {
            waitingPermission = false
            if (packageManager.canRequestPackageInstalls()) install()
            else detail.text = "安装权限未开启。点击安装可再次打开权限设置。"
        }
    }
    override fun onPause() { handler.removeCallbacksAndMessages(null); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy() }

    private fun refresh() {
        if (!::title.isInitialized || isDestroyed || isFinishing || installing) return
        val p = Store.prefs(this)
        val r = try { UpdateFiles.pending(this) } catch (_: IOException) { null }
        val bytes = p.getLong("update_bytes", 0)
        val size = r?.size ?: 0
        val now = SystemClock.elapsedRealtime()
        if (lastTime > 0 && now > lastTime && bytes >= lastBytes) speed = (bytes - lastBytes) / ((now - lastTime) / 1000.0) / 1048576.0
        lastBytes = bytes; lastTime = now
        bar.progress = if (size > 0) (bytes * 100 / size).coerceIn(0, 100).toInt() else 0
        val phase = p.getString("update_phase", "")
        val ready = p.getBoolean("update_ready", false)
        bar.isIndeterminate = phase == "connecting" || phase == "verifying"
        title.text = when { ready -> "更新已准备好"; phase == "failed" -> "下载未完成"; phase == "cancelled" -> "下载已取消"; else -> "下载更新" }
        detail.text = when {
            ready -> "安装包校验通过，可以安装新版本。"
            phase == "failed" -> p.getString("update_error", "下载中断，请重试。")
            phase == "cancelled" -> "已取消本次下载。可重新下载，或返回应用。"
            phase == "connecting" -> "正在连接下载服务器…\n已保留的进度：${String.format(Locale.CHINA, "%.2f MB", bytes / 1048576.0)}"
            phase == "verifying" -> "下载完成，正在校验大小、版本和签名…"
            phase == "downloading" -> String.format(Locale.CHINA, "%d%% · %.2f / %.2f MB\n%.2f MB/s", bar.progress, bytes / 1048576.0, size / 1048576.0, speed)
            else -> p.getString("update_error", "").orEmpty().ifEmpty { "点击下载开始更新。" }
        }
        primary.text = when { ready -> "安装更新"; UpdateDownloadService.busy(this) -> "后台下载"; else -> "重试下载" }
        primary.isEnabled = r != null
        secondary.text = if (UpdateDownloadService.busy(this)) "取消下载" else "返回应用"
        secondary.setOnClickListener { if (UpdateDownloadService.busy(this)) UpdateDownloadService.cancel(this) else finish(); refresh() }
    }

    private fun primaryAction() {
        if (Store.prefs(this).getBoolean("update_ready", false)) install()
        else if (UpdateDownloadService.busy(this)) finish()
        else try { UpdateDownloadService.start(this, UpdateFiles.pending(this)); lastTime = 0; refresh() }
        catch (e: IOException) { detail.text = UpdateProtocol.failureMessage(e) }
    }

    private fun install() {
        if (installing) return
        if (!packageManager.canRequestPackageInstalls()) {
            waitingPermission = true
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            catch (_: RuntimeException) { waitingPermission = false; detail.text = "无法打开安装权限设置，请在系统设置中允许拾件簿安装应用。" }
            return
        }
        installing = true; primary.isEnabled = false; detail.text = "正在核对安装包…"
        worker.execute {
            try {
                val r = UpdateFiles.pending(this)
                UpdateFiles.verify(this, UpdateFiles.file(this, r), r)
                handler.post {
                    if (isDestroyed || isFinishing) return@post
                    installing = false; refresh()
                    try {
                        val uri = Uri.parse("content://$packageName.updates/apk")
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("安装包", uri) })
                    } catch (_: RuntimeException) { detail.text = "无法打开系统安装器，请稍后重试。" }
                }
            } catch (e: Exception) {
                Store.prefs(this).edit().putBoolean("update_ready", false).putString("update_phase", "failed")
                    .putString("update_error", UpdateProtocol.failureMessage(e)).commit()
                handler.post { installing = false; refresh() }
            }
        }
    }
}
