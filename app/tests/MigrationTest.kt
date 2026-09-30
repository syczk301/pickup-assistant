import com.local.pickup.SmsParser
import com.local.pickup.Store
import org.json.JSONObject

fun main() {
    val legacy =
        JSONObject(
            """{"id":"legacy-id","code":"8-2066","carrier":"菜鸟驿站","station":"南门驿站","note":"升级保留","source":"短信识别","created":1790760000000,"completed":1790763600000}"""
        )
    val migrated = Store.Parcel.from(legacy)
    check(migrated.code == "8-2066" && migrated.completed == 1790763600000L)
    check(migrated.json().keySet() == legacy.keySet())
    legacy.keySet().forEach { check(migrated.json().get(it) == legacy.get(it)) }
    val minimal = Store.Parcel.from(JSONObject("""{"id":"old","code":"AB123","created":1}"""))
    check(minimal.completed == 0L && minimal.carrier == "其他" && minimal.source == "导入")
    check(Store.Parcel.from(migrated.json()) == migrated)
    check(
        runCatching { Store.Parcel.from(JSONObject("""{"id":"bad","code":"12","created":1}""")) }
            .isFailure
    )
    check(
        runCatching {
                Store.Parcel.from(JSONObject("""{"id":"bad","code":"AB123","created":-1}"""))
            }
            .isFailure
    )
    check(SmsParser.parse(null).isEmpty())
    println("PASS: legacy JSON fields, defaults, round trip and invalid-record rejection")
}
