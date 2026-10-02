package com.local.pickup

import android.content.Context
import android.content.pm.*
import android.os.*
import java.io.*

object UpdateFiles {
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
            Store.prefs(c).getBoolean("update_ready", false) -> "安装包已下载，点击安装"
            Store.prefs(c).getLong("update_download_id", -1) != -1L -> "正在下载，点击查看进度"
            else -> Store.prefs(c).getString("update_error", "").orEmpty().ifEmpty { "查看是否有新版本" }
        }
}
