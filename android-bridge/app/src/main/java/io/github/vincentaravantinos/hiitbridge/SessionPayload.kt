package io.github.vincentaravantinos.hiitbridge

import android.util.Base64
import org.json.JSONObject

/** Format v1 envoyé par la PWA (voir buildHcPayload dans index.html). Temps en ms epoch. */
data class SegmentIn(val start: Long, val end: Long, val type: String, val reps: Int?)

data class SessionPayload(
    val kind: String,          // "hiit" | "swim"
    val id: String,            // identifiant stable (ms de début) -> upsert, jamais de doublon
    val start: Long,
    val end: Long,
    val title: String,
    val notes: String,
    val segments: List<SegmentIn>,
    val hrT0: Long?,
    val hrDtMs: Double,
    val hrBpm: List<Int>,
    val activeKcal: Double?,
) {
    companion object {
        fun parse(encoded: String): SessionPayload {
            val json = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            val o = JSONObject(json)
            require(o.optInt("v") == 1) { "Version de format inconnue" }
            val segs = mutableListOf<SegmentIn>()
            val sa = o.optJSONArray("segments")
            if (sa != null) for (i in 0 until sa.length()) {
                val s = sa.getJSONObject(i)
                segs += SegmentIn(
                    s.getLong("s"), s.getLong("e"), s.getString("type"),
                    if (s.isNull("reps") || !s.has("reps")) null else s.getInt("reps")
                )
            }
            val hr = o.optJSONObject("hr")
            val bpm = mutableListOf<Int>()
            hr?.optJSONArray("bpm")?.let { a -> for (i in 0 until a.length()) bpm += a.optInt(i, 0) }
            return SessionPayload(
                kind = o.getString("kind"),
                id = o.getString("id"),
                start = o.getLong("start"),
                end = o.getLong("end"),
                title = o.optString("title", "HIIT"),
                notes = o.optString("notes", ""),
                segments = segs,
                hrT0 = hr?.getLong("t0"),
                hrDtMs = hr?.optDouble("dtMs", 1000.0) ?: 1000.0,
                hrBpm = bpm,
                activeKcal = if (o.has("kcal") && !o.isNull("kcal")) o.getDouble("kcal") else null,
            )
        }
    }
}
