package xyz.mikaelavpn.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AuthExpired : Exception("AUTH")

data class Resp(val code: Int, val text: String, val isJson: Boolean) {
    val ok get() = code in 200..299
    fun obj(): JSONObject = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
}

data class Srv(
    val tag: String, val cfg: JSONObject, val proto: String, val net: String, val sec: String,
    val hosts: List<Pair<String, Int>>, val auto: Boolean, val udp: Boolean,
) {
    /** Флаг-эмодзи в начале названия */
    val flag: String get() {
        val sb = StringBuilder(); var i = 0
        while (i < tag.length) {
            val cp = tag.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) { sb.appendCodePoint(cp); i += Character.charCount(cp) } else break
        }
        return if (sb.isEmpty()) "🌐" else sb.toString()
    }
    val label: String get() = tag.substring(flag.length.takeIf { tag.startsWith(flag) } ?: 0).trim().ifEmpty { tag }
    val kind: String get() = if (auto) "Автовыбор" else listOf(proto, net, sec).filter { it.isNotEmpty() }.joinToString(" / ")
}

data class Sub(
    val url: String, val tariff: String, val daysLeft: Int?, val deviceLimit: Int,
    val usedGb: Double, val limitGb: Double, val expired: Boolean,
)

data class Dev(val hwid: String, val title: String, val platform: String)

object Api {
    const val BASE = "https://cabinet.mikaelavpn.xyz"
    const val UA = "MikaelaVPN/1.0 (Android)"
    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private var prefix: String? = null
    lateinit var store: Store
    private var hwid = ""

    fun init(ctx: Context) {
        store = Store(ctx)
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        hwid = MessageDigest.getInstance("SHA-256").digest(("mikaela-android-$id").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    // ---------- низкий уровень ----------
    private fun exec(url: String, method: String, body: JSONObject?, token: String?): Resp {
        val rb = if (method == "GET") null else (body?.toString() ?: "{}").toRequestBody(JSON)
        val b = Request.Builder().url(url).method(method, rb).header("User-Agent", UA)
        if (token != null) b.header("Authorization", "Bearer $token")
        http.newCall(b.build()).execute().use { r ->
            return Resp(r.code, r.body?.string().orEmpty(), (r.header("content-type") ?: "").contains("json"))
        }
    }

    private fun call(path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): Resp {
        prefix?.let { return exec(BASE + it + path, method, body, token) }
        var last: Resp? = null
        for (pf in listOf("", "/api")) {
            val r = exec(BASE + pf + path, method, body, token)
            if (r.isJson) { prefix = pf; return r }
            last = r
        }
        return last!!
    }

    private fun errText(r: Resp, def: String): String {
        val d = r.obj().opt("detail")
        if (d is String) return d
        if (d is JSONArray) return (0 until d.length()).joinToString("; ") { d.optJSONObject(it)?.optString("msg") ?: "" }
        return r.obj().optString("message").ifEmpty { def }
    }

    private fun refreshTokens(): Boolean {
        val rt = store.refresh ?: return false
        val r = call("/cabinet/auth/refresh", "POST", JSONObject().put("refresh_token", rt))
        if (!r.ok) { store.clearAuth(); return false }
        val d = r.obj()
        store.access = d.optString("access_token")
        d.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.refresh = it }
        return true
    }

    private suspend fun authed(path: String, method: String = "GET", body: JSONObject? = null): Resp =
        withContext(Dispatchers.IO) {
            repeat(2) {
                val a = store.access ?: throw AuthExpired()
                val r = call(path, method, body, a)
                if (r.code != 401) return@withContext r
                if (!refreshTokens()) throw AuthExpired()
            }
            throw AuthExpired()
        }

    // ---------- аккаунт ----------
    suspend fun login(email: String, password: String) = withContext(Dispatchers.IO) {
        val r = call("/cabinet/auth/email/login", "POST", JSONObject().put("email", email).put("password", password))
        if (!r.ok) throw Exception(errText(r, "Неверная почта или пароль"))
        val d = r.obj()
        store.access = d.optString("access_token"); store.refresh = d.optString("refresh_token"); store.email = email
    }

    fun logout() = store.clearAuth()

    suspend fun balanceKopeks(): Long? {
        for (p in listOf("/cabinet/auth/me", "/cabinet/balance")) {
            val r = authed(p)
            if (r.ok) { val o = r.obj(); if (o.has("balance_kopeks")) return o.optLong("balance_kopeks") }
        }
        return null
    }

    suspend fun subscription(): Sub {
        val r = authed("/cabinet/subscription")
        if (!r.ok) throw Exception(errText(r, "Не удалось получить подписку"))
        val s = r.obj().optJSONObject("subscription") ?: throw Exception("Нет активной подписки. Оформите её в боте или кабинете.")
        return Sub(
            url = s.optString("subscription_url"), tariff = s.optString("tariff_name").ifEmpty { "Подписка" },
            daysLeft = if (s.has("days_left") && !s.isNull("days_left")) s.optInt("days_left") else null,
            deviceLimit = s.optInt("device_limit"), usedGb = s.optDouble("traffic_used_gb", 0.0),
            limitGb = s.optDouble("traffic_limit_gb", 0.0), expired = s.optBoolean("is_expired"),
        )
    }

    suspend fun devices(): List<Dev> {
        val r = authed("/cabinet/subscription/devices")
        if (!r.ok) throw Exception(errText(r, "Не удалось получить устройства"))
        val o = r.obj()
        val a = o.optJSONArray("devices") ?: o.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).mapNotNull {
            val x = a.optJSONObject(it) ?: return@mapNotNull null
            val h = x.optString("hwid").ifEmpty { x.optString("id") }
            if (h.isEmpty()) null else Dev(h, x.optString("device_model").ifEmpty { x.optString("model") }.ifEmpty { "Устройство" }, x.optString("platform"))
        }
    }

