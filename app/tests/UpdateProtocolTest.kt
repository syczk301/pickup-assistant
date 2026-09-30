import com.local.pickup.UpdateProtocol
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject

fun main() {
    var count = 0
    fun valid() = JSONObject()
        .put("schemaVersion", 1)
        .put("packageName", "com.local.pickup")
        .put("versionCode", 3)
        .put("versionName", "0.2.1")
        .put("apkUrl", "https://example.com/app.apk")
        .put("sizeBytes", 3)
        .put("sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        .put("minSdk", 26)

    fun rejected(action: () -> Unit) {
        try {
            action()
            error("Accepted invalid metadata or APK")
        } catch (_: IOException) {
            count++
        }
    }

    val release = UpdateProtocol.parse(valid().toString(), "com.local.pickup")
    check(release.code == 3 && release.name == "0.2.1")
    count++
    val invalid = listOf(
        "schemaVersion" to 2, "packageName" to "other", "versionCode" to 0,
        "versionCode" to 2147483648L, "sizeBytes" to 0,
        "sizeBytes" to UpdateProtocol.MAX_APK + 1, "sha256" to "bad",
        "apkUrl" to "http://example.com/app.apk",
        "apkUrl" to "https://user:password@example.com/app.apk",
        "versionName" to "", "minSdk" to 25,
    )
    invalid.forEach { (key, value) ->
        rejected { UpdateProtocol.parse(valid().put(key, value).toString(), "com.local.pickup") }
    }
    val file = Files.createTempFile("update-test-", ".apk").toFile()
    try {
        file.writeBytes(byteArrayOf(97, 98, 99))
        UpdateProtocol.verifyBytes(file, release)
        count++
        file.writeBytes(byteArrayOf(97, 98, 100))
        rejected { UpdateProtocol.verifyBytes(file, release) }
        file.writeBytes(byteArrayOf(97))
        rejected { UpdateProtocol.verifyBytes(file, release) }
    } finally {
        file.delete()
    }
    println("PASS: $count update protocol checks")
}
