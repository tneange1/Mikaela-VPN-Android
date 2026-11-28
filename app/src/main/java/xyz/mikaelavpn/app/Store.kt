package xyz.mikaelavpn.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Токены и настройки. Токены хранятся зашифрованно (Android Keystore). */
class Store(ctx: Context) {
    private val sp: SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "mikaela_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("mikaela_plain", Context.MODE_PRIVATE)
    }

    private fun s(k: String): String? = sp.getString(k, null)
    private fun put(k: String, v: String?) { sp.edit().putString(k, v).apply() }

    var access: String? get() = s("a"); set(v) = put("a", v)
    var refresh: String? get() = s("r"); set(v) = put("r", v)
    var email: String? get() = s("email"); set(v) = put("email", v)
    var server: String? get() = s("server"); set(v) = put("server", v)
    var theme: String get() = s("theme") ?: "system"; set(v) = put("theme", v)
    var seen: String? get() = s("seen"); set(v) = put("seen", v)
    /** Первичная настройка маршрутизации уже предлагалась */
    var rtWiz: Boolean get() = s("rtwiz") == "1"; set(v) = put("rtwiz", if (v) "1" else "0")
    /** Канал обновлений: stable | ptb | dev */
    var channel: String get() = s("channel")?.takeIf { it in listOf("stable", "ptb", "dev") } ?: "stable"; set(v) = put("channel", v)
    var subId: String? get() = s("subid"); set(v) = put("subid", v)
    var subUrl: String? get() = s("suburl"); set(v) = put("suburl", v)
    var cache: String? get() = s("cache"); set(v) = put("cache", v)
    var routing: String? get() = s("routing"); set(v) = put("routing", v)
    var pre: String? get() = s("pre"); set(v) = put("pre", v)
    var autoConnect: Boolean get() = sp.getBoolean("auto", false); set(v) { sp.edit().putBoolean("auto", v).apply() }
    var resume: Boolean get() = sp.getBoolean("resume", false); set(v) { sp.edit().putBoolean("resume", v).apply() }
    var boot: Boolean get() = sp.getBoolean("boot", false); set(v) { sp.edit().putBoolean("boot", v).apply() }
    var interval: Long get() = sp.getLong("iv", 300000L); set(v) { sp.edit().putLong("iv", v).apply() }

    fun clearAuth() { access = null; refresh = null; email = null; subId = null; subUrl = null; cache = null }
}
