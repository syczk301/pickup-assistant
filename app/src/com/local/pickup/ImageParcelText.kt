package com.local.pickup

/** Keep the OCR evidence separate from the user's note and the editable result. */
object ImageParcelText {
    private const val marker = "图片识别原文：\n"
    data class Parts(val note: String, val original: String)

    fun split(source: String, note: String): Parts {
        if (source != "图片识别") return Parts(note, "")
        val at = note.indexOf(marker)
        // Older batch imports stored the whole OCR result directly in the note.
        return if (at < 0) Parts("", note)
            else Parts(note.substring(0, at).trimEnd(), note.substring(at + marker.length))
    }

    fun join(note: String, original: String): String =
        listOf(note.trim(), marker + original)
            .filter { it.isNotBlank() }.joinToString("\n\n")

    fun preview(raw: String): String {
        val scan = ImageParcelParser.parse(raw)
        fun fields(code: String, carrier: String, station: String) = listOf(
            if (code.isNotEmpty()) "取件码：$code" else "",
            if (carrier != "其他") "快递公司：$carrier" else "",
            if (station.isNotEmpty()) "驿站名称：$station" else "",
        ).filter { it.isNotEmpty() }.joinToString("\n")
        return if (scan.parcels.isNotEmpty()) scan.parcels.joinToString("\n\n") {
            fields(it.code, it.carrier, it.station)
        } else fields("", scan.carrier, scan.station).ifEmpty { "未识别到取件信息，请手动填写" }
    }
}
