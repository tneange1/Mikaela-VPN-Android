package xyz.mikaelavpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import java.io.File

/** Запуск VPN после перезагрузки (если включено в настройках) и после обновления приложения (если VPN был включён). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val replaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && !replaced) return
        try {
            Api.init(ctx)
            if (replaced) {
                notifyUpdated(ctx)
                if (!Api.store.resume) return
                Api.store.resume = false
            } else if (!Api.store.boot) return
            if (Api.store.access == null) return
            if (VpnService.prepare(ctx) != null) return
            val srv = Api.cachedServers().let { l -> l.firstOrNull { it.tag == Api.store.server } ?: l.firstOrNull() } ?: return
            File(ctx.filesDir, "xray.json").writeText(Api.buildXrayConfig(srv, Api.routing(), Api.cachedServers()))
            ContextCompat.startForegroundService(ctx, Intent(ctx, VpnSvc::class.java).setAction(VpnSvc.A_START))
        } catch (_: Throwable) {}
    }

    private fun notifyUpdated(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("upd", "Обновления", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(2, Notification.Builder(ctx, "upd").setContentTitle("Mikaela VPN обновлён")
                .setContentText("Версия ${BuildConfig.VERSION_NAME} установлена. Нажмите, чтобы открыть.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: Throwable) {}
    }
}
