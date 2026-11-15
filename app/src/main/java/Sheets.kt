@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package xyz.mikaelavpn.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun Sheets(vm: AppVm) {
    if (vm.sheet.isEmpty()) return
    val st = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet({ vm.sheet = "" }, sheetState = st) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
            when (vm.sheet) {
                "topup" -> TopupSheet(vm)
                "renew" -> RenewSheet(vm)
                "buydev" -> BuyDevSheet(vm)
                "tariff" -> TariffSheet(vm)
            }
        }
    }
}

@Composable
fun Chip(text: String, sel: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        colors = if (sel) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) else ButtonDefaults.outlinedButtonColors()) {
        Text(text, fontSize = 13.sp)
    }
}

@Composable
fun TariffSheet(vm: AppVm) {
    Text("Подписка", fontSize = 24.sp, fontWeight = FontWeight.Light)
    Spacer(Modifier.height(10.dp))
    val cur = Api.store.subId
    vm.tariffs.forEachIndexed { i, t ->
        val sel = t.id == cur || (cur.isNullOrEmpty() && i == 0)
        Row(Modifier.fillMaxWidth().clickable { vm.sheet = ""; if (!sel) vm.selectTariff(t.id) }.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(t.name); Muted((t.days?.let { "$it дн." } ?: "") + (if (t.status.isNotEmpty()) " · ${t.status}" else "")) }
            if (sel) Text("✓", color = Ok)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun TopupSheet(vm: AppVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var methods by remember { mutableStateOf<List<PayMethod>?>(null) }
    var err by remember { mutableStateOf("") }
    var m by remember { mutableStateOf<PayMethod?>(null) }
    var opt by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("300") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { methods = Api.methods(); m = methods?.firstOrNull(); opt = m?.options?.firstOrNull()?.first }
        catch (e: AuthExpired) { vm.sheet = ""; vm.expired() } catch (e: Exception) { err = e.message ?: "Ошибка"; methods = emptyList() }
    }
    Text("Пополнение баланса", fontSize = 24.sp, fontWeight = FontWeight.Light)
    vm.balance?.let { Muted("Сейчас: " + rub(it), 13) }
    Spacer(Modifier.height(12.dp))
    val ms = methods
    if (ms == null) CircularProgressIndicator(Modifier.padding(16.dp))
    else if (ms.isEmpty()) Text(err.ifEmpty { "Нет доступных способов оплаты" }, color = Bad)
    else {
        Muted("Способ оплаты", 13)
        FlowRowCompat { ms.forEach { x -> Chip(x.name, m?.id == x.id) { m = x; opt = x.options.firstOrNull()?.first } } }
        val cur = m
        if (cur != null && cur.options.isNotEmpty()) {
            Spacer(Modifier.height(10.dp)); Muted("Вариант", 13)
            FlowRowCompat { cur.options.forEach { (id, name) -> Chip(name, opt == id) { opt = id } } }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Сумма, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("150", "300", "500", "1000").forEach { a -> Chip("$a ₽", amount == a) { amount = a } } }
        val rubles = amount.toLongOrNull() ?: 0
        val min = (cur?.minKopeks ?: 0) / 100
        if (rubles in 1 until min) Text("Минимум $min ₽", color = Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (err.isNotEmpty()) Text(err, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        Button({
            val c = cur ?: return@Button
            busy = true; err = ""
            scope.launch {
                try {
                    val url = Api.topup(rubles * 100, c.id, opt)
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    vm.watchBalance(); vm.sheet = ""
                } catch (e: AuthExpired) { vm.sheet = ""; vm.expired() } catch (e: Exception) { err = e.message ?: "Ошибка" }
                busy = false
            }
        }, Modifier.fillMaxWidth().height(50.dp), enabled = !busy && cur != null && rubles >= maxOf(min, 1L), shape = RoundedCornerShape(12.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Перейти к оплате")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
fun RenewSheet(vm: AppVm) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Period>?>(null) }
    var err by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf<Period?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { list = Api.renewalOptions(); sel = list?.firstOrNull() }
        catch (e: AuthExpired) { vm.sheet = ""; vm.expired() } catch (e: Exception) { err = e.message ?: "Ошибка"; list = emptyList() }
    }
    Text("Продление подписки", fontSize = 24.sp, fontWeight = FontWeight.Light)
    vm.sub?.let { Muted(it.tariff, 13) }
    Spacer(Modifier.height(10.dp))
    val l = list
    if (l == null) CircularProgressIndicator(Modifier.padding(16.dp))
    else if (l.isEmpty()) Text(err.ifEmpty { "Продление недоступно" }, color = Bad)
    else {
        l.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { sel = p }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(sel?.days == p.days, { sel = p })
                Column(Modifier.weight(1f)) {
                    Text(p.label.ifEmpty { "${p.days} дн." })
                    if (p.disc > 0) Text("скидка ${p.disc}%", fontSize = 12.sp, color = Ok)
                }
                Text(p.price?.let { rub(it) } ?: p.text)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        val p = sel; val bal = vm.balance
        val short = p?.price != null && bal != null && bal < p.price
        Spacer(Modifier.height(10.dp))
        if (short) Text("Не хватает ${rub(p!!.price!! - bal!!)}", color = Warn, fontSize = 13.sp)
        if (err.isNotEmpty()) Text(err, color = Bad, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Button({
            if (short) { vm.sheet = "topup"; return@Button }
            val d = p?.days ?: return@Button
            busy = true; err = ""
            scope.launch {
                try { Api.renew(d); vm.say("Подписка продлена"); vm.sheet = ""; vm.reloadUser(); vm.refresh(true) }
                catch (e: AuthExpired) { vm.sheet = ""; vm.expired() } catch (e: Exception) { err = e.message ?: "Ошибка" }
                busy = false
            }
        }, Modifier.fillMaxWidth().height(50.dp), enabled = !busy && p != null, shape = RoundedCornerShape(12.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (short) "Пополнить баланс" else "Продлить" + (p?.price?.let { " за " + rub(it) } ?: ""))
        }
    }
}

@Composable
fun BuyDevSheet(vm: AppVm) {
    val scope = rememberCoroutineScope()
    var n by remember { mutableIntStateOf(1) }
    var price by remember { mutableStateOf<DevPrice?>(null) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(n) {
        price = null; err = ""
        try { price = Api.devicePrice(n) }
        catch (e: AuthExpired) { vm.sheet = ""; vm.expired() } catch (e: Exception) { err = e.message ?: "Ошибка" }
    }
    Text("Докупить устройства", fontSize = 24.sp, fontWeight = FontWeight.Light)
    vm.sub?.let { Muted("Сейчас в тарифе: ${it.deviceLimit}", 13) }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton({ if (n > 1) n-- }, shape = RoundedCornerShape(10.dp)) { Text("−") }
        Text(n.toString(), fontSize = 24.sp)
        val mx = price?.canAdd
        OutlinedButton({ if (mx == null || n < mx) n++ }, shape = RoundedCornerShape(10.dp)) { Text("+") }
    }
    Spacer(Modifier.height(12.dp))
    val p = price
    if (p != null) {
        if (p.blocked) Text("Для этого тарифа докупка устройств недоступна", color = Warn)
        else {
            p.total?.let { KV("К оплате", rub(it)) }
            p.days?.let { Muted("Действует $it дн. до конца подписки") }
            val bal = vm.balance
            if (p.total != null && bal != null && bal < p.total) Text("Не хватает ${rub(p.total - bal)}", color = Warn, fontSize = 13.sp)
        }
    } else if (err.isEmpty()) CircularProgressIndicator(Modifier.padding(8.dp))
    if (err.isNotEmpty()) Text(err, color = Bad, fontSize = 13.sp)
    Spacer(Modifier.height(12.dp))
    val short = p?.total != null && vm.balance != null && vm.balance!! < p.total
    Button({
        if (short) { vm.sheet = "topup"; return@Button }
        busy = true; err = ""
        scope.launch {
            try { Api.buyDevices(n); vm.say("Устройства добавлены"); vm.sheet = ""; vm.reloadUser(); vm.loadDevices() }
            catch (e: AuthExpired) { vm.sheet = ""; vm.expired() } catch (e: Exception) { err = e.message ?: "Ошибка" }
            busy = false
        }
    }, Modifier.fillMaxWidth().height(50.dp), enabled = !busy && p != null && !p.blocked, shape = RoundedCornerShape(12.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (short) "Пополнить баланс" else "Купить")
    }
}
