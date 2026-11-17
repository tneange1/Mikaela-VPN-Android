package xyz.mikaelavpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import java.io.File

/** Запуск VPN после перезагрузки — только если включено в настройках, выдано разрешение VPN и есть сохранённый конфиг. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            Api.init(ctx)
            if (!Api.store.boot || Api.store.access == null) return
            if (VpnService.prepare(ctx) != null) return
            val srv = Api.cachedServers().let { l -> l.firstOrNull { it.tag == Api.store.server } ?: l.firstOrNull() } ?: return
            File(ctx.filesDir, "xray.json").writeText(Api.buildXrayConfig(srv, Api.routing()))
            ContextCompat.startForegroundService(ctx, Intent(ctx, VpnSvc::class.java).setAction(VpnSvc.A_START))
        } catch (_: Throwable) {}
    }
}