    suspend fun deleteDevice(hwid: String) {
        val r = authed("/cabinet/subscription/devices/" + java.net.URLEncoder.encode(hwid, "UTF-8"), "DELETE")
        if (!r.ok) throw Exception(errText(r, "Не удалось удалить устройство"))
    }

    // ---------- серверы (Xray JSON из панели) ----------
    private val BAD = setOf("0.0.0.0", "127.0.0.1", "localhost", "::", "::1")
    private fun isProxy(o: JSONObject) = o.optString("protocol").let { it.isNotEmpty() && it !in setOf("freedom", "blackhole", "dns", "loopback") }
    private fun hostOf(o: JSONObject): Pair<String, Int>? {
        val s = o.optJSONObject("settings") ?: return null
        val v = s.optJSONArray("vnext")?.optJSONObject(0) ?: s.optJSONArray("servers")?.optJSONObject(0) ?: s
        val a = v.optString("address"); if (a.isEmpty()) return null
        return a to v.optInt("port", 443)
    }

    private fun buildServers(arr: JSONArray): List<Srv> {
        val out = ArrayList<Srv>(); val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val cfg = arr.optJSONObject(i) ?: continue
            val outs = cfg.optJSONArray("outbounds") ?: continue
            var prox = (0 until outs.length()).mapNotNull { outs.optJSONObject(it) }.filter { isProxy(it) }
            if (prox.isEmpty()) continue
            val bal = cfg.optJSONObject("routing")?.optJSONArray("balancers")?.optJSONObject(0)
            if (bal != null) {
                val sel = bal.optJSONArray("selector")
                if (sel != null) {
                    val pre = (0 until sel.length()).map { sel.optString(it) }
                    val m = prox.filter { o -> pre.any { o.optString("tag").startsWith(it) } }
                    if (m.isNotEmpty()) prox = m
                }
            }
            val hosts = prox.mapNotNull { hostOf(it) }.filter { it.first !in BAD }
            if (hosts.isEmpty()) continue
            val name = cfg.optString("remarks").ifEmpty { cfg.optString("ps").ifEmpty { "server" } }
            var t = name; var n = 2; while (!seen.add(t)) t = "$name ${n++}"
            val ss = prox[0].optJSONObject("streamSettings")
            val net = ss?.optString("network").orEmpty().ifEmpty { "tcp" }
            val sec = ss?.optString("security").orEmpty().let { if (it == "none") "" else it }
            val proto = prox[0].optString("protocol")
            out.add(Srv(t, cfg, proto.uppercase(), net.uppercase(), sec.uppercase(), hosts, bal != null && prox.size > 1,
                proto.contains("hysteria", true) || net.contains("hysteria", true)))
        }
        return out
    }

    private fun withJsonPath(url: String): String {
        val u = URI(url)
        return URI(u.scheme, u.authority, u.path.trimEnd('/') + "/json", u.query, null).toString()
    }

    suspend fun pullServers(url: String): List<Srv> = withContext(Dispatchers.IO) {
        var last = "нет данных"
        for (u in listOf(url, withJsonPath(url))) {
            try {
                val req = Request.Builder().url(u).header("User-Agent", UA).header("x-hwid", hwid)
                    .header("x-device-os", "Android").header("x-ver-os", Build.VERSION.RELEASE)
                    .header("x-device-model", Build.MODEL).build()
                http.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty().trim()
                    val arr = try { if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t)) }
                    catch (e: Exception) { last = "ответ не JSON (HTTP ${r.code})"; null }
                    if (arr != null) {
                        val l = buildServers(arr)
                        if (l.isNotEmpty()) return@withContext l
                        last = "нет подходящих серверов (возможно, достигнут лимит устройств)"
                    }
                }
            } catch (e: Exception) { last = e.message ?: "ошибка сети" }
        }
        throw Exception("Не удалось получить подписку: $last")
    }

    /** Реальная задержка до сервера: приложение исключено из VPN, поэтому сокет идёт напрямую. */
    suspend fun ping(s: Srv): Int = withContext(Dispatchers.IO) {
        if (s.udp) return@withContext -2
        var best = -1
        for ((h, p) in s.hosts) {
            try {
                val t = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(h, p), 3000) }
                val ms = ((System.nanoTime() - t) / 1_000_000).toInt()
                if (best < 0 || ms < best) best = ms
            } catch (_: Exception) {}
        }
        best
    }

    /** Конфиг для Xray: берём конфиг панели как есть, меняем только входящие на TUN. */
    fun buildXrayConfig(s: Srv): String {
        val c = JSONObject(s.cfg.toString())
        c.put("log", JSONObject().put("loglevel", "warning"))
        val tun = JSONObject().put("tag", "tun-in").put("protocol", "tun")
            .put("settings", JSONObject().put("name", "xray0").put("mtu", 1500))
            .put("sniffing", JSONObject().put("enabled", true).put("routeOnly", true)
                .put("destOverride", JSONArray(listOf("http", "tls", "quic"))))
        c.put("inbounds", JSONArray().put(tun))
        return c.toString()
    }

    // ---------- обновления приложения ----------
    const val RELEASES_API = "https://api.github.com/repos/tneange1/Mikaela-VPN-Android/releases/latest"
    const val APK_URL = "https://github.com/tneange1/Mikaela-VPN-Android/releases/latest/download/Mikaela-VPN.apk"

    suspend fun latestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(RELEASES_API).header("Accept", "application/vnd.github+json").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optString("tag_name").removePrefix("v").ifEmpty { null }
            }
        } catch (e: Exception) { null }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split(".").map { it.toIntOrNull() ?: 0 }; val b = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
