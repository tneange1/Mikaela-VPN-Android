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
import org.json.JSONTokener
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AuthExpired : Exception("AUTH")

data class Resp(val code: Int, val text: String, val isJson: Boolean) {
    val ok get() = code in 200..299
    fun obj(): JSONObject = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
    fun any(): Any? = try { JSONTokener(text).nextValue() } catch (e: Exception) { null }
}

data class Srv(
    val tag: String, val cfg: JSONObject, val proto: String, val net: String, val sec: String,
    val hosts: List<Pair<String, Int>>, val auto: Boolean, val udp: Boolean,
) {
    val flag: String get() {
        val sb = StringBuilder(); var i = 0
        while (i < tag.length) {
            val cp = tag.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) { sb.appendCodePoint(cp); i += Character.charCount(cp) } else break
        }
        return if (sb.isEmpty()) "🌐" else sb.toString()
    }
    val label: String get() = (if (tag.startsWith(flag)) tag.substring(flag.length) else tag).trim().ifEmpty { tag }
    val kind: String get() = if (auto) "Автовыбор" else listOf(proto, net, sec).filter { it.isNotEmpty() }.joinToString(" / ")
}

data class Sub(
    val url: String, val tariff: String, val daysLeft: Int?, val deviceLimit: Int,
    val usedGb: Double, val limitGb: Double, val expired: Boolean,
)
data class Tariff(val id: String, val name: String, val days: Int?, val status: String)
data class Dev(val hwid: String, val title: String, val platform: String, val self: Boolean)
data class DevList(val list: List<Dev>, val count: Int, val limit: Int?)
data class Period(val days: Int, val price: Long?, val text: String, val label: String, val disc: Int)
data class PayMethod(val id: String, val name: String, val minKopeks: Long, val options: List<Pair<String, String>>)
data class DevPrice(val cur: Int?, val max: Int?, val canAdd: Int?, val per: Long?, val total: Long?, val days: Int?, val blocked: Boolean)
data class AuthOpts(val email: Boolean = true, val reg: Boolean = true, val telegram: Boolean = false, val oauth: List<Pair<String, String>> = emptyList())
data class TgSession(val token: String, val bot: String, val expires: Int)
data class Routing(val mode: String = "bypass", val apps: List<String> = emptyList(), val domains: List<String> = emptyList())

private fun JSONObject.num(vararg k: String): Double? {
    for (x in k) if (has(x) && !isNull(x)) {
        val v = opt(x)
        if (v is Number) return v.toDouble()
        if (v is String) v.toDoubleOrNull()?.let { return it }
    }
    return null
}
private fun JSONObject.str(vararg k: String): String {
    for (x in k) { val v = optString(x, ""); if (v.isNotEmpty() && v != "null") return v }
    return ""
}
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

object Api {
    const val BASE = "https://cabinet.mikaelavpn.xyz"
    const val UA = "MikaelaVPN/1.0 (Android)"
    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private var prefix: String? = null
    lateinit var store: Store
    var myHwid = ""
        private set

    fun init(ctx: Context) {
        if (::store.isInitialized) return
        store = Store(ctx)
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        myHwid = MessageDigest.getInstance("SHA-256").digest(("mikaela-android-$id").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun sq() = store.subId?.takeIf { it.isNotEmpty() }?.let { "?subscription_id=" + enc(it) } ?: ""
    private fun sq2() = store.subId?.takeIf { it.isNotEmpty() }?.let { "&subscription_id=" + enc(it) } ?: ""

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
        if (d is JSONObject) return d.str("message", "error").ifEmpty { def }
        return r.obj().optString("message").ifEmpty { def }
    }

    private fun saveTokens(d: JSONObject, email: String? = null) {
        store.access = d.optString("access_token")
        d.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.refresh = it }
        (email ?: d.optJSONObject("user")?.optString("email"))?.takeIf { it.isNotEmpty() }?.let { store.email = it }
    }

