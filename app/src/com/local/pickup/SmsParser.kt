package com.local.pickup

object SmsParser {
    @JvmField val CARRIERS = arrayOf("菜鸟驿站", "顺丰速运", "中通快递", "圆通速递", "申通快递", "韵达快递", "极兔速递", "京东物流", "邮政EMS", "德邦快递", "丰巢", "其他")
    private val aliases = arrayOf("菜鸟", "顺丰", "中通", "圆通", "申通", "韵达", "极兔", "京东", "邮政", "德邦", "丰巢")
    data class Result(@JvmField val code: String, @JvmField val carrier: String, @JvmField val station: String)

    @JvmStatic fun parse(input: String?): List<Result> {
        if (input == null) return emptyList()
        val s = input.replace('：', ':').replace('－', '-').replace('—', '-')
        if (!Regex("取件|取货|提货|开柜|快递|包裹|驿站|自提|货物|丰巢").containsMatchIn(s)) return emptyList()
        if (Regex("登录|登陆|注册|支付|银行|转账").containsMatchIn(s) && !Regex("取件码|取货码|提货码|开柜码").containsMatchIn(s)) return emptyList()
        val signature = Regex("【([^】]+)】|\\[([^\\]]+)\\]").find(s)
        val brand = signature?.let { it.groups[1]?.value ?: it.groups[2]?.value } ?: s
        var index = aliases.indexOfFirst { brand.contains(it) }
        if (index < 0) index = (1 until aliases.size).firstOrNull { s.contains(aliases[it]) } ?: -1
        if (index < 0 && s.contains("菜鸟")) index = 0
        val carrier = if (index < 0) "其他" else CARRIERS[index]
        val station = Regex("(?:取件地址|取货地址|暂存地址|地址|地点|送达|送至|投递至|已到达|到达|已到|请前往|请到)[:\\s]*(.{2,45}?)(?=[，。；!！\\n]|请凭|凭|取件码|$)").find(s)?.groupValues?.get(1)?.trim().orEmpty()
        val codes = linkedSetOf<String>()
        val explicit = Regex("(?:取件码|提取码|提货码|提货号|取货码|开柜码|验证码|密码|凭码|凭号)(?:为|是)?[:\\s]*([A-Za-z0-9][A-Za-z0-9-]{2,11}(?![A-Za-z0-9-])(?:[,，、\\s]+[A-Za-z0-9][A-Za-z0-9-]{2,11}(?![A-Za-z0-9-]))*)")
        explicit.findAll(s).forEach { match -> match.groupValues[1].split(Regex("[,，、\\s]+")).filter(::valid).forEach(codes::add) }
        if (codes.isEmpty()) Regex("(?:请凭|请报|凭|报|输入)\\s*([A-Za-z0-9][A-Za-z0-9-]{2,11})\\s*(?:取件|领取|来取|到|至|前往)").findAll(s).map { it.groupValues[1] }.filter(::valid).forEach(codes::add)
        return codes.map { Result(it, carrier, station) }
    }

    @JvmStatic fun valid(code: String?): Boolean = code != null && code.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{2,11}")) && !code.matches(Regex("1[3-9]\\d{9}"))
}
