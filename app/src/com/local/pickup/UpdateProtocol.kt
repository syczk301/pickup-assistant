package com.local.pickup

import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.Locale
import org.json.*

object UpdateProtocol {
    const val LEGACY_SOURCE = "https://github.com/syczk301/pickup-assistant/releases/latest/download/update.json"
    const val API_SOURCE = "https://api.github.com/repos/syczk301/pickup-assistant/contents/update.json?ref=main"
    val SOURCES = listOf(
        API_SOURCE,
        "https://raw.githubusercontent.com/syczk301/pickup-assistant/main/update.json",
        "https://cdn.jsdelivr.net/gh/syczk301/pickup-assistant@main/update.json",
        LEGACY_SOURCE,
    )

    const val STABLE = "stable"
    const val BETA = "beta"
    val BETA_SOURCES = listOf(
        "https://api.github.com/repos/syczk301/pickup-assistant/contents/update-beta.json?ref=main",
        "https://raw.githubusercontent.com/syczk301/pickup-assistant/main/update-beta.json",
        "https://cdn.jsdelivr.net/gh/syczk301/pickup-assistant@main/update-beta.json",
    )
    fun channel(value: String) = if (value == BETA) BETA else STABLE

    // Custom sources remain explicit; old built-in addresses migrate to the API route.
    fun sourceCandidates(stored: String, channel: String = STABLE): List<String> =
        if (channel(channel) == BETA) BETA_SOURCES
        else if (stored.isBlank() || stored in SOURCES || stored in BETA_SOURCES) SOURCES else listOf(stored)

    fun fetchAny(
        sources: List<String>,
        pkg: String,
        expectedChannel: String? = null,
        request: (String, String) -> Release = ::fetch,
    ): Release {
        var failure: IOException? = null
        for (source in sources.distinct()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("更新检查已取消")
            try {
                val release = request(source, pkg)
                if (expectedChannel != null && release.channel != channel(expectedChannel)) throw IOException("更新文件与所选渠道不匹配")
                return release
            }
            catch (e: IOException) { failure = e }
        }
        throw IOException(failureMessage(failure), failure)
    }

    fun failureMessage(error: Throwable?): String = when (error) {
        is SocketTimeoutException -> "连接更新服务器超时，请切换 Wi-Fi 或移动数据后重试。"
        is UnknownHostException -> "无法连接更新服务器，请检查网络连接后重试。"
        is javax.net.ssl.SSLException -> "无法建立安全连接，请检查手机日期和网络后重试。"
        else -> error?.message?.takeIf { it.isNotBlank() } ?: "暂时无法连接更新服务器，请稍后重试。"
    }

    const val MAX_APK = 200L * 1024 * 1024

    data class Release(
        @JvmField val code: Int,
        @JvmField val minSdk: Int,
        @JvmField val size: Long,
        @JvmField val name: String,
        @JvmField val url: String,
        @JvmField val hash: String,
        @JvmField val notes: String,
        @JvmField val json: String,
        @JvmField val channel: String = STABLE,
    )

