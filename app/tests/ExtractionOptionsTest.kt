import com.local.pickup.ExtractionOptions
import com.local.pickup.SmsParser
import com.local.pickup.ImageParcelParser

fun main() {
    val sms = SmsParser.parse("【中通快递】包裹已到达南门驿站，取件码：8-2066。").single()
    val image = ImageParcelParser.parse("取件码：8-2066\n快递公司：中通快递\n驿站名称：南门驿站\n取件地址：大学路123号").parcels.single()
    for (result in listOf(sms, image)) {
        check(result.station.isNotBlank())
        for (carrier in listOf(false, true)) for (station in listOf(false, true)) {
            val filtered = ExtractionOptions(carrier, station).apply(result)
            check(filtered.code == result.code)
            check(filtered.carrier == if (carrier) result.carrier else "其他")
            check(filtered.station == if (station) result.station else "")
        }
        check(ExtractionOptions().apply(result).station.isEmpty())
    }
    val longAddress = "南门驿站".repeat(30)
    check(ExtractionOptions.widgetMetadata("中通快递", longAddress, false) == "中通快递")
    check(ExtractionOptions.widgetMetadata("中通快递", longAddress, true) == "中通快递 · $longAddress")
    check(ExtractionOptions.widgetMetadata("其他", longAddress, false) == "待取包裹")
    check(ExtractionOptions.widgetMetadata("其他", longAddress, true) == longAddress)
    check(ExtractionOptions.widgetMetadata("中通快递", "中通快递", true) == "中通快递")
    println("Extraction options: SMS/image field combinations and widget metadata passed")
}
