package io.github.vincentaravantinos.hiitbridge

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * API Oura v2 (OAuth2, code d'autorisation). La PWA envoie client ID / secret + le code reçu
 * au retour de l'écran d'autorisation Oura ; le pont échange le code et garde les jetons pour
 * lui seul (le jeton de rafraîchissement est à usage unique : un seul détenteur possible).
 * Lit chaque jour : score de préparation (+ facteurs, écart de température), score de
 * sommeil et niveau de résilience. Rien de tout ça n'existe dans Health Connect.
 */
object OuraSync {
    private const val PREFS = "hiit-bridge-oura"
    // Nouveau serveur d'autorisation (apps créées sur le portail développeur actuel) ; l'ancien
    // reste en repli pour les apps historiques. Un échec d'authentification client ne consomme
    // pas le code d'autorisation, on peut donc tenter l'un puis l'autre.
    private val TOKEN_URLS = listOf(
        "https://moi.ouraring.com/oauth/v2/ext/oauth-token",
        "https://api.ouraring.com/oauth/token",
    )
    private const val API = "https://api.ouraring.com/v2/usercollection"

    fun isConnected(ctx: Context) = prefs(ctx).getString("refresh", null) != null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Échange le code d'autorisation contre les jetons. À appeler hors du thread principal. */
    fun connect(ctx: Context, cid: String, secret: String, code: String, redirect: String) {
        val resp = tokenRequest(mapOf(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirect,
            "client_id" to cid, "client_secret" to secret,
        ))
        prefs(ctx).edit().putString("cid", cid.trim()).putString("secret", secret.trim()).apply()
        saveTokens(ctx, resp)
    }

    private fun saveTokens(ctx: Context, o: JSONObject) {
        val access = o.optString("access_token").ifEmpty { throw IllegalStateException("Oura : pas de jeton reçu") }
        val exp = System.currentTimeMillis() + o.optLong("expires_in", 86400L) * 1000L
        prefs(ctx).edit().putString("access", access).putLong("exp", exp)
            .also { e -> o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { e.putString("refresh", it) } }
            .commit() // commit : le nouveau jeton de rafraîchissement ne doit jamais être perdu
    }

    @Synchronized
    private fun accessToken(ctx: Context): String {
        val p = prefs(ctx)
        val access = p.getString("access", null)
        if (access != null && System.currentTimeMillis() < p.getLong("exp", 0L) - 3600_000L) return access
        val refresh = p.getString("refresh", null) ?: throw IllegalStateException("Oura non connecté")
        val resp = tokenRequest(mapOf(
            "grant_type" to "refresh_token", "refresh_token" to refresh,
            "client_id" to (p.getString("cid", null) ?: ""), "client_secret" to (p.getString("secret", null) ?: ""),
        ))
        saveTokens(ctx, resp)
        return resp.getString("access_token")
    }

    /**
     * Données Oura par jour (clé = date ISO), ou null si Oura n'est pas connecté.
     * Lève une exception en cas d'échec réseau / jeton : l'appelant garde alors l'ancien cache.
     */
    fun fetch(ctx: Context, from: LocalDate, to: LocalDate): Map<String, JSONObject>? {
        if (!isConnected(ctx)) return null
        val token = accessToken(ctx)
        val out = mutableMapOf<String, JSONObject>()
        fun day(d: String) = out.getOrPut(d) { JSONObject() }
        val range = "start_date=$from&end_date=${to.plusDays(1)}"

        for (r in list("$API/daily_readiness?$range", token)) {
            val d = r.optString("day"); if (d.isEmpty()) continue
            if (r.isNull("score")) continue
            val o = day(d).put("readiness", r.getInt("score"))
            if (!r.isNull("temperature_deviation")) o.put("tempDev", r.getDouble("temperature_deviation"))
            r.optJSONObject("contributors")?.let { c ->
                val keep = JSONObject()
                c.keys().forEach { k -> if (!c.isNull(k)) keep.put(k, c.getInt(k)) }
                o.put("contrib", keep)
            }
        }
        for (r in list("$API/daily_sleep?$range", token)) {
            val d = r.optString("day"); if (d.isEmpty()) continue
            if (!r.isNull("score")) day(d).put("sleepScore", r.getInt("score"))
        }
        // Résilience : facultative (pas disponible sur tous les comptes / toutes les bagues).
        try {
            for (r in list("$API/daily_resilience?$range", token)) {
                val d = r.optString("day"); if (d.isEmpty()) continue
                r.optString("level").takeIf { it.isNotEmpty() && it != "null" }?.let { day(d).put("resilience", it) }
            }
        } catch (e: Exception) { /* ignorée */ }
        return out
    }

    private fun list(url: String, token: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var next: String? = null
        do {
            val u = if (next == null) url else "$url&next_token=${enc(next)}"
            val (code, body) = http("GET", u, mapOf("Authorization" to "Bearer $token"), null)
            if (code != 200) throw IllegalStateException("Oura a répondu $code : ${body.take(120)}")
            val o = JSONObject(body)
            val data = o.optJSONArray("data")
            if (data != null) for (i in 0 until data.length()) out += data.getJSONObject(i)
            next = o.optString("next_token").takeIf { it.isNotEmpty() && it != "null" }
        } while (next != null)
        return out
    }

    /** Pour chaque serveur : identifiants dans le corps, puis en HTTP Basic si invalid_client. */
    private fun tokenRequest(form: Map<String, String>): JSONObject {
        val f = form.mapValues { it.value.trim() }
        val ct = "Content-Type" to "application/x-www-form-urlencoded"
        fun encode(m: Map<String, String>) = m.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val basic = android.util.Base64.encodeToString(
            "${f["client_id"]}:${f["client_secret"]}".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val errors = mutableListOf<String>()
        for (url in TOKEN_URLS) {
            var (code, resp) = http("POST", url, mapOf(ct), encode(f))
            if (code == 401 || resp.contains("invalid_client")) {
                val r2 = http("POST", url, mapOf(ct, "Authorization" to "Basic $basic"), encode(f - "client_id" - "client_secret"))
                code = r2.first; resp = r2.second
            }
            if (code == 200) return JSONObject(resp)
            errors += "${url.substringAfter("//").substringBefore("/")} $code : ${resp.take(150)}"
            // Code d'autorisation consommé ou refusé pour une autre raison : inutile d'insister ailleurs.
            if (resp.contains("invalid_grant")) break
        }
        // Aperçu masqué de ce qui a été envoyé, à comparer avec le portail Oura (jamais le secret entier).
        fun peek(v: String?) = v.orEmpty().let { if (it.length <= 8) "${it.length} car." else "${it.take(4)}…${it.takeLast(4)} (${it.length} car.)" }
        throw IllegalStateException("Oura (jeton) a refusé — " + errors.joinToString(" | ") +
            "\n\nEnvoyé : Client ID ${peek(f["client_id"])}, Secret ${peek(f["client_secret"])}")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Accept", "application/json")
        c.connectTimeout = 20000; c.readTimeout = 30000
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }
}
