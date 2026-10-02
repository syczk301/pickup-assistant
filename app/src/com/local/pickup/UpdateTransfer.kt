package com.local.pickup

import java.io.*
import java.net.HttpURLConnection
import java.net.URL

// One HTTPS transfer, with bounded redirects and a checked Range resume.
// The connection factory allows regression tests to use a local HTTP fixture.
class UpdateTransfer(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() { cancelled = true; connection?.disconnect() }

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已取消")
    }

    fun download(
        release: UpdateProtocol.Release,
        source: String,
        part: File,
        progress: (Long) -> Unit,
        response: (String, Int, Long) -> Unit = { _, _, _ -> },
    ) {
        var url = UpdateProtocol.https(source)
        if (part.length() > release.size && !part.delete()) throw IOException("无法清理旧下载文件")
        var bytes = part.length()
        progress(bytes)
        if (bytes == release.size) return
        var redirects = 0
        var segments = 0
        while (bytes < release.size) {
            checkCancelled()
            val c = connect(url)
            connection = c
            c.connectTimeout = 12000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "PickupAssistant-Android")
            c.setRequestProperty("Accept", "application/octet-stream")
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("Cache-Control", "no-cache")
            if (bytes > 0) c.setRequestProperty("Range", "bytes=$bytes-")
            try {
                checkCancelled()
                val status = c.responseCode
                response(url.host, status, bytes)
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (++redirects > 6) throw IOException("下载地址跳转次数过多")
                    url = UpdateProtocol.https(URL(url, c.getHeaderField("Location") ?: throw IOException("下载地址跳转无效")).toString())
                    continue
                }
                if (status != 200 && status != 206) throw IOException("下载服务器返回 HTTP $status，请切换网络后重试。")
                if (++segments > 16) throw IOException("下载服务器返回了过多的数据分段，请重试。")
                if (c.getHeaderField("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity", true) })
                    throw IOException("下载服务器返回了不支持的数据格式")
                val length = c.contentLengthLong
                val end: Long
                if (status == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
                        .matchEntire(c.getHeaderField("Content-Range").orEmpty()) ?: throw IOException("下载续传响应无效，请重试。")
                    val start = range.groupValues[1].toLongOrNull()
                    val last = range.groupValues[2].toLongOrNull()
                    val total = range.groupValues[3].toLongOrNull()
                    if (start != bytes || last == null || last < bytes || last >= release.size || total != release.size ||
                        (length >= 0 && length != last - bytes + 1)) throw IOException("下载续传位置与安装包不一致，请重试。")
                    end = last + 1
                } else {
                    if (length >= 0 && length != release.size) throw IOException("下载内容大小与安装包不一致，请重试。")
                    // Servers may ignore Range. Replace the partial file rather than append twice.
                    bytes = 0
                    end = release.size
                    progress(bytes)
                }
                var lastProgress = System.nanoTime()
                c.inputStream.use { input ->
                    FileOutputStream(part, status == 206 && bytes > 0).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            checkCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            checkCancelled()
                            if (bytes + n > end) throw IOException("下载内容超过安装包大小，请重试。")
                            output.write(buffer, 0, n)
                            bytes += n
                            if (System.nanoTime() - lastProgress >= 500000000L) {
                                progress(bytes); lastProgress = System.nanoTime()
                            }
                        }
                        output.fd.sync()
                    }
                }
                progress(bytes)
                if (bytes != end) throw IOException("下载连接中断，已保留进度，请重试续传。")
            } finally {
                c.disconnect()
                if (connection === c) connection = null
            }
        }
        checkCancelled()
    }
}
