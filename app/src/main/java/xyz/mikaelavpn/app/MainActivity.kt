package xyz.mikaelavpn.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val ctx = LocalContext.current
    val force = vm.newVersion != null
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(vm.toast) { if (vm.toast.isNotEmpty()) { Toast.makeText(ctx, vm.toast, Toast.LENGTH_LONG).show(); vm.toast = "" } }
    LaunchedEffect(vm.servers.size, vm.screen) { vm.maybeAutoConnect(ctx) }
    BackHandler(enabled = vm.screen == "main" && vm.sheet.isEmpty() && vm.tab == 3 && vm.settingsPage != "main") {
        vm.settingsPage = if (vm.settingsPage == "apps" || vm.settingsPage == "domains") "routing" else "main"
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().then(if (force) Modifier.blur(18.dp) else Modifier)) {
            if (vm.screen == "login") LoginScreen(vm) else MainScreen(vm)
        }
        IntroDialog(vm)
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
fun Title(t: String, sub: String? = null) {
    Spacer(Modifier.height(18.dp))
    Text(t, fontSize = 34.sp, fontWeight = FontWeight.Light)
    if (sub != null) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    Column((if (onClick != null) m.clickable { onClick() } else m).padding(18.dp), content = content)
}

@Composable
fun Muted(t: String, size: Int = 12) = Text(t, fontSize = size.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun KV(k: String, v: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v)
    }
}

@Composable
fun Pick(value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, shape = RoundedCornerShape(10.dp)) { Text(options.firstOrNull { it.first == value }?.second ?: value) }
        DropdownMenu(open, { open = false }) {
            options.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { onPick(k); open = false }) }
        }
    }
}

@Composable
fun MainScreen(vm: AppVm) {
    val tabs = listOf("Главная", "Серверы", "Устройства", "Настройки")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(Modifier.fillMaxWidth().height(68.dp)) {
                    tabs.forEachIndexed { i, t ->
                        val sel = vm.tab == i
                        val c = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        Column(Modifier.weight(1f).fillMaxHeight().clickable {
                            vm.tab = i; if (i == 2) vm.loadDevices(); if (i != 3) vm.settingsPage = "main"
                        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Ico(i, c, 24.dp)
                            Spacer(Modifier.height(5.dp))
                            Text(t, fontSize = 11.sp, color = c, maxLines = 1, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 22.dp).statusBarsPadding().fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Brand()
                vm.balance?.let {
                    Text(rub(it) + "  +", modifier = Modifier.clip(RoundedCornerShape(99.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(99.dp))
                        .clickable { vm.sheet = "topup" }.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp)
                }
            }
            when (vm.tab) {
                0 -> Home(vm)
                1 -> Servers(vm)
                2 -> Devices(vm)
                else -> SettingsTab(vm)
            }
        }
        Sheets(vm)
    }
}