    private fun refreshTokens(): Boolean {
        val rt = store.refresh ?: return false
        val r = call("/cabinet/auth/refresh", "POST", JSONObject().put("refresh_token", rt))
        if (!r.ok) { store.clearAuth(); return false }
        saveTokens(r.obj()); return true
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

    private suspend fun jcall(path: String, method: String = "GET", body: JSONObject? = null, def: String = "Ошибка"): Resp {
        val r = authed(path, method, body)
        if (!r.ok) throw Exception(errText(r, def + " (" + r.code + ")"))
        return r
    }

    // ---------- вход ----------
    suspend fun login(email: String, password: String) = withContext(Dispatchers.IO) {
        val r = call("/cabinet/auth/email/login", "POST", JSONObject().put("email", email).put("password", password))
        if (!r.ok) throw Exception(errText(r, "Неверная почта или пароль. Если вход был через Telegram или Google, пароль не задан: нажмите «Забыли пароль?»"))
        saveTokens(r.obj(), email)
    }

    suspend fun register(email: String, password: String, name: String): String = withContext(Dispatchers.IO) {
        val b = JSONObject().put("email", email).put("password", password).put("language", "ru")
        if (name.isNotBlank()) b.put("first_name", name.trim())
        val r = call("/cabinet/auth/email/register/standalone", "POST", b)
        if (!r.ok) throw Exception(errText(r, "Не удалось зарегистрироваться"))
        r.obj().optString("message").ifEmpty { "Письмо отправлено. Подтвердите почту и войдите." }
    }

    suspend fun forgot(email: String): String = withContext(Dispatchers.IO) {
        val r = call("/cabinet/auth/password/forgot", "POST", JSONObject().put("email", email))
        if (!r.ok) throw Exception(errText(r, "Не удалось отправить письмо"))
        "Если такой аккаунт есть, письмо со ссылкой для пароля отправлено."
    }

    suspend fun tgRequest(): TgSession = withContext(Dispatchers.IO) {
        val r = call("/cabinet/auth/deeplink/request", "POST"); val d = r.obj()
        if (!r.ok || d.optString("token").isEmpty()) throw Exception("Вход через Telegram недоступен")
        TgSession(d.optString("token"), d.optString("bot_username"), d.optInt("expires_in", 300))
    }

    /** 200 — вошли, 410 — истекло, остальное — ждём */
    suspend fun tgPoll(token: String): Int = withContext(Dispatchers.IO) {
        val r = call("/cabinet/auth/deeplink/poll", "POST", JSONObject().put("token", token))
        if (r.code == 200) saveTokens(r.obj())
        r.code
    }

    fun saveWebTokens(access: String, refresh: String) { store.access = access; store.refresh = refresh }

    suspend fun authOpts(): AuthOpts = withContext(Dispatchers.IO) {
        fun get(p: String): JSONObject? = try { val r = call(p); if (r.ok && r.isJson) r.obj() else null } catch (e: Exception) { null }
        var o = AuthOpts()
        get("/cabinet/auth/oauth/providers")?.optJSONArray("providers")?.let { a ->
            val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("name").isNotEmpty() }
                .map { it.optString("name") to it.str("display_name", "name") }
            o = o.copy(oauth = l)
        }
        get("/cabinet/branding/telegram-widget")?.let { tw ->
            if (tw.opt("enabled") != false && (tw.str("bot_username", "bot_name", "oidc_client_id").isNotEmpty() || tw.optBoolean("oidc_enabled") || tw.optBoolean("enabled")))
                o = o.copy(telegram = true)
        }
        get("/cabinet/branding/email-auth")?.let { em ->
            for (k in listOf("enabled", "email_auth_enabled", "email_enabled")) if (em.opt(k) is Boolean) { val v = em.optBoolean(k); o = o.copy(email = v, reg = v); break }
        }
        o
    }

    fun logout() = store.clearAuth()

    // ---------- аккаунт ----------
    suspend fun balanceKopeks(): Long? {
        for (p in listOf("/cabinet/auth/me", "/cabinet/balance")) {
            val r = authed(p)
            if (r.ok) { val o = r.obj(); o.num("balance_kopeks")?.let { return it.toLong() } }
        }
        return null
    }

