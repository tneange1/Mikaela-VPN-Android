package xyz.mikaelavpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.ServiceCompat
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Схема как в Windows-версии: Xray (отдельные процессы, SOCKS на localhost) отвечает за протоколы,
 * sing-box (libbox) держит TUN и по приложению/домену решает, куда отправить соединение.
 */
class VpnSvc : VpnService(), PlatformInterface, CommandServerHandler {
    companion object {
        const val A_START = "xyz.mikaelavpn.START"
        const val A_STOP = "xyz.mikaelavpn.STOP"
        val state = MutableStateFlow(false)
        @Volatile var lastError: String? = null
        private var setupDone = false
        private val xrayLog = StringBuilder()

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
    private var cs: CommandServer? = null
    private var tun: ParcelFileDescriptor? = null
    private val procs = ArrayList<java.lang.Process>()
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var ifListener: InterfaceUpdateListener? = null
    @Volatile private var defNet: Network? = null
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

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

    // ---------- Xray как отдельный процесс ----------
    private fun startXray(port: Int, cfg: String, assets: String): java.lang.Process {
        val f = File(filesDir, "xray-$port.json"); f.writeText(cfg)
        val bin = File(applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) throw Exception("Не найден модуль Xray (libxray.so). Эта сборка не поддерживает ваш процессор")
        val pb = ProcessBuilder(bin.absolutePath, "run", "-c", f.absolutePath).redirectErrorStream(true)
        pb.environment()["XRAY_LOCATION_ASSET"] = assets
        val p = pb.start()
        Thread {
            try { p.inputStream.bufferedReader().forEachLine { l -> synchronized(xrayLog) { xrayLog.append(l).append('\n'); if (xrayLog.length > 4000) xrayLog.delete(0, xrayLog.length - 3000) } } } catch (_: Exception) {}
        }.start()
        return p
    }

    private fun portOpen(port: Int, ms: Int = 300) = try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), ms) }; true } catch (_: Exception) { false }

    private fun waitPort(p: java.lang.Process, port: Int, totalMs: Int): Boolean {
        val end = System.currentTimeMillis() + totalMs
        while (System.currentTimeMillis() < end) {
            if (!p.isAlive) return false
            if (portOpen(port)) return true
            Thread.sleep(150)
        }
        return false
    }

    private fun xrayTail() = synchronized(xrayLog) { xrayLog.toString().trim().lines().takeLast(4).joinToString(" | ") }

    private fun killProcs() { for (p in procs) try { p.destroy() } catch (_: Exception) {}; procs.clear() }

    private fun startVpn() {
        try {
            lastError = null
            val j = JSONObject(File(filesDir, "session.json").readText())
            val assets = assetDir(this)
            synchronized(xrayLog) { xrayLog.setLength(0) }
            val xs = j.getJSONArray("xray")
            val started = ArrayList<Pair<java.lang.Process, Int>>()
            for (i in 0 until xs.length()) {
                val o = xs.getJSONObject(i)
                val port = o.getInt("port")
                val p = startXray(port, o.getJSONObject("cfg").toString(), assets)
                procs.add(p); started.add(p to port)
            }
            // основной Xray обязателен; остальные (серверы правил) могут не подняться — тогда трафик их правил блокируется
            for ((idx, pp) in started.withIndex()) {
                val ok = waitPort(pp.first, pp.second, if (idx == 0) 20000 else 8000)
                if (!ok && idx == 0) throw Exception("Xray не запустился: " + xrayTail().ifEmpty { "нет вывода" })
            }

            if (!setupDone) {
                Libbox.setup(SetupOptions().also {
                    it.basePath = filesDir.path
                    it.workingPath = File(filesDir, "sb").apply { mkdirs() }.path
                    it.tempPath = cacheDir.path
                    it.fixAndroidStack = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    it.logMaxLines = 300
                    it.crashReportSource = "mikaela"
                    it.appVersion = BuildConfig.VERSION_CODE.toString()
                    it.appMarketingVersion = BuildConfig.VERSION_NAME
                })
                setupDone = true
            }
            startNetMonitor()
            val srv = CommandServer(this, this)
            srv.start()
            cs = srv
            srv.startOrReloadService(j.getJSONObject("sb").toString(), OverrideOptions())
            running = true; state.value = true
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString()
            cleanup(); state.value = false; stopSelf()
        }
    }

    private fun cleanup() {
        try { cs?.closeService() } catch (_: Throwable) {}
        try { cs?.close() } catch (_: Throwable) {}
        cs = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        stopNetMonitor()
        killProcs()
    }

    private fun stopVpn() {
        cleanup()
        running = false; state.value = false
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() { if (running) cleanup(); state.value = false; super.onDestroy() }

    // ---------- текущая сеть (для направления трафика «мимо VPN») ----------
    private fun startNetMonitor() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { defNet = network; notifyIface(network) }
            override fun onLost(network: Network) { if (defNet == network) { defNet = null; notifyIface(null) } }
        }
        netCb = cb
        defNet = cm.activeNetwork
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
    }

    private fun stopNetMonitor() { netCb?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }; netCb = null; ifListener = null }

    private fun notifyIface(n: Network?) {
        val l = ifListener ?: return
        if (n == null) { l.updateDefaultInterface("", -1, false, false); return }
        for (t in 0 until 10) {
            val lp = cm.getLinkProperties(n)
            val name = lp?.interfaceName
            val idx = try { NetworkInterface.getByName(name)?.index } catch (_: Exception) { null }
            if (name != null && idx != null) { l.updateDefaultInterface(name, idx, false, false); return }
            Thread.sleep(100)
        }
    }

    // ---------- PlatformInterface ----------
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) throw Exception("Разрешение на VPN не выдано")
        val b = Builder().setSession("Mikaela VPN").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val a4 = options.inet4Address; while (a4.hasNext()) { val a = a4.next(); b.addAddress(a.address(), a.prefix()) }
        val a6 = options.inet6Address; while (a6.hasNext()) { val a = a6.next(); b.addAddress(a.address(), a.prefix()) }
        val dm = options.dnsMode.value
        if (dm != Libbox.DNSModeDisabled && (options.autoRoute || dm.isNotEmpty())) {
            val d = options.dnsServerAddress
            while (d.hasNext()) { val s = d.next(); b.addDnsServer(s); if (!options.autoRoute) b.addRoute(s, if (s.contains(':')) 128 else 32) }
        }
        if (options.autoRoute) { b.addRoute("0.0.0.0", 0); b.addRoute("::", 0) }
        // приложение и его Xray (тот же uid) работают вне туннеля: иначе петля, а пинг был бы ненастоящим
        try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
        val p = b.establish() ?: throw Exception("Не удалось создать VPN-интерфейс")
        tun = p
        return p.fd
    }

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): ConnectionOwner {
        if (Build.VERSION.SDK_INT < 29) throw Exception("Определение приложения требует Android 10+")
        val uid = cm.getConnectionOwnerUid(ipProtocol, InetSocketAddress(sourceAddress, sourcePort), InetSocketAddress(destinationAddress, destinationPort))
        if (uid == Process.INVALID_UID) throw Exception("владелец соединения не найден")
        val pk = packageManager.getPackagesForUid(uid)?.toList() ?: emptyList()
        return ConnectionOwner().also { it.userId = uid; it.userName = pk.firstOrNull() ?: ""; it.setAndroidPackageNames(Strs(pk)) }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) { ifListener = listener; notifyIface(defNet) }
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) { ifListener = null }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val out = ArrayList<io.nekohasekai.libbox.NetworkInterface>()
        val all = try { NetworkInterface.getNetworkInterfaces().toList() } catch (_: Exception) { emptyList() }
        for (n in cm.allNetworks) {
            val lp = cm.getLinkProperties(n) ?: continue
            val caps = cm.getNetworkCapabilities(n) ?: continue
            val ni = all.find { it.name == lp.interfaceName } ?: continue
            out.add(io.nekohasekai.libbox.NetworkInterface().also {
                it.name = lp.interfaceName
                it.index = ni.index
                it.dnsServer = Strs(lp.dnsServers.mapNotNull { d -> d.hostAddress })
                it.gateway = Strs(lp.routes.filter { r -> r.destination.prefixLength == 0 }.mapNotNull { r -> r.gateway }.filterNot { g -> g.isAnyLocalAddress }.mapNotNull { g -> g.hostAddress })
                it.type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                try { it.mtu = ni.mtu } catch (_: Exception) {}
                it.addresses = Strs(ni.interfaceAddresses.map { a -> a.address.hostAddress.substringBefore('%') + "/" + a.networkPrefixLength })
                var fl = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) fl = android.system.OsConstants.IFF_UP or android.system.OsConstants.IFF_RUNNING
                if (ni.isLoopback) fl = fl or android.system.OsConstants.IFF_LOOPBACK
                if (ni.isPointToPoint) fl = fl or android.system.OsConstants.IFF_POINTOPOINT
                if (ni.supportsMulticast()) fl = fl or android.system.OsConstants.IFF_MULTICAST
                it.flags = fl
                it.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            })
        }
        val iter = out.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext() = iter.hasNext()
            override fun next() = iter.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {}
    override fun cancelNotification(identifier: String, typeID: Int) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() { throw Exception("not supported") }
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int): ShellSession { throw Exception("not supported") }
    override fun lookupUser(username: String?): PlatformUser { throw Exception("not supported") }
    override fun lookupSFTPServer(): String { throw Exception("not supported") }
    override fun readSystemSSHHostKey(): String { throw Exception("not supported") }
    override fun tailscaleHostname(): String = ""
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession { throw Exception("not supported") }

    // ---------- CommandServerHandler ----------
    override fun serviceStop() { Thread { stopVpn() }.start() }
    override fun serviceReload() {}
    override fun getSystemProxyStatus(): SystemProxyStatus? = SystemProxyStatus().also { it.available = false; it.enabled = false }
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) {}
    override fun connectSSHAgent(): Int { throw Exception("not supported") }
}

class Strs(private val l: List<String>) : StringIterator {
    private val it = l.iterator()
    override fun len(): Int = l.size
    override fun hasNext(): Boolean = it.hasNext()
    override fun next(): String = it.next()
}
