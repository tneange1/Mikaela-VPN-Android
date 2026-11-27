package xyz.mikaelavpn.app

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun Home(vm: AppVm) {
    val ctx = LocalContext.current
    val on by VpnSvc.state.collectAsStateWithLifecycle()
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) vm.start(ctx)
    }
    val s = vm.sub
    val shape = RoundedCornerShape(12.dp)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Title("Главная")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Ok else MaterialTheme.colorScheme.onSurfaceVariant))
                Spacer(Modifier.width(8.dp))
                Text(if (on) "Подключено" + (vm.current?.let { " к ${it.label}" } ?: "") else if (vm.connecting) "Подключаю…" else "Отключено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(22.dp))
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(if (on) "Защита активна" else "Соединение остановлено", fontWeight = FontWeight.Medium)
                        Muted("Нажмите для " + if (on) "отключения" else "подключения")
                    }
                    Box(Modifier.size(58.dp).clip(CircleShape).border(1.dp, if (on) Ok else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable {
                            if (on) vm.stop(ctx) else {
                                val i = VpnService.prepare(ctx)
                                if (i != null) vpnLauncher.launch(i) else vm.start(ctx)
                            }
                        }, contentAlignment = Alignment.Center) {
                        PowerIcon(if (on) Ok else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (on && vm.routingDirty) {
                Spacer(Modifier.height(10.dp))
                Panel {
                    Text("Правила маршрутизации изменены", fontSize = 13.sp)
                    TextButton({ vm.reconnect(ctx) }) { Text("Переподключить") }
                }
            }
            if (vm.error.isNotEmpty()) Text(vm.error, color = Bad, modifier = Modifier.padding(top = 10.dp), fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Muted("Текущая локация", 13)
            Spacer(Modifier.height(8.dp))
            Panel(onClick = { vm.tab = 1 }) {
                val c = vm.current
                if (c == null) Text("—") else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.flag, fontSize = 22.sp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(c.label); Muted(c.kind) }
                    vm.pings[c.tag]?.let { p ->
                        if (p >= 0) Text("$p мс", fontSize = 13.sp, color = if (p < 120) Ok else if (p < 250) Warn else Bad)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (s != null) {
                KV("Тариф", s.tariff + if (vm.tariffs.size > 1) "  ›" else "", if (vm.tariffs.size > 1) ({ vm.sheet = "tariff" }) else null)
                KV("Осталось", if (s.expired) "истекла" else s.daysLeft?.let { "$it дн." } ?: "—")
                KV("Трафик", if (s.limitGb > 0) "%.1f / %.0f ГБ".format(s.usedGb, s.limitGb) else "%.1f ГБ / безлимит".format(s.usedGb))
                KV("Устройств (лимит)", s.deviceLimit.toString())
            } else if (vm.busy) CircularProgressIndicator(Modifier.padding(20.dp))
            if (vm.stale) Muted("Показан сохранённый список серверов")
            Spacer(Modifier.height(12.dp))
            Button({ vm.sheet = "renew" }, Modifier.fillMaxWidth().height(50.dp), shape = shape) { Text("Продлить подписку") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun ActionRow(icon: Int, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant)
        .border(1.dp, cs.outline, RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onClick() }.padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Ico(icon, cs.onSurface, 20.dp); Spacer(Modifier.width(14.dp)); Text(text, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Servers(vm: AppVm) {
    val ctx = LocalContext.current
    val on by VpnSvc.state.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Title("Серверы", "Нажмите — выбрать, свайп вправо — проверить пинг")
        val answered = vm.servers.count { (vm.pings[it.tag] ?: 0) > 0 }; val checked = vm.servers.count { vm.pings[it.tag] != null }
        Muted("Всего серверов: ${vm.servers.size}" + if (checked > 0) " · отвечают: $answered из $checked" else "", 13)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Поиск сервера") }, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(10.dp))
        ActionRow(4, "Тест пинга") { vm.pingAll() }
        if (vm.error.isNotEmpty()) Text(vm.error, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(10.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(vm.servers.filter { q.isBlank() || it.label.contains(q, true) || it.tag.contains(q, true) }, key = { it.tag }) { s ->
                val p = vm.pings[s.tag]
                val sel = s.tag == vm.current?.tag
                var dx by remember(s.tag) { mutableStateOf(0f) }
                val shift by animateFloatAsState(dx, label = "swipe")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
                    if (shift > 4f) Row(Modifier.matchParentSize().padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ico(4, Ok, 18.dp); Spacer(Modifier.width(8.dp)); Text("Пинг", color = Ok, fontSize = 14.sp)
                    }
                    Row(Modifier.fillMaxWidth().offset { IntOffset(shift.roundToInt(), 0) }.clip(RoundedCornerShape(12.dp))
                        .background(if (sel) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
                        .pointerInput(s.tag) {
                            detectHorizontalDragGestures(
                                onDragEnd = { if (dx > 150f) vm.pingOne(s.tag); dx = 0f },
                                onDragCancel = { dx = 0f },
                            ) { change, d -> change.consume(); dx = (dx + d).coerceIn(0f, 240f) }
                        }
                        .clickable { if (!sel) { vm.pick(s.tag); if (on) vm.reconnect(ctx) } }
                        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) { Text(s.flag, fontSize = 24.sp) }
                        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            Text(s.label, fontSize = 16.sp, maxLines = 1)
                            Text(s.kind, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        val txt = when { p == null -> ""; p == -2 -> ""; p < 0 -> "—"; else -> "$p мс" }
                        val col = when { p == null || p < 0 -> MaterialTheme.colorScheme.onSurfaceVariant; p < 120 -> Ok; p < 250 -> Warn; else -> Bad }
                        Text(txt, color = col, fontSize = 13.sp)
                        if (sel) { Spacer(Modifier.width(10.dp)); Box(Modifier.size(7.dp).clip(CircleShape).background(Ok)) }
                    }
                }
            }
        }
    }
}

@Composable
fun Devices(vm: AppVm) {
    LaunchedEffect(Unit) { vm.loadDevices() }
    val d = vm.devices
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Сбросить все устройства?") },
        text = { Text("Все привязанные устройства будут удалены из подписки.") },
        confirmButton = { TextButton({ confirm = false; vm.resetDevices() }) { Text("Сбросить", color = Bad) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } },
    )
    Column(Modifier.fillMaxSize()) {
        Title("Устройства", if (d != null) "Подключено ${d.count} из ${d.limit ?: "∞"}" else "Загрузка…")
        Spacer(Modifier.height(14.dp))
        ActionRow(2, "Докупить устройства") { vm.sheet = "buydev" }
        if (d != null && d.list.isNotEmpty()) { Spacer(Modifier.height(10.dp)); ActionRow(5, "Сбросить все") { confirm = true } }
        if (vm.devErr.isNotEmpty()) Text(vm.devErr, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(d?.list ?: emptyList(), key = { it.hwid }) { x ->
                val phone = x.platform.contains("android", true) || x.platform.contains("ios", true) || x.platform.contains("iphone", true)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant)
                    .border(1.dp, cs.outline, RoundedCornerShape(14.dp)).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Ico(if (phone) 6 else 2, cs.onSurfaceVariant, 22.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(x.title.ifEmpty { x.platform.ifEmpty { "Устройство" } }, fontSize = 15.sp, maxLines = 1)
                        Text(
                            (if (x.platform.isNotEmpty()) x.platform + " · " else "") + x.hwid.take(8) + "…" + (if (x.self) "  ·  это устройство" else ""),
                            fontSize = 12.sp, color = if (x.self) Ok else cs.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    TextButton({ vm.deleteDevice(x.hwid) }) { Text("Удалить", color = Bad, fontSize = 13.sp) }
                }
            }
        }
    }
}

// ---------------- настройки ----------------

@Composable
fun NavRow(title: String, value: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title)
        Row(verticalAlignment = Alignment.CenterVertically) { if (value.isNotEmpty()) Muted(value, 13); Spacer(Modifier.width(8.dp)); Muted("›", 18) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
fun SwitchRow(title: String, sub: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title); Muted(sub) }
        Switch(v, onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
fun SettingsTab(vm: AppVm) {
    when (vm.settingsPage) {
        "adv" -> AdvPage(vm)
        "routing" -> RoutingPage(vm)
        "apps" -> AppsPage(vm)
        "domains" -> DomainsPage(vm)
        else -> SettingsMain(vm)
    }
}

@Composable
fun SubHead(title: String, back: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    TextButton(back, contentPadding = PaddingValues(0.dp)) { Text("‹ Назад") }
    Text(title, fontSize = 28.sp, fontWeight = FontWeight.Light)
    Spacer(Modifier.height(8.dp))
}

@Composable
fun SettingsMain(vm: AppVm) {
    val ctx = LocalContext.current
    val themes = listOf("system" to "Как в системе", "light" to "Белая", "gray" to "Серая", "dark" to "Чёрная")
    Column(Modifier.fillMaxSize()) {
        Title("Настройки")
        Spacer(Modifier.height(12.dp))
        NavRow("Дополнительные параметры") { vm.settingsPage = "adv" }
        NavRow("Маршрутизация", (vm.routing.apps.size + vm.routing.rules.size).let { if (it > 0) "$it" else "" }) { vm.settingsPage = "routing" }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Тема"); Pick(vm.theme, themes) { vm.changeTheme(it) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        TextButton({ vm.openGuide() }, contentPadding = PaddingValues(0.dp)) { Text("Показать инструкцию") }
        Spacer(Modifier.height(10.dp))
        Muted(Api.store.email ?: "", 13)
        Spacer(Modifier.height(6.dp))
        OutlinedButton({ vm.logout(ctx) }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text("Выйти из аккаунта") }
    }
}

@Composable
fun AdvPage(vm: AppVm) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        SubHead("Дополнительные параметры") { vm.settingsPage = "main" }
        SwitchRow("Автоподключение", "Подключаться при открытии приложения", vm.autoConnect) { vm.changeAuto(it) }
        SwitchRow("Запуск при включении телефона", "Подключаться после перезагрузки (если выдано разрешение VPN)", vm.boot) { vm.changeBoot(it) }
        Spacer(Modifier.height(14.dp))
        Panel {
            Text("Диагностика")
            Spacer(Modifier.height(4.dp))
            Muted("Если что-то не работает, нажмите и отправьте скопированный текст в поддержку.", 12)
            TextButton({
                val cb = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cb.setPrimaryClip(android.content.ClipData.newPlainText("Mikaela VPN", VpnSvc.report(ctx)))
                vm.say("Журнал скопирован")
            }, contentPadding = PaddingValues(0.dp)) { Text("Скопировать журнал") }
        }
        Spacer(Modifier.height(14.dp))
        Panel {
            Text("Блокировка интернета без VPN")
            Spacer(Modifier.height(4.dp))
            Muted("В системных настройках VPN включите «Постоянный VPN» и «Блокировать подключения без VPN» для Mikaela VPN.")
            TextButton({ ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, contentPadding = PaddingValues(0.dp)) {
                Text("Открыть настройки VPN")
            }
        }
    }
}

@Composable
fun RoutingPage(vm: AppVm) {
    val r = vm.routing
    Column(Modifier.fillMaxSize()) {
        SubHead("Маршрутизация") { vm.settingsPage = "main" }
        Muted("Домены можно пустить напрямую, минуя VPN, или всегда через выбранные серверы. Если такой сервер не отвечает, соединение блокируется. Для приложений тоже можно выбрать: мимо VPN или через выбранные серверы (нужен Android 10 и новее).", 13)
        Spacer(Modifier.height(10.dp))
        NavRow("Маршрутизация приложений", if (r.apps.isEmpty()) "" else r.apps.size.toString()) { vm.settingsPage = "apps" }
        NavRow("Маршрутизация доменов", if (r.rules.isEmpty()) "" else r.rules.size.toString()) { vm.settingsPage = "domains" }
    }
}

data class AppInfo(val label: String, val pkg: String)

@Composable
fun AppsPage(vm: AppVm) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<AppInfo>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0).filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != ctx.packageName }
                .map { AppInfo(it.loadLabel(pm).toString(), it.packageName) }.sortedBy { it.label.lowercase() }
        }
    }
    val r = vm.routing
    var edit by remember { mutableStateOf<AppInfo?>(null) }
    fun status(pkg: String): String? = r.apps.firstOrNull { it.value == pkg }?.let { d ->
        if (d.action == "server") "→ " + d.servers.joinToString(", ") { tg -> vm.servers.firstOrNull { it.tag == tg }?.label ?: "$tg (нет в списке)" } else "Напрямую (мимо VPN)"
    }
    Column(Modifier.fillMaxSize()) {
        SubHead("Приложения") { vm.settingsPage = "routing" }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Поиск") }, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(8.dp))
        val list = all
        if (list == null) CircularProgressIndicator(Modifier.padding(20.dp)) else if (list.isEmpty()) Text("Не удалось получить список приложений. Разрешите приложению доступ к списку приложений в настройках телефона.", color = Bad, fontSize = 13.sp) else {
            val set = r.apps.map { it.value }.toSet()
            val f = list.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
                .sortedByDescending { it.pkg in set }
            LazyColumn {
                items(f, key = { it.pkg }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { edit = a }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val ico by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.pkg) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Throwable) { null }
                            }
                        }
                        val ib = ico
                        if (ib != null) Image(ib, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) else Spacer(Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, maxLines = 1)
                            val st = status(a.pkg)
                            if (st != null) Muted(st, 12) else Muted(a.pkg, 12)
                        }
                    }
                }
            }
        }
    }
    edit?.let { a ->
        val cur = r.apps.firstOrNull { it.value == a.pkg }
        RuleDialog(vm, cur ?: DomRule(a.pkg), { edit = null }, if (cur == null) "Добавить" else "Сохранить", a.label,
            if (cur == null) null else ({ vm.changeRouting(vm.routing.copy(apps = vm.routing.apps.filter { it.value != a.pkg })); edit = null })) { n ->
            val rest = vm.routing.apps.filter { it.value != a.pkg }
            vm.changeRouting(vm.routing.copy(apps = rest + n)); edit = null
        }
    }
}

