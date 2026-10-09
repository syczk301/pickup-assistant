package com.local.pickup

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object Store {
    data class Parcel(
        var id: String = "",
        var code: String = "",
        var carrier: String = "其他",
        var station: String = "",
        var note: String = "",
        var source: String = "",
        var created: Long = 0,
        var completed: Long = 0,
    ) {
        fun json(): JSONObject =
            JSONObject()
                .put("id", id)
                .put("code", code)
                .put("carrier", carrier)
                .put("station", station)
                .put("note", note)
                .put("source", source)
                .put("created", created)
                .put("completed", completed)

        companion object {
            fun from(j: JSONObject): Parcel {
                val p =
                    Parcel(
                        j.getString("id"),
                        j.getString("code"),
                        j.optString("carrier", "其他"),
                        j.optString("station"),
                        j.optString("note"),
                        j.optString("source", "导入"),
                        j.getLong("created"),
                        j.optLong("completed"),
                    )
                if (
                    !SmsParser.valid(p.code) ||
                        p.id.length > 100 ||
                        p.created < 0 ||
                        p.completed < 0
                )
                    throw JSONException("无效记录")
                return p
            }
        }
    }

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("pickup", Context.MODE_PRIVATE)

    @Synchronized
    fun load(c: Context): MutableList<Parcel> {
        try {
            val j = JSONArray(prefs(c).getString("parcels", "[]"))
            return MutableList(j.length()) { Parcel.from(j.getJSONObject(it)) }
        } catch (e: JSONException) {
            throw IllegalStateException("本地取件记录损坏，请保留数据后恢复备份", e)
        }
    }

    @Synchronized
    fun save(c: Context, parcels: List<Parcel>) {
        val json = JSONArray().apply { parcels.forEach { put(it.json()) } }
        check(prefs(c).edit().putString("parcels", json.toString()).commit()) { "保存失败，存储空间可能不足" }
        PickupWidget.refresh(c)
    }

    fun make(code: String, carrier: String, station: String, note: String, source: String) =
        Parcel(
            UUID.randomUUID().toString(),
            code,
            carrier,
            station,
            note,
            source,
            System.currentTimeMillis(),
        )

    /** Validate the whole reviewed batch before writing; skip pending duplicates. */
    @Synchronized
    fun addReviewed(c: Context, candidates: List<Parcel>): Int {
        require(candidates.all { SmsParser.valid(it.code) }) { "请核对所有取件码后再保存" }
        val parcels = load(c)
        var added = 0
        for (candidate in candidates) {
            if (parcels.none { it.completed == 0L && it.code.equals(candidate.code, true) && it.carrier == candidate.carrier }) {
                parcels.add(candidate)
                added++
            }
        }
        if (added > 0) save(c, parcels)
        return added
    }

    @Synchronized
    fun ingest(c: Context, text: String, time: Long): Int {
        val parcels = load(c)
        var added = 0
        for (r in SmsParser.parse(text)) {
            val duplicate =
                parcels.any {
                    it.code.equals(r.code, true) &&
                        it.carrier == r.carrier &&
                        (it.completed == 0L || kotlin.math.abs(it.created - time) < 86400000L)
                }
            if (!duplicate) {
                parcels.add(
                    make(r.code, r.carrier, r.station, text, "短信识别").apply { created = time }
                )
                added++
            }
        }
        if (added > 0) save(c, parcels)
        return added
    }

    fun pending(c: Context) = load(c).count { it.completed == 0L }
}
