package xyz.mikaelavpn.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TopApp(val label: String, val pkg: String, val bytes: Long)

fun hasUsageAccess(ctx: Context): Boolean = try {
    val a = ctx.getSystemService(AppOpsManager::class.java)
    @Suppress("DEPRECATION")
    a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
} catch (_: Throwable) { false }

fun fmtBytes(b: Long): String = if (b >= 1L shl 30) "%.1f ГБ".format(b / 1073741824.0) else if (b >= 1L shl 20) "${b shr 20} МБ" else "${b shr 10} КБ"

/** Приложения, которые за последние 30 дней потратили больше всего трафика (данные системы, нужен «Доступ к истории использования»). */
suspend fun topApps(ctx: Context): List<TopApp> = withContext(Dispatchers.IO) {
    val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    val pm = ctx.packageManager
    val end = System.currentTimeMillis(); val start = end - 30L * 24 * 3600 * 1000
    val bytes = HashMap<Int, Long>()
    for (t in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
        try {
            val st = nsm.querySummary(t, null, start, end); val b = NetworkStats.Bucket()
            while (st.hasNextBucket()) { st.getNextBucket(b); bytes[b.uid] = (bytes[b.uid] ?: 0L) + b.rxBytes + b.txBytes }
            st.close()
        } catch (_: Throwable) {}
    }
    val seen = HashSet<String>()
    bytes.entries.sortedByDescending { it.value }.mapNotNull { (uid, v) ->
        val pkg = (pm.getPackagesForUid(uid) ?: return@mapNotNull null).firstOrNull { it != ctx.packageName && pm.getLaunchIntentForPackage(it) != null } ?: return@mapNotNull null
        if (!seen.add(pkg)) return@mapNotNull null
        try { TopApp(pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString(), pkg, v) } catch (_: Throwable) { null }
    }.take(15)
}

/** Без доступа к статистике: популярные установленные приложения. */
suspend fun popularApps(ctx: Context): List<TopApp> = withContext(Dispatchers.IO) {
    val pm = ctx.packageManager
    listOf("org.telegram.messenger", "com.whatsapp", "com.google.android.youtube", "com.instagram.android", "com.android.chrome", "com.discord", "com.spotify.music", "com.zhiliaoapp.musically", "com.vkontakte.android", "ru.yandex.searchplugin", "ru.sberbankmobile", "ru.tinkoff.mobile.bank")
        .mapNotNull { p -> try { if (pm.getLaunchIntentForPackage(p) == null) null else TopApp(pm.getApplicationInfo(p, 0).loadLabel(pm).toString(), p, -1L) } catch (_: Throwable) { null } }
}

