package xyz.mikaelavpn.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class AppVm(app: Application) : AndroidViewModel(app) {
    var screen by mutableStateOf(if (Api.store.access != null) "main" else "login")
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var balance by mutableStateOf<Long?>(null)
    var sub by mutableStateOf<Sub?>(null)
    var tariffs by mutableStateOf<List<Tariff>>(emptyList())
    var servers by mutableStateOf(Api.cachedServers())
    var pings by mutableStateOf<Map<String, Int>>(emptyMap())
    var selected by mutableStateOf(Api.store.server ?: "")
    var theme by mutableStateOf(Api.store.theme)
    var newVersion by mutableStateOf<String?>(null)
    var updState by mutableStateOf("idle")   // idle, downloading, ready, installing, error
    var updPercent by mutableStateOf(0)
    var updError by mutableStateOf("")
    private var updFile: File? = null
    var connecting by mutableStateOf(false)
    var toast by mutableStateOf("")
    var sheet by mutableStateOf("")
    var settingsPage by mutableStateOf("main")
    var routing by mutableStateOf(Api.routing())
    var routingDirty by mutableStateOf(false)
    var autoConnect by mutableStateOf(Api.store.autoConnect)
    var boot by mutableStateOf(Api.store.boot)
    var stale by mutableStateOf(Api.cachedServers().isNotEmpty())

    var introKind by mutableStateOf("")
    var introStep by mutableStateOf(0)

    private var refreshing = false
    private var autoDone = false

    val current: Srv? get() = servers.firstOrNull { it.tag == selected } ?: servers.firstOrNull()

    init {
        initIntro()
        if (screen == "main") refresh()
        // раз в минуту: свежий список серверов и проверка, не вышел ли готовый релиз (отдельных таймеров и кнопок нет)
        viewModelScope.launch { while (true) { checkUpdate(); delay(60_000L); if (screen == "main") refresh(true) } }
    }

    private suspend fun checkUpdate() {
        val v = Api.latestVersion() ?: return
        newVersion = if (Api.isNewer(v, BuildConfig.VERSION_NAME)) v else null
        if (newVersion != null && updState == "idle") downloadUpdate()   // как только релиз готов, обновление сразу скачивается в фоне
    }

    fun downloadUpdate() {
        if (updState == "downloading") return
        updState = "downloading"; updPercent = 0; updError = ""; Updater.error = ""
        viewModelScope.launch {
            try { updFile = Updater.download(getApplication(), { updPercent = it }); updState = "ready" }
            catch (e: Exception) { updState = "error"; updError = e.message ?: "ошибка сети" }
        }
    }

    fun installUpdate(ctx: Context) {
        val f = updFile
        if (f == null || !f.exists()) { downloadUpdate(); return }
        if (!Updater.canInstall(ctx)) { say("Разрешите установку обновлений и вернитесь в приложение"); Updater.askPermission(ctx); return }
        try {
            Api.store.resume = VpnSvc.state.value
            Updater.error = ""; updState = "installing"
            Updater.install(ctx, f)
            viewModelScope.launch { delay(20_000); if (updState == "installing") updState = "ready" }
        } catch (e: Exception) { updState = "ready"; Updater.error = e.message ?: "Не удалось установить" }
    }

    fun say(t: String) { toast = t }

    // ---------- «Что нового» и инструкция ----------
    private fun initIntro() {
        val st = Api.store; val v = BuildConfig.VERSION_NAME
        val seen = st.seen
        if (seen == null && st.access == null && st.email == null) { introKind = "guide"; introStep = 0; return }
        if (seen != v) { st.seen = v; if (NOTES[v] != null) { introKind = "notes"; introStep = 0 } }
    }
    fun openGuide() { introKind = "guide"; introStep = 0 }
    fun closeIntro() { if (introKind == "guide") Api.store.seen = BuildConfig.VERSION_NAME; introKind = "" }

    fun pingOne(tag: String) {
        val s = servers.firstOrNull { it.tag == tag } ?: return
        viewModelScope.launch {
            pings = pings - tag
            val p = Api.ping(s)
            pings = pings + (tag to p)
            say(s.label + ": " + when { p == -2 -> "UDP (Hysteria), пинг недоступен"; p < 0 -> "нет ответа"; else -> "$p мс" })
        }
    }

    fun expired() { Api.logout(); servers = emptyList(); sub = null; balance = null; screen = "login" }

    var tab by mutableStateOf(0)
    var opts by mutableStateOf(AuthOpts())
    var info by mutableStateOf("")
    var devices by mutableStateOf<DevList?>(null)
    var devErr by mutableStateOf("")
    var tg by mutableStateOf<TgSession?>(null)

    // ---------- вход ----------
    fun afterLogin() { screen = "main"; tab = 0; error = ""; info = ""; refresh() }

    fun loadOpts() { viewModelScope.launch { opts = Api.authOpts() } }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true; error = ""; info = ""
            try { block() } catch (e: AuthExpired) { expired() } catch (e: Exception) { error = e.message ?: "Ошибка" }
            busy = false
        }
    }

    fun login(email: String, pw: String) = act { Api.login(email.trim(), pw); afterLogin() }
    fun register(email: String, pw: String, name: String) = act { info = Api.register(email.trim(), pw, name) }
    fun forgot(email: String) = act { info = Api.forgot(email.trim()) }

    fun webLogin(a: String, r: String) { Api.saveWebTokens(a, r); afterLogin() }

    fun startTg(ctx: Context) {
        viewModelScope.launch {
            error = ""
            try {
                val t = Api.tgRequest(); tg = t
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/${t.bot}?start=${t.token}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val end = System.currentTimeMillis() + t.expires * 1000L
                while (tg?.token == t.token && System.currentTimeMillis() < end) {
                    delay(2000)
                    val c = try { Api.tgPoll(t.token) } catch (_: Exception) { 0 }
                    if (c == 200) { tg = null; afterLogin(); return@launch }
                    if (c == 410) break
                }
                if (tg?.token == t.token) { tg = null; error = "Время ожидания истекло" }
            } catch (e: Exception) { tg = null; error = e.message ?: "Ошибка" }
        }
    }
    fun cancelTg() { tg = null }

    // ---------- устройства ----------
    fun loadDevices() {
        viewModelScope.launch {
            try { devices = Api.devices(); devErr = "" } catch (e: AuthExpired) { expired() } catch (e: Exception) { devErr = e.message ?: "Ошибка" }
        }
    }
    fun deleteDevice(h: String) {
        viewModelScope.launch {
            try { Api.deleteDevice(h); loadDevices() } catch (e: AuthExpired) { expired() } catch (e: Exception) { devErr = e.message ?: "Ошибка" }
        }
    }
    fun resetDevices() {
        viewModelScope.launch {
            try { Api.resetDevices(); loadDevices() } catch (e: AuthExpired) { expired() } catch (e: Exception) { devErr = e.message ?: "Ошибка" }
        }
    }

    fun logout(ctx: Context) { stop(ctx); expired(); tariffs = emptyList() }

    // ---------- данные ----------
    fun refresh(silent: Boolean = false) {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            if (!silent) { busy = true; error = "" }
            try {
                balance = Api.balanceKopeks()
                tariffs = try { Api.tariffs() } catch (e: AuthExpired) { throw e } catch (_: Exception) { tariffs }
                val sid = Api.store.subId
                if (!sid.isNullOrEmpty() && tariffs.isNotEmpty() && tariffs.none { it.id == sid }) Api.store.subId = null
                val s = Api.subscription(); sub = s
                if (s.url.isEmpty()) throw Exception("Нет активной подписки. Оформите её в боте или кабинете.")
                servers = Api.pullServers(s.url); stale = false
                if (servers.none { it.tag == selected }) pick(servers.first().tag)
                if (!silent) pingAll()
            } catch (e: AuthExpired) { expired() }
            catch (e: Exception) {
                // кабинет недоступен — пробуем панель напрямую по сохранённому адресу
                val u = Api.store.subUrl
                var ok = false
                if (u != null) try { servers = Api.pullServers(u); stale = false; ok = true; if (servers.none { it.tag == selected }) pick(servers.first().tag) } catch (_: Exception) {}
                if (!silent || servers.isEmpty()) error = if (!ok && servers.isNotEmpty()) "Нет связи, показан сохранённый список" else if (ok) "" else (e.message ?: "Ошибка")
            }
            busy = false; refreshing = false
        }
    }

    fun reloadUser() {
        viewModelScope.launch {
            try { balance = Api.balanceKopeks(); sub = Api.subscription() } catch (e: AuthExpired) { expired() } catch (_: Exception) {}
        }
    }

    fun selectTariff(id: String) {
        Api.store.subId = id; Api.store.cache = null; Api.store.subUrl = null
        sub = null; servers = emptyList(); pings = emptyMap(); refresh()
    }

    fun pick(tag: String) { selected = tag; Api.store.server = tag }

    fun pingAll() {
        viewModelScope.launch {
            pings = emptyMap()
            // все серверы сразу; результат каждого появляется, как только он ответил
            servers.forEach { s -> launch { val p = Api.ping(s); pings = pings + (s.tag to p) } }
        }
    }

    fun watchBalance() {
        viewModelScope.launch {
            val before = balance
            repeat(24) {
                delay(5000)
                try { val b = Api.balanceKopeks(); if (b != null && b != before) { balance = b; say("Баланс пополнен"); return@launch } } catch (_: Exception) {}
            }
        }
    }

    // ---------- настройки ----------
    fun changeTheme(t: String) { theme = t; Api.store.theme = t }
    fun changeAuto(v: Boolean) { autoConnect = v; Api.store.autoConnect = v }
    fun changeBoot(v: Boolean) { boot = v; Api.store.boot = v }
    fun changeRouting(r: Routing) { routing = r; Api.saveRouting(r); routingDirty = true }

    // ---------- VPN ----------
    fun writeConfig(ctx: Context): Boolean {
        val s = current ?: return false
        File(ctx.filesDir, "session.json").writeText(Api.buildSession(s, routing, servers))
        return true
    }

    fun start(ctx: Context) {
        if (!writeConfig(ctx)) { error = "Нет сервера для подключения"; return }
        error = ""; connecting = true; routingDirty = false
        ContextCompat.startForegroundService(ctx, Intent(ctx, VpnSvc::class.java).setAction(VpnSvc.A_START))
        viewModelScope.launch {
            delay(6000); connecting = false
            if (!VpnSvc.state.value) VpnSvc.lastError?.let { error = it }
        }
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, VpnSvc::class.java).setAction(VpnSvc.A_STOP)); connecting = false
    }

    fun reconnect(ctx: Context) {
        viewModelScope.launch { stop(ctx); delay(1500); start(ctx) }
    }

    fun maybeAutoConnect(ctx: Context) {
        if (autoDone || !autoConnect || servers.isEmpty() || screen != "main") return
        autoDone = true
        if (!VpnSvc.state.value && VpnService.prepare(ctx) == null) start(ctx)
    }
}