    @JvmStatic
    @Throws(IOException::class)
    fun https(value: String): URL {
        try {
            val url = URL(value)
            if (
                !url.protocol.equals("https", true) ||
                    url.host.isEmpty() ||
                    url.userInfo != null ||
                    url.ref != null
            )
                throw IOException("更新地址必须是有效的 HTTPS 地址")
            return url
        } catch (e: MalformedURLException) {
            throw IOException("更新地址格式不正确", e)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun parse(raw: String, packageName: String): Release {
        try {
            val j = JSONObject(raw)
            if (j.getInt("schemaVersion") != 1 || j.getString("packageName") != packageName)
                throw IOException("版本文件与本应用不匹配")
            val channel = j.optString("channel", STABLE)
            if (channel != STABLE && channel != BETA) throw IOException("更新渠道无效")
            val code = j.getLong("versionCode")
            val size = j.getLong("sizeBytes")
            val min = j.optInt("minSdk", 26)
            val name = j.getString("versionName")
            val url = j.getString("apkUrl")
            val hash = j.getString("sha256").lowercase(Locale.ROOT)
            val notes = j.optString("releaseNotes", "")
            https(url)
            if (
                code !in 1..Int.MAX_VALUE.toLong() ||
                    size !in 1..MAX_APK ||
                    min !in 26..100 ||
                    name.isBlank() ||
                    name.length > 80 ||
                    notes.length > 12000 ||
                    !hash.matches(Regex("[0-9a-f]{64}"))
            )
                throw IOException("版本文件字段不完整或超出范围")
            return Release(code.toInt(), min, size, name, url, hash, notes, raw, channel)
        } catch (e: JSONException) {
            throw IOException("无法读取版本文件，请检查 JSON 内容", e)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun fetch(source: String, pkg: String): Release {
        var url = https(source)
        repeat(6) {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github.raw+json, application/json")
            connection.setRequestProperty("User-Agent", "PickupAssistant-Android")
            connection.setRequestProperty("Cache-Control", "no-cache")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location =
                        connection.getHeaderField("Location") ?: throw IOException("更新地址跳转无效")
                    url = https(URL(url, location).toString())
                } else {
                    if (status != 200) throw IOException("更新服务器返回 HTTP $status")
                    val out = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            out.write(buffer, 0, count)
                            if (out.size() > 131072) throw IOException("版本文件过大")
                        }
                    }
                    return parse(out.toString("UTF-8"), pkg)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("更新地址跳转次数过多")
    }

    // Compare official GitHub routes only; APK integrity and signing checks remain mandatory.
    fun selectDownloadUrl(release: Release): String {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val results = java.util.concurrent.ExecutorCompletionService<String>(pool)
        val tasks = mutableListOf<java.util.concurrent.Future<String>>()
        tasks.add(results.submit(java.util.concurrent.Callable { probeDownload(release.url) }))
        val prefix = "https://github.com/syczk301/pickup-assistant/releases/download/"
        if (release.url.startsWith(prefix)) tasks.add(results.submit(java.util.concurrent.Callable {
            val parts = release.url.removePrefix(prefix).split('/')
            if (parts.size != 2) throw IOException("下载路径无效")
            val api = "https://api.github.com/repos/syczk301/pickup-assistant/releases/tags/" + parts[0]
            val connection = https(api).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            connection.setRequestProperty("User-Agent", "PickupAssistant-Android")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val raw = try {
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); if (out.size() > 1048576) throw IOException("发布信息过大") }
                    out.toString("UTF-8")
                }
            } finally { connection.disconnect() }
            val assets = JSONObject(raw).getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == parts[1] } ?: throw IOException("找不到安装包")
            val url = asset.getString("url")
            if (!url.startsWith("https://api.github.com/repos/syczk301/pickup-assistant/releases/assets/")) throw IOException("下载线路无效")
            probeDownload(url)
        }))
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
            repeat(tasks.size) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return release.url
                val result = results.poll(left, java.util.concurrent.TimeUnit.NANOSECONDS) ?: return release.url
                try { return result.get() } catch (_: java.util.concurrent.ExecutionException) { }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally { tasks.forEach { it.cancel(true) }; pool.shutdownNow() }
        return release.url
    }

    private fun probeDownload(source: String): String {
        var url = https(source)
        repeat(6) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("下载选择已取消")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "PickupAssistant-Android")
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Range", "bytes=0-32767")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    url = https(URL(url, connection.getHeaderField("Location") ?: throw IOException("下载跳转无效")).toString())
                } else {
                    if (status != 200 && status != 206) throw IOException("下载线路返回 HTTP $status")
                    connection.inputStream.use { input ->
                        val data = ByteArray(32768); var count = 0
                        while (count < data.size) { val n = input.read(data, count, data.size - count); if (n < 0) break; count += n }
                        if (count < 4 || data[0] != 0x50.toByte() || data[1] != 0x4b.toByte()) throw IOException("线路未返回安装包")
                    }
                    // The actual transfer obtains a fresh redirect; signed probe URLs can expire.
                    return source
                }
            } finally { connection.disconnect() }
        }
        throw IOException("下载跳转次数过多")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun verifyBytes(file: File, release: Release) {
        if (!file.isFile || file.length() != release.size) throw IOException("安装包大小与版本文件不一致，请重新下载")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (hex != release.hash) throw IOException("安装包校验失败，请重新下载")
    }
}
