package com.local.pickup

import android.content.Context
import android.content.pm.*
import android.os.*
import java.io.*

object UpdateFiles {
    fun installUri(c: Context, r: UpdateProtocol.Release): android.net.Uri =
        android.net.Uri.parse("content://${c.packageName}.updates/apk/${r.code}/${r.hash}")
    fun needsDownloadPermission(c: Context) = Build.VERSION.SDK_INT < 29 &&
        c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    // Download privately for safe resume/verification; publish only complete, verified APKs.
    fun publish(c: Context, r: UpdateProtocol.Release) {
        val name = "pickup-assistant-${r.name}-${r.code}.apk"
        val source = file(c, r)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = c.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法保存到公共 Download 目录")
            try {
                resolver.openOutputStream(uri, "w")?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("无法写入公共 Download 目录")
                values.clear(); values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) != 1) throw IOException("无法完成安装包保存")
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        } else {
            if (needsDownloadPermission(c)) throw IOException("请允许存储权限，以保存到公共 Download 目录")
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建 Download 目录")
            val target = File(dir, name)
            val temporary = File.createTempFile("pickup-", ".part", dir)
            try {
                source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                UpdateProtocol.verifyBytes(temporary, r)
                if (target.exists() && !target.delete()) throw IOException("无法替换下载目录中的安装包")
                if (!temporary.renameTo(target)) throw IOException("无法保存到 Download 目录")
            } finally { temporary.delete() }
        }
    }

    fun clearInstalled(c: Context): Boolean {
        val r = try { pending(c) } catch (_: IOException) { return false }
        if (r.code > code(c.packageManager.getPackageInfo(c.packageName, 0))) return false
        UpdateDownloadService.cancel(c)
        Store.prefs(c).edit().remove("update_release").putString("update_phase", "up_to_date")
            .putBoolean("update_install_permission", false).putString("update_error", "").commit()
        return true
    }
    fun file(c: Context, r: UpdateProtocol.Release): File {
        val root =
            c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("无法访问下载目录")
        val dir = File(root, "updates")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建下载目录")
        return File(dir, "pickup-update-${r.code}.apk")
    }

    fun code(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode

    fun pending(c: Context) =
        UpdateProtocol.parse(
            Store.prefs(c).getString("update_release", "").orEmpty(),
            c.packageName,
        )

    private fun signatures(info: PackageInfo): Set<String> {
        val signatures =
            if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null)
                info.signingInfo!!.apkContentsSigners
            else info.signatures
        return signatures?.map { it.toCharsString() }?.toSet().orEmpty()
    }

    fun verify(c: Context, file: File, r: UpdateProtocol.Release) {
        UpdateProtocol.verifyBytes(file, r)
        val flags =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val archive =
            c.packageManager.getPackageArchiveInfo(file.path, flags)
                ?: throw IOException("安装包的应用或版本不匹配")
        val own = c.packageManager.getPackageInfo(c.packageName, flags)
        if (
            archive.packageName != own.packageName ||
                code(archive) != r.code ||
                code(archive) <= code(own) ||
                archive.versionName != r.name
        )
            throw IOException("安装包的应用或版本不匹配")
        val expected = signatures(own)
        if (expected.isEmpty() || expected != signatures(archive))
            throw IOException("安装包签名与当前应用不一致")
        if ((archive.applicationInfo?.minSdkVersion ?: 0) > Build.VERSION.SDK_INT)
            throw IOException("此更新需要更高版本的 Android")
    }

    fun status(c: Context): String =
        when {
            Store.prefs(c).getString("update_phase", "") == "up_to_date" -> "当前已安装此版本，点击检查更新"
            Store.prefs(c).getBoolean("update_ready", false) -> "安装包已下载，点击安装"
            UpdateDownloadService.busy(c) -> "正在下载，点击查看进度"
            Store.prefs(c).getString("update_phase", "") == "cancelled" -> "下载已取消，点击重新下载"
            Store.prefs(c).getLong("update_download_id", -1) != -1L -> "正在下载，点击查看进度"
            else -> Store.prefs(c).getString("update_error", "").orEmpty().ifEmpty { "查看是否有新版本" }
        }
}
