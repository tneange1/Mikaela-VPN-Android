package xyz.mikaelavpn.app

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener

private val SCAN_JS = """(function(){var a=null,r=null;
function scan(o){if(!o||typeof o!=='object')return;for(var k in o){var v=o[k];if(typeof v==='string'){var l=k.toLowerCase();
if(l==='accesstoken'||l==='access_token')a=v;if(l==='refreshtoken'||l==='refresh_token')r=v;}else scan(v);}}
try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i),v=localStorage.getItem(k),l=k.toLowerCase();
if(v&&v.charAt(0)!=='{'&&v.charAt(0)!=='['){var c=v.replace(/^"|"${'$'}/g,'');
if(l.indexOf('access')>=0&&l.indexOf('token')>=0)a=c;if(l.indexOf('refresh')>=0&&l.indexOf('token')>=0)r=c;}
try{scan(JSON.parse(v))}catch(e){}}}catch(e){}
return JSON.stringify({a:a,r:r});})()"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLogin(onDone: (String, String) -> Unit, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        var n = 0
        while (true) {
            delay(1500)
            w.evaluateJavascript(SCAN_JS) { res ->
                try {
                    val str = JSONTokener(res).nextValue() as? String ?: return@evaluateJavascript
                    val o = JSONObject(str)
                    val a = o.optString("a").takeIf { it.isNotEmpty() && it != "null" }
                    val r = o.optString("r").takeIf { it.isNotEmpty() && it != "null" }
                    if (a != null && r != null) onDone(a, r)
                } catch (_: Exception) {}
            }
            n++
        }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Вход через кабинет", Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
                    TextButton(onClose) { Text("Закрыть") }
                }
                AndroidView(factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google и др. не пускают в «wv»-браузеры
                        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = WebViewClient()
                        loadUrl(Api.BASE + "/login")
                        web = this
                    }
                }, modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() })
            }
        }
    }
}

@Composable
fun LoginScreen(vm: AppVm) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf("login") }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadOpts() }
    val o = vm.opts
    val shape = RoundedCornerShape(12.dp)

    if (web) WebLogin({ a, r -> web = false; vm.webLogin(a, r) }, { web = false })

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(24.dp))
        Brand()
        Spacer(Modifier.height(28.dp))
        Text(if (mode == "login") "Вход" else if (mode == "reg") "Регистрация" else "Сброс пароля", fontSize = 34.sp, fontWeight = FontWeight.Light)
        Text("Аккаунт общий с ботом и кабинетом", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        if (o.email) {
            if (mode == "reg") {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Имя (необязательно)") }, shape = shape)
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = shape)
            if (mode != "forgot") {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пароль") },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton({ show = !show }) { Text(if (show) "скрыть" else "показать", fontSize = 12.sp) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = shape)
            }
            Spacer(Modifier.height(16.dp))
            Button({
                when (mode) { "login" -> vm.login(email, pw); "reg" -> vm.register(email, pw, name); else -> vm.forgot(email) }
            }, Modifier.fillMaxWidth().height(50.dp), enabled = !vm.busy && email.isNotBlank() && (mode == "forgot" || pw.isNotEmpty()), shape = shape) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                else Text(if (mode == "login") "Войти" else if (mode == "reg") "Зарегистрироваться" else "Отправить письмо")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (mode == "login") {
                    if (o.reg) TextButton({ mode = "reg"; vm.error = ""; vm.info = "" }) { Text("Регистрация", fontSize = 13.sp) }
                    TextButton({ mode = "forgot"; vm.error = ""; vm.info = "" }) { Text("Забыли пароль?", fontSize = 13.sp) }
                } else TextButton({ mode = "login"; vm.error = ""; vm.info = "" }) { Text("← Ко входу", fontSize = 13.sp) }
            }
        }
        if (vm.error.isNotEmpty()) Text(vm.error, color = Bad, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp)
        if (vm.info.isNotEmpty()) Text(vm.info, color = Ok, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp)

        if (mode == "login" && (o.telegram || o.oauth.isNotEmpty())) {
            Spacer(Modifier.height(16.dp))
            if (o.email) Text("или", Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            if (o.telegram) {
                if (vm.tg != null) {
                    OutlinedButton({ vm.cancelTg() }, Modifier.fillMaxWidth().height(50.dp), shape = shape) { Text("Ждём подтверждения в Telegram… (отмена)") }
                } else OutlinedButton({ vm.startTg(ctx) }, Modifier.fillMaxWidth().height(50.dp), shape = shape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text("Войти через Telegram") }
                Spacer(Modifier.height(8.dp))
            }
            o.oauth.forEach { (_, title) ->
                OutlinedButton({ web = true }, Modifier.fillMaxWidth().height(50.dp), shape = shape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text("Войти через $title") }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
