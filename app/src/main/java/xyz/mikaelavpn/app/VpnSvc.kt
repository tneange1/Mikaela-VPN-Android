package xyz.mikaelavpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import mikaelacore.Mikaelacore
import java.io.File

class VpnSvc : VpnService() {
    companion object {
        const val A_START = "xyz.mikaelavpn.START"
        const val A_STOP = "xyz.mikaelavpn.STOP"
        val state = MutableStateFlow(false)
        @Volatile var lastError: String? = null

        fun assetDir(ctx: Context): String {
            val d = File(ctx.filesDir, "xray-assets").apply { mkdirs() }
            val mark = File(d, "version"); val ver = BuildConfig.VERSION_CODE.toString()
            val fresh = mark.exists() && mark.readText() == ver
            for (n in listOf("geoip.dat", "geosite.dat")) {
                val f = File(d, n)
                if (fresh && f.exists()) continue
                try { ctx.assets.open(n).use { i -> f.outputStream().use { o -> i.copyTo(o) } } } catch (_: Exception) {}
            }
            mark.writeText(ver)
            return d.absolutePath
        }
    }

    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == A_STOP) { stopVpn(); return START_NOT_STICKY }
        startFg()
        Thread { startVpn() }.start()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "vpn").setContentTitle("Mikaela VPN").setContentText("Защита активна")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(1, n)
    }

    private fun startVpn() {
        try {
            lastError = null
            val cfg = File(filesDir, "xray.json").readText()
            val pfd: ParcelFileDescriptor = Builder().setSession("Mikaela VPN").setMtu(1500)
                .addAddress("172.19.0.1", 30).addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("0.0.0.0", 0).addRoute("::", 0)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName)   // сам Xray работает в нашем процессе — его трафик идёт мимо туннеля
                .establish() ?: throw Exception("Разрешение на VPN не выдано")
            val fd = pfd.detachFd()
            try {
                Mikaelacore.start(cfg, assetDir(this), fd.toLong())
            } catch (e: Exception) {
                try { ParcelFileDescriptor.adoptFd(fd).close() } catch (_: Exception) {}
                throw e
            }
            running = true; state.value = true
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString(); state.value = false; stopSelf()
        }
    }

    private fun stopVpn() {
        try { Mikaelacore.stop() } catch (_: Throwable) {}
        running = false; state.value = false
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() { if (running) try { Mikaelacore.stop() } catch (_: Throwable) {}; state.value = false; super.onDestroy() }
}