@Composable
fun RoutingWizard(vm: AppVm) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.screen, vm.introKind) {
        if (vm.screen == "main" && vm.introKind.isEmpty() && vm.wizard.isEmpty() && !Api.store.rtWiz) { Api.store.rtWiz = true; vm.wizard = "ask" }
    }
    fun finish() { vm.wizard = ""; vm.wizPicked = emptySet() }
    suspend fun load() { vm.wizTop = if (hasUsageAccess(ctx)) topApps(ctx) else emptyList(); if (vm.wizTop.isEmpty()) vm.wizTop = popularApps(ctx) }
    when (vm.wizard) {
        "ask" -> AlertDialog(onDismissRequest = { finish() },
            title = { Text("Настроить маршрутизацию?") },
            text = { Text("Для каждого приложения можно выбрать: идти напрямую, мимо VPN, или через выбранный сервер. Предложим приложения, которые больше всего тратят трафик. Всё можно изменить позже: Настройки → Маршрутизация.", fontSize = 14.sp) },
            confirmButton = { Button({ vm.wizard = if (hasUsageAccess(ctx)) "pick" else "perm" }) { Text("Продолжить") } },
            dismissButton = { TextButton({ finish() }) { Text("Пропустить") } })
        "perm" -> {
            var tried by remember { mutableStateOf(false) }
            AlertDialog(onDismissRequest = { finish() },
                title = { Text("Доступ к статистике трафика") },
                text = { Column {
                    Text("Чтобы предложить приложения по потраченному трафику, нужен «Доступ к истории использования» в настройках телефона. Приложение только читает суммарный трафик по приложениям и никуда его не отправляет.", fontSize = 14.sp)
                    if (tried) { Spacer(Modifier.height(8.dp)); Text("Доступ пока не выдан. Можно продолжить без статистики: покажем популярные приложения.", fontSize = 13.sp, color = Bad) }
                } },
                confirmButton = { Button({
                    if (hasUsageAccess(ctx)) vm.wizard = "pick" else if (!tried) {
                        tried = true
                        try { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: Exception) { try { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
                    } else vm.wizard = "pick"
                }) { Text(if (!tried) "Открыть настройки" else "Далее") } },
                dismissButton = { TextButton({ finish() }) { Text("Пропустить") } })
        }
        "pick" -> {
            LaunchedEffect(Unit) { load() }
            AlertDialog(onDismissRequest = { finish() },
                title = { Text("Часто используемые") },
                text = { Column {
                    Muted(if (vm.wizTop.any { it.bytes >= 0 }) "Больше всего трафика за 30 дней (данные системы). Отметьте нужные." else "Популярные приложения на этом телефоне. Отметьте нужные.", 13)
                    Spacer(Modifier.height(8.dp))
                    if (vm.wizTop.isEmpty()) Text("Подходящих приложений не найдено. Добавьте их вручную в Настройки → Маршрутизация.", fontSize = 13.sp)
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(vm.wizTop, key = { it.pkg }) { a ->
                            val on = a.pkg in vm.wizPicked
                            Row(Modifier.fillMaxWidth().clickable { vm.wizPicked = if (on) vm.wizPicked - a.pkg else vm.wizPicked + a.pkg }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                val ico by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.pkg) {
                                    value = withContext(Dispatchers.IO) { try { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Throwable) { null } }
                                }
                                val ib = ico
                                if (ib != null) Image(ib, null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))) else Spacer(Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) { Text(a.label, maxLines = 1); if (a.bytes >= 0) Muted(fmtBytes(a.bytes), 12) }
                                Checkbox(on, { vm.wizPicked = if (on) vm.wizPicked - a.pkg else vm.wizPicked + a.pkg })
                            }
                        }
                    }
                } },
                confirmButton = { Button({ if (vm.wizPicked.isEmpty()) vm.say("Отметьте хотя бы одно приложение") else vm.wizard = "dest" }) { Text("Далее") } },
                dismissButton = { TextButton({ finish() }) { Text("Пропустить") } })
        }
        "dest" -> {
            val names = vm.wizTop.filter { it.pkg in vm.wizPicked }.joinToString(", ") { it.label }
            RuleDialog(vm, DomRule(names), { finish() }, "Добавить", "Куда направлять трафик") { n ->
                val have = vm.routing.apps.map { it.value }.toSet()
                vm.changeRouting(vm.routing.copy(apps = vm.routing.apps + vm.wizPicked.filter { it !in have }.map { DomRule(it, n.action, n.servers) }))
                finish()
            }
        }
    }
}

@Composable
fun LogPage(vm: AppVm) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { text = withContext(Dispatchers.IO) { VpnSvc.report(ctx) }; kotlinx.coroutines.delay(2000) } }
    Column(Modifier.fillMaxSize()) {
        SubHead("Журнал") { vm.settingsPage = "main" }
        Muted("Версия, проверки и последние события sing-box и Xray. Ключи серверов не показываются.", 12)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState(), reverseScrolling = false)) {
            Text(text, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Button({
            val cb = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Mikaela VPN", text)); vm.say("Журнал скопирован")
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text("Скопировать") }
        Spacer(Modifier.height(8.dp))
    }
}
