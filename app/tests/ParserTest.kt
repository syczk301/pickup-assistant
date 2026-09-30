import com.local.pickup.SmsParser

fun main() {
    var count = 0
    fun expect(input: String, vararg expected: String) {
        val codes = SmsParser.parse(input).map { it.code }
        check(codes == expected.toList()) { "$input -> $codes, expected ${expected.toList()}" }
        count++
    }

    expect("【菜鸟驿站】包裹已到达南门驿站，取件码：8-2066，请及时取件。", "8-2066")
    expect("【京东物流】您的包裹已投递至JD代收点，取件码：JD1357。", "JD1357")
    expect("【圆通速递】包裹已到达代收点，验证码：9876。", "9876")
    expect("【丰巢】您的快递已投递到快递柜，密码：8642。", "8642")
    expect("【极兔快递】包裹已送达驿站，取件码JT5432，48小时内取件。", "JT5432")
    expect("【申通快递】货物已到自提点，凭码ST2468取件。", "ST2468")
    expect("请凭取件码 A8B9 至柜台取件，24 小时内有效。", "A8B9")
    expect("快递包裹已到，取件码为12-33、12-34，取件码12-33。", "12-33", "12-34")
    expect("包裹到了，请报AB1234取件。", "AB1234")
    expect("【银行】登录验证码123456，请勿泄露。")
    expect("【顺丰快递】登录验证码：123456，请勿泄露。")
    expect("快递客服电话13812345678，验证码：13812345678。")
    expect("快递到达，取件码：12。")
    expect("快递到达，取件码：ABCDEFGHIJKLMNOP。")
    check(SmsParser.parse("【顺丰速运】包裹已到达南门驿站，取件码AB123。").first().station == "南门驿站")
    count++
    check(SmsParser.parse("【顺丰速运】取件码AB123。").first().carrier == "顺丰速运")
    count++
    check(SmsParser.parse("【圆通速递】包裹到达菜鸟驿站，取件码AB123。").first().carrier == "圆通速递")
    count++
    println("PASS: $count parser cases")
}
