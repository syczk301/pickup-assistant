package com.local.pickup

import java.text.Normalizer

/** Structured screenshots differ from SMS: labels may be on their own lines. */
object ImageParcelParser {
    data class Scan(val text: String, val parcels: List<SmsParser.Result>, val carrier: String, val station: String, val confidence: Int = 0)
    private val label = Regex("(?:取件码|取货码|提货码|提取码|开柜码)(?:为|是)?[:\\s]*")
    private val field = Regex("(?:取件码|取货码|提货码|快递公司|承运商|物流单号|订单编号|联系电话|手机|电话|驿站地址|取件地址|取货地址|站点地址|自提地址|驿站名称|站点名称|自提点名称|取件站点|取件地点|收货地址|寄件地址|地址|营业时间)")
    private val recipient = Regex("(?:收货|收件人?|收方|寄件人?|寄方|发货|配送|送货|退货|退件|账单)地址")
    private val controls = Regex("(?:拨打电话|联系电话|电话|复制|分享取件|分享|导航|查看全部|号码保护|支持退换货|订阅提醒)")
    private val stationEnding = Regex("(?:驿站|自提点|代收点|(?:快递|物流|快件)服务(?:点|站|中心)|店)$")
    private data class Evidence(val value: String, val strength: Int)

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
            val independent = block != null && codes(block).size == 1 && (carrier(block).value != "其他" || station(block).value.isNotEmpty())
            // Do not attach one card's company/address to another card without a clear boundary.
            SmsParser.Result(code,
                if (allCodes.size == 1) carrier.value else if (independent) carrier(block!!).value else "其他",
                if (allCodes.size == 1) station.value else if (independent) station(block!!).value else "")
        }
        return Scan(text, parcels, carrier.value, station.value, carrier.strength + station.strength)
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

    private fun carrier(text: String): Evidence {
        fun matches(value: String) = SmsParser.CARRIERS.dropLast(1).filter {
            val alias = when (it) { "邮政EMS" -> "邮政|EMS"; else -> it.take(2) }
            Regex(alias, RegexOption.IGNORE_CASE).containsMatchIn(value)
        }
        val evidence = mutableListOf<Evidence>()
        Regex("(?:快递公司|承运公司|物流公司|承运商|快递品牌)[:\\s]*([^\\n，。]{2,24})")
            .findAll(text).forEach { explicit ->
                matches(explicit.groupValues[1]).singleOrNull()?.let { evidence.add(Evidence(it, 40)) }
            }
        for (line in text.lineSequence()) {
            val found = matches(line)
            for (name in found) {
                val alias = if (name == "邮政EMS") "(?:邮政(?:EMS)?|EMS)" else name.take(2)
                val tracking = Regex("$alias(?:快递|速递|速运|物流)?\\s*:?\\s*[A-Za-z]*[0-9]{10,}", RegexOption.IGNORE_CASE)
                val full = Regex("$alias(?:快递|速递|速运|物流)", RegexOption.IGNORE_CASE)
                val platform = name == "菜鸟驿站" || name == "丰巢"
                evidence.add(Evidence(name, when { tracking.containsMatchIn(line) -> 30; platform -> 2; full.containsMatchIn(line) -> 20; else -> 10 }))
            }
        }
        val strength = evidence.maxOfOrNull { it.strength } ?: return Evidence("其他", 0)
        val best = evidence.filter { it.strength == strength }.map { it.value }.distinct()
        // Conflicting equally strong names need review; never choose by carrier-list order.
        return best.singleOrNull()?.let { Evidence(it, strength) } ?: Evidence("其他", 0)
    }

    private fun station(text: String): Evidence {
        fun clean(value: String): String = value.take(controls.find(value)?.range?.first ?: value.length)
            .substringBefore('。').substringBefore('；').substringBefore(';').substringBefore('，').substringBefore(',')
            .trim(' ', '\t', '"', '“', '”', '|', '丨', '[', ']', '【', '】', '(', ')', '-', ':').take(100)
        fun usable(value: String) = value.length >= 2 && value.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } &&
            !recipient.containsMatchIn(value) && !controls.containsMatchIn(value) && !field.containsMatchIn(value)
        fun value(pattern: String): String {
            for (match in Regex(pattern + "[:\\s]*").findAll(text)) {
                // A generic 地址 label inside 收货地址 / 寄件地址 belongs to the recipient.
                if (recipient.findAll(text).any { match.range.first in it.range }) continue
                val line = text.substring(match.range.last + 1).lineSequence().firstOrNull().orEmpty()
                val candidate = clean(line.take(field.find(line)?.range?.first ?: line.length))
                if (usable(candidate)) return candidate
            }
            return ""
        }
        val name = value("(?:驿站名称|站点名称|自提点名称|取件站点)")
        val address = value("(?:驿站地址|取件地址|取货地址|站点地址|自提地址|取件地点)")
        if (name.isNotBlank() || address.isNotBlank()) return Evidence(
            if (name.isNotBlank() && address.isNotBlank() && !address.contains(name)) "$name · $address" else address.ifBlank { name }, 30)
        val inferred = text.lineSequence().filterNot { recipient.containsMatchIn(it) }.map(::clean).firstOrNull { line ->
            line.length in 3..100 && usable(line) && line.split(Regex("[|｜丨]")).any { stationEnding.containsMatchIn(it.trim()) } &&
                !Regex("送达|送至|到达|取件码|请凭|请到").containsMatchIn(line)
        }.orEmpty()
        if (inferred.isNotEmpty()) return Evidence(inferred, 20)
        val delivered = Regex("(?:【|\\[|已送达|送至)\\s*代收点\\s*[-:]?\\s*([^\\n，。；;】\\]]{2,60})")
            .findAll(text).map { clean(it.groupValues[1]) }.firstOrNull(::usable).orEmpty()
        if (delivered.isNotEmpty()) return Evidence(delivered, 20)
        val generic = value("(?:地址|地点)")
        if (generic.isNotEmpty()) return Evidence(generic, 10)
        val safeText = text.lineSequence().filterNot { recipient.containsMatchIn(it) }.joinToString("\n")
        val sms = SmsParser.parse(safeText).firstOrNull()?.station?.let(::clean).orEmpty()
        return Evidence(sms.takeIf(::usable).orEmpty(), if (usable(sms)) 5 else 0)
    }
}
