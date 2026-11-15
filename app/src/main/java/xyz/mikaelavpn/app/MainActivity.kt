package xyz.mikaelavpn.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MikaelaTheme(vm.theme) { Root(vm) } }
    }
}

fun rub(k: Long) = "%.2f ₽".format(k / 100.0)

@Composable
fun Root(vm: AppVm) {
    val force = vm.newVersion != null
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().then(if (force) Modifier.blur(18.dp) else Modifier)) {
            if (vm.screen == "login") LoginScreen(vm) else MainScreen(vm)
        }
        if (force) UpdateDialog(vm.newVersion!!)
    }
}

@Composable
fun UpdateDialog(v: String) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Доступно обновление v$v") },
        text = { Text("Чтобы продолжить пользоваться приложением, обновите его до последней версии.") },
        confirmButton = {
            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Api.APK_URL))) }) { Text("Обновить") }
        },
    )
}

@Composable
fun Brand() = Text("Mikaela VPN", fontSize = 20.sp, fontWeight = FontWeight.Medium)

@Composable
fun LoginScreen(vm: AppVm) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp).statusBarsPadding(), verticalArrangement = Arrangement.Center) {
        Brand()
        Spacer(Modifier.height(28.dp))
        Text("Вход", fontSize = 34.sp, fontWeight = FontWeight.Light)
        Text("Аккаунт общий с ботом и кабинетом", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(16.dp))
        Button({ vm.login(email, pw) }, Modifier.fillMaxWidth().height(50.dp), enabled = !vm.busy, shape = RoundedCornerShape(12.dp)) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Войти")
        }
        if (vm.error.isNotEmpty()) Text(vm.error, color = Bad, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun MainScreen(vm: AppVm) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Главная", "Серверы", "Устройства", "Настройки")
    val icons = listOf("⌂", "◍", "▭", "≡")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; if (i == 2) vm.loadDevices() },
                        icon = { Text(icons[i], fontSize = 20.sp) }, label = { Text(t, fontSize = 11.sp) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 22.dp).statusBarsPadding().fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Brand()
                vm.balance?.let {
                    Text(rub(it), modifier = Modifier.clip(RoundedCornerShape(99.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(99.dp)).padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp)
                }
            }
            when (tab) {
                0 -> Home(vm)
                1 -> Servers(vm)
                2 -> Devices(vm)
                else -> SettingsTab(vm)
            }
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    Column((if (onClick != null) m.clickable { onClick() } else m).padding(18.dp), content = content)
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v)
    }
}

@Composable
fun Home(vm: AppVm) {
    val ctx = LocalContext.current
    val on by VpnSvc.state.collectAsStateWithLifecycle()
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) vm.start(ctx)
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val s = vm.sub
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("Главная", fontSize = 36.sp, fontWeight = FontWeight.Light)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Ok else MaterialTheme.colorScheme.onSurfaceVariant))
                Spacer(Modifier.width(8.dp))
                Text(if (on) "Подключено" + (vm.current?.let { " к ${it.label}" } ?: "") else if (vm.connecting) "Подключаю…" else "Отключено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(22.dp))
            Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(if (on) "Защита активна" else "Соединение остановлено", fontWeight = FontWeight.Medium)
                        Text("Нажмите для " + if (on) "отключения" else "подключения", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(58.dp).clip(CircleShape).border(1.dp, if (on) Ok else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable {
                            if (on) vm.stop(ctx) else {
                                val i = VpnService.prepare(ctx)
                                if (i != null) vpnLauncher.launch(i) else vm.start(ctx)
                            }
                        }, contentAlignment = Alignment.Center) {
                        Text("⏻", fontSize = 24.sp, color = if (on) Ok else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (vm.error.isNotEmpty()) Text(vm.error, color = Bad, modifier = Modifier.padding(top = 10.dp), fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Text("Текущая локация", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Card {
                val c = vm.current
                if (c == null) Text("—") else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.flag, fontSize = 22.sp); Spacer(Modifier.width(12.dp))
                    Column { Text(c.label); Text(c.kind, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (s != null) {
                KV("Тариф", s.tariff)
                KV("Осталось", if (s.expired) "истекла" else s.daysLeft?.let { "$it дн." } ?: "—")
                KV("Трафик", if (s.limitGb > 0) "%.1f / %.0f ГБ".format(s.usedGb, s.limitGb) else "%.1f ГБ / безлимит".format(s.usedGb))
                KV("Устройств (лимит)", s.deviceLimit.toString())
            } else if (vm.busy) CircularProgressIndicator(Modifier.padding(20.dp))
            OutlinedButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cabinet.mikaelavpn.xyz"))) },
                Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text("Продлить / пополнить в кабинете") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun Servers(vm: AppVm) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(18.dp))
        Text("Серверы", fontSize = 36.sp, fontWeight = FontWeight.Light)
        Text("Выберите сервер для подключения", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ vm.pingAll() }, shape = RoundedCornerShape(12.dp)) { Text("Тест пинга") }
            OutlinedButton({ vm.refresh() }, shape = RoundedCornerShape(12.dp), enabled = !vm.busy) { Text("Обновить") }
        }
        if (vm.error.isNotEmpty()) Text(vm.error, color = Bad, fontSize = 13.sp)
        LazyColumn {
            items(vm.servers, key = { it.tag }) { s ->
                val p = vm.pings[s.tag]
                val sel = s.tag == vm.current?.tag
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (sel) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable { vm.pick(s.tag) }.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.flag, fontSize = 24.sp); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(s.label); Text(s.kind, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val txt = when { p == null -> ""; p == -2 -> "UDP"; p < 0 -> "—"; else -> "$p мс" }
                    val col = when { p == null || p < 0 -> MaterialTheme.colorScheme.onSurfaceVariant; p < 120 -> Ok; p < 250 -> Warn; else -> Bad }
                    Text(txt, color = col, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun Devices(vm: AppVm) {
    LaunchedEffect(Unit) { vm.loadDevices() }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(18.dp))
        Text("Устройства", fontSize = 36.sp, fontWeight = FontWeight.Light)
        Text("Подключено ${vm.devices.size} из ${vm.sub?.deviceLimit ?: "∞"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        LazyColumn {
            items(vm.devices, key = { it.hwid }) { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title)
                        Text(d.platform + (if (d.platform.isNotEmpty()) " · " else "") + d.hwid.take(10) + "…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ vm.deleteDevice(d.hwid) }) { Text("Удалить", color = Bad) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun SettingsTab(vm: AppVm) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val names = mapOf("system" to "Как в системе", "light" to "Белая", "gray" to "Серая", "dark" to "Чёрная")
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(18.dp))
        Text("Настройки", fontSize = 36.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Тема", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton({ menu = true }, shape = RoundedCornerShape(10.dp)) { Text(names[vm.theme] ?: "") }
                DropdownMenu(menu, { menu = false }) {
                    names.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { vm.changeTheme(k); menu = false }) }
                }
            }
        }
        KV("Версия", "v" + BuildConfig.VERSION_NAME)
        Spacer(Modifier.height(10.dp))
        Text(Api.store.email ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedButton({ vm.logout(ctx) }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text("Выйти из аккаунта") }
    }
}
