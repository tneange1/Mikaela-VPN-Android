package xyz.mikaelavpn.app

import android.app.Application
import android.content.Context
import android.content.Intent
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
    var servers by mutableStateOf<List<Srv>>(emptyList())
    var pings by mutableStateOf<Map<String, Int>>(emptyMap())
    var selected by mutableStateOf(Api.store.server ?: "")
    var devices by mutableStateOf<List<Dev>>(emptyList())
    var theme by mutableStateOf(Api.store.theme)
    var newVersion by mutableStateOf<String?>(null)
    var connecting by mutableStateOf(false)

    val current: Srv? get() = servers.firstOrNull { it.tag == selected } ?: servers.firstOrNull()

    init {
        if (screen == "main") refresh()
        viewModelScope.launch { while (true) { checkUpdate(); delay(3 * 60 * 1000L) } }
    }

    private suspend fun checkUpdate() {
        val v = Api.latestVersion() ?: return
        newVersion = if (Api.isNewer(v, BuildConfig.VERSION_NAME)) v else null
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) { error = "Введите почту и пароль"; return }
        viewModelScope.launch {
            busy = true; error = ""
            try { Api.login(email.trim(), password); screen = "main"; refresh() }
            catch (e: Exception) { error = e.message ?: "Ошибка входа" }
            busy = false
        }
    }

    fun logout(ctx: Context) {
        stop(ctx); Api.logout(); servers = emptyList(); sub = null; balance = null; devices = emptyList(); screen = "login"
    }

    fun refresh() {
        viewModelScope.launch {
            busy = true; error = ""
            try {
                balance = Api.balanceKopeks()
                val s = Api.subscription(); sub = s
                if (s.url.isEmpty()) throw Exception("Нет активной подписки. Оформите её в боте или кабинете.")
                servers = Api.pullServers(s.url)
                if (servers.none { it.tag == selected }) pick(servers.first().tag)
                pingAll()
            } catch (e: AuthExpired) { Api.logout(); screen = "login" }
            catch (e: Exception) { error = e.message ?: "Ошибка" }
            busy = false
        }
    }

    fun pick(tag: String) { selected = tag; Api.store.server = tag }

    fun pingAll() {
        viewModelScope.launch {
            pings = emptyMap()
            servers.map { s -> async { s.tag to Api.ping(s) } }.awaitAll().let { pings = it.toMap() }
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            try { devices = Api.devices() } catch (e: AuthExpired) { screen = "login" } catch (e: Exception) { error = e.message ?: "" }
        }
    }

    fun deleteDevice(h: String) {
        viewModelScope.launch {
            try { Api.deleteDevice(h); loadDevices() } catch (e: Exception) { error = e.message ?: "" }
        }
    }

    fun changeTheme(t: String) { theme = t; Api.store.theme = t }

    // ---------- VPN ----------
    fun writeConfig(ctx: Context): Boolean {
        val s = current ?: return false
        File(ctx.filesDir, "xray.json").writeText(Api.buildXrayConfig(s))
        return true
    }

    fun start(ctx: Context) {
        if (!writeConfig(ctx)) { error = "Нет сервера для подключения"; return }
        error = ""; connecting = true
        ContextCompat.startForegroundService(ctx, Intent(ctx, VpnSvc::class.java).setAction(VpnSvc.A_START))
        viewModelScope.launch {
            delay(6000); connecting = false
            if (!VpnSvc.state.value) VpnSvc.lastError?.let { error = it }
        }
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, VpnSvc::class.java).setAction(VpnSvc.A_STOP)); connecting = false
    }
}
