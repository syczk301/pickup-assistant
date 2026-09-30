package com.local.pickup

import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.Locale
import org.json.*

object UpdateProtocol {
    const val MAX_APK = 200L * 1024 * 1024
    data class Release(
        @JvmField val code: Int, @JvmField val minSdk: Int, @JvmField val size: Long,
        @JvmField val name: String, @JvmField val url: String, @JvmField val hash: String,
        @JvmField val notes: String, @JvmField val json: String
    )
    @JvmStatic @Throws(IOException::class) fun https(value: String): URL {
        try {
            val url = URL(value)
            if (!url.protocol.equals("https", true) || url.host.isEmpty() || url.userInfo != null || url.ref != null) throw IOException("更新地址必须是有效的 HTTPS 地址")
            return url
        } catch (e: MalformedURLException) { throw IOException("更新地址格式不正确", e) }
    }
    @JvmStatic @Throws(IOException::class) fun parse(raw: String, packageName: String): Release {
        try {
            val j = JSONObject(raw)
            if (j.getInt("schemaVersion") != 1 || j.getString("packageName") != packageName) throw IOException("版本文件与本应用不匹配")
            val code = j.getLong("versionCode"); val size = j.getLong("sizeBytes")
            val min = j.optInt("minSdk", 26); val name = j.getString("versionName")
            val url = j.getString("apkUrl"); val hash = j.getString("sha256").lowercase(Locale.ROOT); val notes = j.optString("releaseNotes", "")
            https(url)
            if (code !in 1..Int.MAX_VALUE.toLong() || size !in 1..MAX_APK || min !in 26..100 || name.isBlank() || name.length > 80 || notes.length > 12000 || !hash.matches(Regex("[0-9a-f]{64}"))) throw IOException("版本文件字段不完整或超出范围")
            return Release(code.toInt(), min, size, name, url, hash, notes, raw)
        } catch (e: JSONException) { throw IOException("无法读取版本文件，请检查 JSON 内容", e) }
    }
    @JvmStatic @Throws(IOException::class) fun fetch(source: String, pkg: String): Release {
        var url = https(source)
        repeat(6) {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 20000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("更新地址跳转无效")
                    url = https(URL(url, location).toString())
                } else {
                    if (status != 200) throw IOException("更新服务器返回 HTTP $status")
                    val out = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count == -1) break; out.write(buffer, 0, count); if (out.size() > 131072) throw IOException("版本文件过大") }
                    }
                    return parse(out.toString("UTF-8"), pkg)
                }
            } finally { connection.disconnect() }
        }
        throw IOException("更新地址跳转次数过多")
    }
    @JvmStatic @Throws(IOException::class) fun verifyBytes(file: File, release: Release) {
        if (!file.isFile || file.length() != release.size) throw IOException("安装包大小与版本文件不一致，请重新下载")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) } }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (hex != release.hash) throw IOException("安装包校验失败，请重新下载")
    }
}
