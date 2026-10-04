import com.local.pickup.ImageParcelParser
import com.local.pickup.ImageParcelText

fun main() {
    var checks = 0
    fun expect(value: Boolean, message: String) { check(value) { message }; checks++ }
    val raw = "十\n\nwa 已放入代收点 ED @\n\nif\n\nanak\n\nSaye\n\n收货地址\n\n岛号码保护\n\n订单共2个包裹 (ae) (ae)\n\n取件码7-2-9408\n\n复制 || 分享取件 |\n\n南城大学生活动中心 | 大学快递服务点\n\n拨打电话\n\n您有2个快递待取\n\n查看全部 >\n\n中通快递:12345678901234"
    val preview = ImageParcelText.preview(raw)
    expect(preview == "取件码：7-2-9408\n快递公司：中通快递\n驿站名称：南城大学生活动中心 | 大学快递服务点", "clean result contains only useful fields")
    expect(ImageParcelParser.parse(preview).parcels == ImageParcelParser.parse(raw).parcels, "clean result preserves parsed fields")
    val packed = ImageParcelText.join("周末取", raw)
    expect(ImageParcelText.split("图片识别", packed) == ImageParcelText.Parts("周末取", raw), "manual note and exact OCR evidence round trip")
    expect(ImageParcelText.split("图片识别", raw) == ImageParcelText.Parts("", raw), "legacy batch raw note")
    expect(ImageParcelText.split("图片识别", "图片识别原文：\n$raw").original == raw, "legacy single raw note")
    expect(ImageParcelText.split("手动添加", packed) == ImageParcelText.Parts(packed, ""), "marker in non-image note is user content")
    val edited = ImageParcelText.join("已改备注", ImageParcelText.split("图片识别", packed).original)
    expect(ImageParcelText.split("图片识别", edited) == ImageParcelText.Parts("已改备注", raw), "edit preserves original evidence")
    expect(ImageParcelText.split("图片识别", ImageParcelText.join("", "  raw\n")).original == "  raw\n", "raw whitespace preserved")
    val multiple = "中通快递\n取件码：A7B29\n地址：北门驿站\n\n圆通速递\n取件码：4-4-4216\n地址：南门驿站"
    expect(ImageParcelParser.parse(ImageParcelText.preview(multiple)).parcels == ImageParcelParser.parse(multiple).parcels, "multiple cards retain codes and independent metadata")
    val ambiguous = "中通快递\n取件码：111111\n取件码：222222\n地址：北门驿站"
    expect(ImageParcelParser.parse(ImageParcelText.preview(ambiguous)).parcels == ImageParcelParser.parse(ambiguous).parcels, "ambiguous metadata stays unassigned")
    expect(ImageParcelText.preview("取件码：A7B29\n快递公司：邮政EMS").contains("A7B29"), "ASCII pickup code preserved")
    expect(ImageParcelText.preview("取件码：13812345678").contains("未识别到"), "phone does not become a pickup code")
    expect(ImageParcelText.preview("wa if anak Saye").contains("未识别到"), "noise does not invent result")
    val partial = ImageParcelText.preview("快递公司：京东物流\n取件地点：南门驿站")
    expect(!partial.contains("取件码") && partial.contains("京东物流") && partial.contains("南门驿站"), "partial fields survive without invented code")
    expect(ImageParcelText.split("图片识别", "").original.isEmpty(), "empty legacy image source")
    expect(ImageParcelText.split("图片识别", ImageParcelText.join("手动核对", "")) == ImageParcelText.Parts("手动核对", ""), "note without OCR text stays editable")
    println("PASS: $checks image result, note and original-evidence checks")
}
