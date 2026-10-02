package io.github.vincentaravantinos.hiitbridge

import android.content.Context
import android.util.Base64
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Lit sommeil / HRV / FC de repos / FC nocturne / poids dans Health Connect, agrège par nuit
 * (date = jour du réveil), chiffre (gzip + AES-256-GCM, même format que la PWA) et dépose le
 * résultat dans le repo GitHub. La PWA le relit et le déchiffre avec sa clé.
 */
object HealthSync {
    private const val PREFS = "hiit-bridge"
    private const val KEEP_DAYS = 60L

    fun saveConfig(ctx: Context, c: SyncConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", c.keyB64).putString("token", c.token)
            .putString("repo", c.repo).putString("path", c.path).apply()
    }

    fun config(ctx: Context): SyncConfig? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = p.getString("key", null) ?: return null
        val token = p.getString("token", null) ?: return null
        return SyncConfig(key, token, p.getString("repo", "vincentaravantinos/Hiit")!!, p.getString("path", "data/health/health.json")!!)
    }

    /** La nuit du jour (date de réveil = aujourd'hui) est-elle déjà captée ? */
    fun hasTodaySleep(ctx: Context): Boolean {
        val cache = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("cache", null) ?: return false
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        return JSONObject(cache).optJSONObject(today)?.has("sleep") == true
    }

    /** Renvoie le nombre de nuits présentes dans le fichier publié. */
    suspend fun run(ctx: Context): Int = withContext(Dispatchers.IO) {
        val cfg = config(ctx) ?: throw IllegalStateException("Pas encore configuré par l'app HIIT")
        val client = HealthConnectClient.getOrCreate(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val cache = prefs.getString("cache", null)?.let { JSONObject(it) } ?: JSONObject()
        // Première fois : 30 jours (limite d'historique Health Connect) ; ensuite les 4 derniers.
        val back = if (cache.length() == 0) 30L else 4L
        val from = today.minusDays(back).atStartOfDay(zone).minusHours(12).toInstant()
        val to = Instant.now()

        val days = mutableMapOf<LocalDate, JSONObject>()
        fun day(d: LocalDate) = days.getOrPut(d) { JSONObject() }

        // Sommeil : nuit principale = la plus longue session qui se termine ce jour-là.
        val sleepsByDay = readAll(client, SleepSessionRecord::class, from, to)
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
        val sleeps = sleepsByDay.mapValues { (_, l) -> l.maxBy { Duration.between(it.startTime, it.endTime) } }
        // Siestes = les autres sessions de sommeil du jour (la nuit reste la plus longue).
        for ((d, l) in sleepsByDay) {
            val main = sleeps[d]
            val nap = l.filter { it !== main }.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
            if (nap >= 10) day(d).put("napMin", nap)
        }
        val hrvs = readAll(client, HeartRateVariabilityRmssdRecord::class, from, to)
        for ((d, s) in sleeps) {
            val o = day(d)
            var deep = 0L; var rem = 0L; var light = 0L; var awake = 0L; var asleep = 0L
            for (st in s.stages) {
                val m = Duration.between(st.startTime, st.endTime).toMinutes()
                when (st.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> { deep += m; asleep += m }
                    SleepSessionRecord.STAGE_TYPE_REM -> { rem += m; asleep += m }
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> { light += m; asleep += m }
                    SleepSessionRecord.STAGE_TYPE_SLEEPING -> asleep += m
                    else -> awake += m
                }
            }
            val total = if (asleep > 0) asleep else Duration.between(s.startTime, s.endTime).toMinutes()
            o.put("sleep", JSONObject()
                .put("start", s.startTime.toEpochMilli()).put("end", s.endTime.toEpochMilli())
                .put("totalMin", total).put("deepMin", deep).put("remMin", rem)
                .put("lightMin", light).put("awakeMin", awake))

            val inNight = hrvs.filter { !it.time.isBefore(s.startTime) && !it.time.isAfter(s.endTime) }
            if (inNight.isNotEmpty()) o.put("hrv", (inNight.map { it.heartRateVariabilityMillis }.average() * 10).roundToInt() / 10.0)

            // FC nocturne : moyenne et plancher (moyenne glissante sur 5 mesures).
            val hr = readAll(client, HeartRateRecord::class, s.startTime, s.endTime)
                .flatMap { it.samples }.filter { !it.time.isBefore(s.startTime) && !it.time.isAfter(s.endTime) }
                .sortedBy { it.time }.map { it.beatsPerMinute.toDouble() }
            if (hr.size >= 10) {
                o.put("nightHrAvg", hr.average().roundToInt())
                o.put("nightHrMin", hr.windowed(5).minOf { it.average() }.roundToInt())
            }
        }
        // HRV hors sommeil enregistré (si la source n'écrit pas de session de sommeil).
        hrvs.groupBy { it.time.atZone(zone).toLocalDate() }.forEach { (d, l) ->
            val o = day(d); if (!o.has("hrv")) o.put("hrvDay", (l.map { it.heartRateVariabilityMillis }.average() * 10).roundToInt() / 10.0)
        }
        readAll(client, RestingHeartRateRecord::class, from, to)
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .forEach { (d, l) -> day(d).put("rhr", l.maxBy { it.time }.beatsPerMinute) }
        readAll(client, WeightRecord::class, from, to)
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .forEach { (d, l) -> day(d).put("weightKg", (l.maxBy { it.time }.weight.inKilograms * 10).roundToInt() / 10.0) }

        // Fusion avec le cache (les jours relus remplacent les anciens), purge > 60 jours.
        for ((d, o) in days) cache.put(d.toString(), o)
        val oldest = today.minusDays(KEEP_DAYS).toString()
        cache.keys().asSequence().toList().filter { it < oldest }.forEach { cache.remove(it) }
        prefs.edit().putString("cache", cache.toString()).apply()

        val doc = JSONObject().put("v", 1).put("updatedAt", System.currentTimeMillis())
            .put("tz", zone.id).put("days", cache)
        upload(cfg, encrypt(doc.toString(), cfg.keyB64))
        cache.length()
    }

    private suspend fun <T : Record> readAll(client: HealthConnectClient, type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = 1000, pageToken = token))
            out += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    fun encrypt(json: String, keyB64: String): String {
        val key = Base64.decode(keyB64, Base64.DEFAULT)
        require(key.size == 32) { "Clé de chiffrement invalide" }
        val gz = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(json.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(gz)
        return JSONObject().put("enc", "aes-256-gcm+gzip").put("v", 1)
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP)).toString()
    }

    /** 409 = le repo a bougé entre la lecture du SHA et l'écriture (ex. l'app sauvegarde une
     *  séance au même moment) : on relit le SHA et on réessaie. */
    private fun upload(cfg: SyncConfig, envelope: String) {
        val url = "https://api.github.com/repos/${cfg.repo}/contents/${cfg.path}"
        var last = ""
        repeat(4) { attempt ->
            if (attempt > 0) Thread.sleep(1500L * attempt)
            val sha = http("GET", "$url?t=${System.currentTimeMillis()}", cfg.token, null).let { (code, body) ->
                if (code == 200) JSONObject(body).optString("sha").ifEmpty { null } else null
            }
            val body = JSONObject()
                .put("message", "Données santé (chiffrées) mises à jour")
                .put("content", Base64.encodeToString(envelope.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (sha != null) body.put("sha", sha)
            val (code, resp) = http("PUT", url, cfg.token, body.toString())
            if (code in 200..201) return
            last = "GitHub a répondu $code : ${resp.take(120)}"
            if (code != 409 && code != 422) throw IllegalStateException(last)
        }
        throw IllegalStateException(last)
    }

    private fun http(method: String, url: String, token: String, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "token $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "hiit-bridge")
        c.connectTimeout = 20000; c.readTimeout = 30000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }
}
