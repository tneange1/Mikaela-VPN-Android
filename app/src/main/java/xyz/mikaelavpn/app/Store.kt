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

    fun clearAuth() { access = null; refresh = null; email = null }
}