@Composable
fun DomainsPage(vm: AppVm) {
    var t by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<DomRule?>(null) }
    var pending by remember { mutableStateOf<List<String>?>(null) }
    val r = vm.routing
    fun add() {
        val have = r.rules.map { it.value }.toMutableSet(); val add = ArrayList<String>()
        for (p in t.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }) { val d = Api.normDomain(p); if (d.isNotEmpty() && have.add(d)) add.add(d) }
        if (add.isNotEmpty()) pending = add else vm.say("Неверный домен или он уже есть в списке")
    }
    Column(Modifier.fillMaxSize()) {
        SubHead("Домены") { vm.settingsPage = "routing" }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(t, { t = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("example.com или .ru") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.width(8.dp))
            Button({ add() }, enabled = t.isNotBlank()) { Text("Добавить") }
        }
        Muted("Домен example.com включает поддомены. Доменная зона начинается с точки: .ru, .su, .рф. Можно сразу несколько через пробел или запятую. После ввода приложение спросит, куда направлять трафик: напрямую или на серверы.", 12)
        LazyColumn {
            items(r.rules, key = { it.value }) { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.value)
                        Muted(if (d.action == "server") "→ " + d.servers.joinToString(", ") { tg -> vm.servers.firstOrNull { it.tag == tg }?.label ?: "$tg (нет в списке)" } else "Напрямую (мимо VPN)", 12)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { edit = d }, contentAlignment = Alignment.Center) { Ico(3, MaterialTheme.colorScheme.onSurfaceVariant, 20.dp) }
                    TextButton({ vm.changeRouting(r.copy(rules = r.rules - d)) }) { Text("Убрать", color = Bad) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    pending?.let { ds ->
        RuleDialog(vm, DomRule(ds.joinToString(", ")), { pending = null }, "Добавить") { n ->
            vm.changeRouting(vm.routing.copy(rules = vm.routing.rules + ds.map { DomRule(it, n.action, n.servers) }))
            pending = null; t = ""
        }
    }
    edit?.let { d -> RuleDialog(vm, d, { edit = null }) { n -> vm.changeRouting(vm.routing.copy(rules = vm.routing.rules.map { if (it.value == d.value) n else it })); edit = null } }
}

@Composable
fun RuleDialog(vm: AppVm, d: DomRule, onClose: () -> Unit, saveText: String = "Сохранить", title: String = d.value, onRemove: (() -> Unit)? = null, onSave: (DomRule) -> Unit) {
    var act by remember { mutableStateOf(d.action) }
    var sel by remember { mutableStateOf(d.servers.toSet()) }
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                Muted("Куда направлять трафик", 13)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Напрямую", act == "direct", Modifier.weight(1f)) { act = "direct" }
                    Chip("На серверы", act == "server", Modifier.weight(1f)) { act = "server" }
                }
                if (onRemove != null) TextButton(onRemove) { Text("Убрать из списка", color = Bad) }
                if (act == "direct") { Spacer(Modifier.height(8.dp)); Muted("Обход туннеля: трафик идёт мимо VPN.", 12) }
                else {
                    Spacer(Modifier.height(8.dp)); Muted("Выберите один или несколько серверов. Если выбрано несколько, трафик распределяется между ними.", 12)
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, placeholder = { Text("Поиск сервера") }, shape = RoundedCornerShape(12.dp))
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(vm.servers.filter { q.isBlank() || it.label.contains(q, true) || it.tag.contains(q, true) }, key = { it.tag }) { s ->
                            val on = s.tag in sel
                            Row(Modifier.fillMaxWidth().clickable { sel = if (on) sel - s.tag else sel + s.tag }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.flag, fontSize = 20.sp); Spacer(Modifier.width(8.dp)); Text(s.label, Modifier.weight(1f), maxLines = 1)
                                Checkbox(on, { sel = if (on) sel - s.tag else sel + s.tag })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button({ if (act == "server" && sel.isEmpty()) vm.say("Выберите хотя бы один сервер") else onSave(DomRule(d.value, act, if (act == "server") vm.servers.map { it.tag }.filter { it in sel } else emptyList())) }) { Text(saveText) }
        },
        dismissButton = { OutlinedButton(onClose) { Text("Отмена") } },
    )
}
