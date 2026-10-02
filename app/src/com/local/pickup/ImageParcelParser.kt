package com.local.pickup

import java.text.Normalizer

/** Structured screenshots differ from SMS: labels may be on their own lines. */
object ImageParcelParser {
    data class Scan(val text: String, val parcels: List<SmsParser.Result>, val carrier: String, val station: String)
    private val label = Regex("(?:取件码|取货码|提货码|提取码|开柜码)(?:为|是)?[:\\s]*")
    private val field = Regex("(?:取件码|取货码|提货码|快递公司|承运商|物流单号|联系电话|手机|电话|驿站地址|取件地址|取货地址|站点地址|自提地址|驿站名称|站点名称|自提点名称|取件站点|取件地点|地址|营业时间)")

    fun parse(raw: String): Scan {
        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(Regex("[‐‑–—−－]"), "-")
            .replace(Regex("(?<=[\\p{IsHan}])[ \\t]+(?=[\\p{IsHan}])"), "")
            .replace(Regex("(?<=[A-Za-z0-9])[ \\t]*-[ \\t]*(?=[A-Za-z0-9])"), "-").trim()
        val carrier = carrier(text)
        val station = station(text)
        val blocks = text.split(Regex("\\n[ \\t]*\\n+"))
        val allCodes = codes(text)
        val parcels = allCodes.map { code ->
            val block = blocks.singleOrNull { codes(it).contains(code) }
            val independent = block != null && codes(block).size == 1 && (carrier(block) != "其他" || station(block).isNotEmpty())
            // Do not attach one card's company/address to another card without a clear boundary.
            SmsParser.Result(code,
                if (independent) carrier(block!!) else if (allCodes.size == 1) carrier else "其他",
                if (independent) station(block!!) else if (allCodes.size == 1) station else "")
        }
        return Scan(text, parcels, carrier, station)
    }

    private fun codes(text: String): List<String> {
        val found = linkedSetOf<String>()
        label.findAll(text).forEach { match ->
            val tail = text.substring(match.range.last + 1).lineSequence().firstOrNull().orEmpty()
            val candidate = tail.trim().substringBefore('，').substringBefore(',').substringBefore('。')
                .replace(Regex("[ \\t]+"), "")
            Regex("^[A-Za-z0-9][A-Za-z0-9-]{2,11}(?=$|[\\p{IsHan}(])").find(candidate)?.value
                ?.takeIf(SmsParser::valid)?.let(found::add)
        }
        if (!label.containsMatchIn(text)) {
            text.lineSequence().map { it.trim() }.filter {
                it.matches(Regex("[A-Za-z0-9]{1,3}-[A-Za-z0-9]{1,3}-[A-Za-z0-9]{3,6}"))
            }.filter(SmsParser::valid).forEach(found::add)
        }
        // Explicit SMS patterns embedded in a screenshot remain supported; no naked number guessing.
        if (found.isEmpty() && !label.containsMatchIn(text)) SmsParser.parse(text).forEach { found.add(it.code) }
        return found.toList()
    }

    private fun carrier(text: String): String {
        val explicit = Regex("(?:快递公司|承运公司|物流公司|承运商|快递品牌)[:\\s]*([^\\n，。]{2,16})")
            .find(text)?.groupValues?.get(1).orEmpty()
        fun match(value: String): String? = SmsParser.CARRIERS.dropLast(1).firstOrNull {
            val alias = when (it) { "邮政EMS" -> "邮政|EMS"; else -> it.take(2) }
            Regex(alias, RegexOption.IGNORE_CASE).containsMatchIn(value)
        }
        match(explicit)?.let { return it }
        // Courier wins over a pickup platform such as 菜鸟/丰巢.
        val courier = SmsParser.CARRIERS.slice(1..9).firstOrNull {
            Regex(if (it == "邮政EMS") "邮政|EMS" else it.take(2), RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
        return courier ?: match(text) ?: "其他"
    }

    private fun station(text: String): String {
        fun value(pattern: String): String {
            val match = Regex(pattern + "[:\\s]*").find(text) ?: return ""
            val tail = text.substring(match.range.last + 1)
            return tail.lineSequence().take(2).map { line ->
                line.take(field.find(line)?.range?.first ?: line.length)
            }.takeWhile { it.isNotBlank() }
                .joinToString(" ").substringBefore('。').substringBefore('；').substringBefore(';').trim().take(100)
        }
        val inferred = text.lineSequence().map { it.trim() }.firstOrNull {
            it.length in 3..60 && !field.containsMatchIn(it) &&
                Regex("(?:驿站|自提点|代收点|店)$").containsMatchIn(it)
        }.orEmpty()
        val name = value("(?:驿站名称|站点名称|自提点名称|取件站点)")
        val address = value("(?:驿站地址|取件地址|取货地址|站点地址|自提地址|地址|取件地点)")
        return if (name.isNotBlank() && address.isNotBlank() && !address.contains(name)) "$name · $address"
            else address.ifBlank { name.ifBlank { inferred.ifBlank { SmsParser.parse(text).firstOrNull()?.station.orEmpty() } } }
    }
}
