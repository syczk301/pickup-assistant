package com.local.pickup

/** Options apply to automatic fields, never to manually edited or imported records. */
data class ExtractionOptions(val carrier: Boolean = true, val station: Boolean = false) {
    fun apply(result: SmsParser.Result) = SmsParser.Result(
        result.code, if (carrier) result.carrier else "其他", if (station) result.station else "")

    companion object {
        fun widgetMetadata(carrier: String, station: String, showStation: Boolean): String =
            listOf(carrier.takeIf { it.isNotBlank() && it != "其他" }.orEmpty(),
                station.takeIf { showStation }.orEmpty()).filter { it.isNotBlank() }.distinct()
                .joinToString(" · ").ifBlank { "待取包裹" }
    }
}
