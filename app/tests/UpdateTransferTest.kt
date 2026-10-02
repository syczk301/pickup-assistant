import com.local.pickup.UpdateProtocol
import com.local.pickup.UpdateTransfer
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject

fun main() {
    var checks = 0
    val data = ByteArray(524288) { (it % 251).toByte() }
    val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    val r = UpdateProtocol.parse(JSONObject().put("schemaVersion", 1).put("packageName", "com.local.pickup")
        .put("versionCode", 30).put("versionName", "test").put("apkUrl", "https://fixture.test/full")
        .put("sizeBytes", data.size).put("sha256", hash).put("minSdk", 26).toString(), "com.local.pickup")
    val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newCachedThreadPool()
    server.executor = executor
    server.createContext("/") { ex ->
        try {
            val path = ex.requestURI.path
            val range = ex.requestHeaders.getFirst("Range")
            requests.add(path to range)
            fun header(key: String, value: String) { ex.responseHeaders.set(key, value) }
            fun body(status: Int, bytes: ByteArray, chunked: Boolean = false) {
                ex.sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            when (path) {
                "/redirect" -> { header("Location", "https://other.test/full"); ex.sendResponseHeaders(302, -1) }
                "/downgrade" -> { header("Location", "http://fixture.test/full"); ex.sendResponseHeaders(302, -1) }
                "/loop" -> { header("Location", "https://fixture.test/loop"); ex.sendResponseHeaders(302, -1) }
                "/range" -> {
                    val start = range!!.removePrefix("bytes=").removeSuffix("-").toInt()
                    header("Content-Range", "bytes $start-${data.lastIndex}/${data.size}")
                    body(206, data.copyOfRange(start, data.size))
                }
                "/bad-range" -> { header("Content-Range", "bytes 0-${data.lastIndex}/${data.size}"); body(206, data) }
                "/missing-range" -> body(206, data)
                "/wrong-total" -> { header("Content-Range", "bytes 0-${data.lastIndex}/${data.size + 1}"); body(206, data) }
                "/short" -> { ex.sendResponseHeaders(200, data.size.toLong()); ex.responseBody.use { it.write(data, 0, 65536) } }
                "/oversize" -> body(200, data + byteArrayOf(1), true)
                "/wrong-size" -> body(200, byteArrayOf(1, 2, 3))
                "/gzip" -> { header("Content-Encoding", "gzip"); body(200, data) }
                "/forbidden" -> ex.sendResponseHeaders(403, -1)
                "/slow" -> {
                    ex.sendResponseHeaders(200, data.size.toLong())
                    ex.responseBody.use { out ->
                        for (i in data.indices step 65536) { out.write(data, i, 65536); out.flush(); Thread.sleep(200) }
                    }
                }
                else -> body(200, data)
            }
        } catch (_: IOException) { } finally { ex.close() }
    }
    server.start()
    val dir = Files.createTempDirectory("pickup-transfer-").toFile()
    val part = java.io.File(dir, "test.part")
    fun transfer() = UpdateTransfer { url -> URL("http://127.0.0.1:${server.address.port}${url.path}").openConnection() as HttpURLConnection }
    fun download(path: String, downloader: UpdateTransfer = transfer(), progress: (Long) -> Unit = {}) =
        downloader.download(r, "https://fixture.test$path", part, progress)
    fun valid(path: String) { download(path); UpdateProtocol.verifyBytes(part, r); checks++ }
    fun reject(path: String, downloader: UpdateTransfer = transfer()) {
        try { download(path, downloader); error("Accepted invalid download: $path") } catch (_: IOException) { checks++ }
    }
    try {
        valid("/full")
        check(requests.last().second == null); checks++
        part.writeBytes(data.copyOfRange(0, 65536)); valid("/range")
        check(requests.last().second == "bytes=65536-"); checks++
        part.writeBytes(data.copyOfRange(0, 65536)); valid("/full") // Range ignored, HTTP 200 replacement.
        check(part.length() == data.size.toLong()); checks++
        part.delete(); valid("/redirect")
        part.delete(); reject("/downgrade")
        part.delete(); reject("/loop")
        part.writeBytes(data.copyOfRange(0, 65536)); reject("/bad-range")
        check(part.length() == 65536L); checks++
        part.delete(); reject("/missing-range")
        part.delete(); reject("/wrong-total")
        part.delete(); reject("/wrong-size")
        part.delete(); reject("/gzip")
        part.delete(); reject("/forbidden")
        part.delete(); reject("/short")
        check(part.length() == 65536L); checks++
        valid("/range") // Retry a real connection interruption without losing bytes.
        part.delete(); reject("/oversize")
        check(part.length() <= r.size); checks++
        part.delete()
        val cancelled = transfer(); cancelled.cancel(); val before = requests.size
        reject("/full", cancelled); check(requests.size == before); checks++
        part.delete()
        val active = transfer()
        try { download("/slow", active) { if (it >= 65536) active.cancel() }; error("Cancellation completed a download") }
        catch (_: IOException) { check(part.length() in 65536 until data.size.toLong()); checks++ }
        part.writeBytes(data + byteArrayOf(1)); valid("/full") // Oversized old partial is discarded.
        part.writeBytes(data); val completeBefore = requests.size; valid("/full")
        check(requests.size == completeBefore); checks++
        println("PASS: $checks HTTPS download, resume and cancellation checks")
    } finally { server.stop(0); executor.shutdownNow(); part.delete(); dir.delete() }
}