    suspend fun tariffs(): List<Tariff> {
        for (p in listOf("/cabinet/subscriptions", "/cabinet/subscription/list")) {
            try {
                val r = authed(p); if (!r.ok) continue
                val root = r.any()
                val a = when (root) {
                    is JSONArray -> root
                    is JSONObject -> root.optJSONArray("subscriptions") ?: root.optJSONArray("items") ?: root.optJSONArray("data") ?: JSONArray()
                    else -> JSONArray()
                }
                val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
                    val id = x.opt("id")?.toString()?.takeIf { it != "null" } ?: x.opt("subscription_id")?.toString() ?: return@mapNotNull null
                    Tariff(id, x.str("tariff_name", "name").ifEmpty { x.optJSONObject("tariff")?.optString("name").orEmpty() }.ifEmpty { "Подписка" },
                        x.num("days_left")?.toInt(), x.optString("status"))
                }
                if (l.isNotEmpty()) return l
            } catch (e: AuthExpired) { throw e } catch (_: Exception) {}
        }
        return emptyList()
    }

    suspend fun subscription(): Sub {
        val r = jcall("/cabinet/subscription" + sq(), def = "Не удалось получить подписку")
        val s = r.obj().optJSONObject("subscription") ?: throw Exception("Нет активной подписки. Оформите её в боте или кабинете.")
        return Sub(
            url = s.optString("subscription_url"), tariff = s.optString("tariff_name").ifEmpty { "Подписка" },
            daysLeft = s.num("days_left")?.toInt(), deviceLimit = s.optInt("device_limit"),
            usedGb = s.optDouble("traffic_used_gb", 0.0), limitGb = s.optDouble("traffic_limit_gb", 0.0), expired = s.optBoolean("is_expired"),
        )
    }

    // ---------- устройства ----------
    suspend fun devices(): DevList {
        val r = jcall("/cabinet/subscription/devices" + sq(), def = "Не удалось получить устройства")
        val root = r.any(); val o = root as? JSONObject
        val a = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("devices") ?: root.optJSONArray("items") ?: root.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
            val h = x.str("hwid", "hwid_id", "id"); if (h.isEmpty()) null else
                Dev(h, x.str("device_model", "model", "name").ifEmpty { "Устройство" }, x.str("platform", "device_os"), h.equals(myHwid, true))
        }
        return DevList(list, o?.num("total", "connected")?.toInt() ?: list.size, o?.num("device_limit", "limit")?.toInt())
    }

    suspend fun deleteDevice(h: String) { jcall("/cabinet/subscription/devices/" + enc(h) + sq(), "DELETE") }
    suspend fun resetDevices() { jcall("/cabinet/subscription/devices" + sq(), "DELETE") }

    suspend fun devicePrice(n: Int): DevPrice {
        val o = jcall("/cabinet/subscription/devices/price?devices=$n" + sq2(), def = "Не удалось получить цену").obj()
        val cur = o.num("current_device_limit", "current_limit")?.toInt()
        val mx = o.num("max_device_limit", "max_devices", "device_limit_max")?.toInt()
        var can = o.num("max_can_add", "max_addable", "can_add_max", "max_to_add")?.toInt()
        if (can == null && mx != null && cur != null) can = mx - cur
        val per = o.num("price_per_device_kopeks", "per_device_price_kopeks", "device_price_kopeks")?.toLong()
        val total = o.num("total_price_kopeks", "price_kopeks", "final_price_kopeks")?.toLong() ?: per?.times(n)
        val blocked = o.opt("available") == false || o.opt("can_add") == false || (can != null && can < 1)
        return DevPrice(cur, mx, can, per, total, o.num("days_left", "days", "remaining_days")?.toInt(), blocked)
    }

    suspend fun buyDevices(n: Int) { jcall("/cabinet/subscription/devices/purchase" + sq(), "POST", JSONObject().put("devices", n)) }

    // ---------- пополнение и продление ----------
    suspend fun methods(): List<PayMethod> {
        val root = jcall("/cabinet/balance/payment-methods").any()
        val a = when (root) { is JSONArray -> root; is JSONObject -> root.optJSONArray("methods") ?: root.optJSONArray("items") ?: JSONArray(); else -> JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.opt("is_available") != false }.map { m ->
            val os = m.optJSONArray("options") ?: JSONArray()
            PayMethod(m.str("id"), m.str("name", "title", "id"), (m.num("min_amount_kopeks") ?: 1000.0).toLong(),
                (0 until os.length()).mapNotNull { os.optJSONObject(it) }.map { it.str("id", "code", "value") to it.str("name", "title", "label", "id") })
        }
    }

    /** Возвращает ссылку на оплату */
    suspend fun topup(amountKopeks: Long, method: String, option: String?): String {
        val b = JSONObject().put("amount_kopeks", amountKopeks).put("payment_method", method)
        if (!option.isNullOrEmpty()) b.put("payment_option", option)
        val u = jcall("/cabinet/balance/topup", "POST", b).obj().optString("payment_url")
        if (!u.startsWith("https://")) throw Exception("Платёжная система не вернула ссылку на оплату")
        return u
    }

    suspend fun renewalOptions(): List<Period> = parsePeriods(jcall("/cabinet/subscription/renewal-options" + sq()).text)
    suspend fun renew(days: Int) { jcall("/cabinet/subscription/renew" + sq(), "POST", JSONObject().put("period_days", days)) }

    private fun parsePeriods(text: String): List<Period> {
        val root: Any? = try { JSONTokener(text).nextValue() } catch (e: Exception) { null }
        var arr: JSONArray? = null
        if (root is JSONArray) arr = root
        else if (root is JSONObject) {
            for (k in listOf("periods", "options", "renewal_options", "renewal_periods", "available_periods", "prices", "items", "data")) {
                val a = root.optJSONArray(k); if (a != null) { arr = a; break }
            }
            if (arr == null) {
                val ks = root.keys().asSequence().filter { k -> k.isNotEmpty() && k.all { it.isDigit() } }.toList()
                if (ks.isNotEmpty()) {
                    arr = JSONArray()
                    for (k in ks) {
                        val v = root.opt(k)
                        arr.put(if (v is JSONObject) JSONObject(v.toString()).put("days", k.toInt()) else JSONObject().put("days", k.toInt()).put("price_kopeks", v))
                    }
                }
            }
        }
        val a = arr ?: return emptyList()
        val out = ArrayList<Period>()
        for (i in 0 until a.length()) {
            val x = a.opt(i)
            if (x is Number) { out.add(Period(x.toInt(), null, "", "", 0)); continue }
            if (x !is JSONObject) continue
            val days = x.num("period_days", "days", "period", "duration_days")?.toInt() ?: x.num("months")?.let { (it * 30).toInt() } ?: x.num("value")?.toInt() ?: continue
            val price = x.num("price_kopeks", "final_price_kopeks", "total_price_kopeks", "amount_kopeks")?.toLong() ?: x.num("price_rubles")?.let { Math.round(it * 100) }
            out.add(Period(days, price, x.str("price_label", "price_display", "formatted_price"), x.str("label", "title", "name", "period_label"), x.num("discount_percent", "discount")?.toInt() ?: 0))
        }
        return out
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

    fun buildServers(arr: JSONArray): List<Srv> {
        val out = ArrayList<Srv>(); val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val cfg = arr.optJSONObject(i) ?: continue
            val outs = cfg.optJSONArray("outbounds") ?: continue
            var prox = (0 until outs.length()).mapNotNull { outs.optJSONObject(it) }.filter { isProxy(it) }
            if (prox.isEmpty()) continue
            val bal = cfg.optJSONObject("routing")?.optJSONArray("balancers")?.optJSONObject(0)
            val sel = bal?.optJSONArray("selector")
            if (sel != null) {
                val pre = (0 until sel.length()).map { sel.optString(it) }
                val m = prox.filter { o -> pre.any { o.optString("tag").startsWith(it) } }
                if (m.isNotEmpty()) prox = m
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

    fun cachedServers(): List<Srv> = try { store.cache?.let { buildServers(JSONArray(it)) } ?: emptyList() } catch (e: Exception) { emptyList() }

    private fun withJsonPath(url: String): String {
        val u = URI(url)
        return URI(u.scheme, u.authority, u.path.trimEnd('/') + "/json", u.query, null).toString()
    }

    suspend fun pullServers(url: String): List<Srv> = withContext(Dispatchers.IO) {
        var last = "нет данных"
        for (u in listOf(url, withJsonPath(url))) {
            try {
                val req = Request.Builder().url(u).header("User-Agent", UA).header("x-hwid", myHwid)
                    .header("x-device-os", "Android").header("x-ver-os", Build.VERSION.RELEASE)
                    .header("x-device-model", Build.MODEL).build()
                http.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty().trim()
                    val arr = try { if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t)) }
                    catch (e: Exception) { last = "ответ не JSON (HTTP ${r.code})"; null }
                    if (arr != null) {
                        val l = buildServers(arr)
                        if (l.isNotEmpty()) { store.cache = arr.toString(); store.subUrl = url; return@withContext l }
                        last = "нет подходящих серверов (возможно, достигнут лимит устройств)"
                    }
                }
            } catch (e: Exception) { last = e.message ?: "ошибка сети" }
        }
        throw Exception("Не удалось получить подписку: $last")
    }

    /** Реальная задержка: приложение исключено из VPN, поэтому сокет идёт напрямую. */
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

    // ---------- маршрутизация ----------
    fun routing(): Routing = try {
        val j = JSONObject(store.routing ?: "{}")
        fun list(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        Routing(if (j.optString("mode") == "only") "only" else "bypass", list("apps"), list("domains"))
    } catch (e: Exception) { Routing() }

    fun saveRouting(r: Routing) {
        store.routing = JSONObject().put("mode", r.mode).put("apps", JSONArray(r.apps)).put("domains", JSONArray(r.domains)).toString()
    }

    fun normDomain(s: String): String =
        s.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("*.").removePrefix(".").split('/', ':', '?', '#')[0]

    /** Конфиг для Xray: конфиг панели как есть, входящие заменяются на TUN, плюс доменные правила пользователя. */
    fun buildXrayConfig(s: Srv, rt: Routing): String {
        val c = JSONObject(s.cfg.toString())
        c.put("log", JSONObject().put("loglevel", "warning"))
        val tun = JSONObject().put("tag", "tun-in").put("protocol", "tun")
            .put("settings", JSONObject().put("name", "xray0").put("mtu", 1500))
            .put("sniffing", JSONObject().put("enabled", true).put("routeOnly", true)
                .put("destOverride", JSONArray(listOf("http", "tls", "quic"))))
        c.put("inbounds", JSONArray().put(tun))

        if (rt.domains.isNotEmpty()) {
            val outs = c.optJSONArray("outbounds") ?: JSONArray().also { c.put("outbounds", it) }
            var hasDirect = false; var proxyTag: String? = null
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (o.optString("tag") == "direct") hasDirect = true
                if (proxyTag == null && isProxy(o)) proxyTag = o.optString("tag")
            }
            if (!hasDirect) outs.put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
            val routing = c.optJSONObject("routing") ?: JSONObject().also { c.put("routing", it) }
            val old = routing.optJSONArray("rules") ?: JSONArray()
            val doms = JSONArray(rt.domains.map { "domain:$it" })
            val rules = JSONArray()
            val bal = routing.optJSONArray("balancers")?.optJSONObject(0)?.optString("tag")?.takeIf { it.isNotEmpty() }
            if (rt.mode == "only") {
                val r1 = JSONObject().put("type", "field").put("domain", doms)
                if (bal != null) r1.put("balancerTag", bal) else r1.put("outboundTag", proxyTag ?: "direct")
                rules.put(r1)
                rules.put(JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", "direct"))
            } else {
                rules.put(JSONObject().put("type", "field").put("domain", doms).put("outboundTag", "direct"))
                for (i in 0 until old.length()) rules.put(old.get(i))
            }
            routing.put("rules", rules)
        }
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
