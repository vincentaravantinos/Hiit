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
        fun decode(encoded: String): JSONObject {
            val json = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            val o = JSONObject(json)
            require(o.optInt("v") == 1) { "Version de format inconnue" }
            return o
        }

        /** Configuration de synchro envoyée par la PWA (clé de chiffrement + token GitHub). */
        fun cfgOf(o: JSONObject): SyncConfig? = o.optJSONObject("cfg")?.let {
            val key = it.optString("key"); val token = it.optString("token")
            if (key.isEmpty() || token.isEmpty()) null
            else SyncConfig(key, token, it.optString("repo", "vincentaravantinos/Hiit"), it.optString("path", "data/health/health.json"))
        }

        /** null pour un simple réglage (kind = "setup"), sans séance à écrire. */
        fun sessionOf(o: JSONObject): SessionPayload? {
            if (o.optString("kind") == "setup") return null
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

data class SyncConfig(val keyB64: String, val token: String, val repo: String, val path: String)
