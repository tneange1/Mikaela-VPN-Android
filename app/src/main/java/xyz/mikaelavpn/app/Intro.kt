package xyz.mikaelavpn.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

data class Notes(val add: List<String> = emptyList(), val chg: List<String> = emptyList(), val fix: List<String> = emptyList(), val rm: List<String> = emptyList())

/** Что изменилось в версии. Для каждой новой версии добавляйте запись: окно покажется один раз после обновления. */
val NOTES = mapOf(
    "1.7.0" to Notes(
        add = listOf("Маршрутизация приложений на выбранный сервер или напрямую, иконки приложений, выбор «куда» перед сохранением правила"),
        chg = listOf("Новое ядро sing-box вместо старого туннеля, Диагностика с копированием журнала (Дополнительные параметры). QUIC отключён: сайты открываются через TCP"),
    ),
    "1.6.0" to Notes(
        add = listOf("Выбор канала обновлений (Дополнительные параметры): Stable, PTB, Dev"),
        chg = listOf("Dev-сборка: туннель на sing-box, маршрутизация приложений на выбранные серверы (Android 10+). Возможны сбои"),
    ),
    "1.4.0" to Notes(
        add = listOf("Обновление прямо в приложении: новая версия скачивается сама в фоне, остаётся нажать «Установить». Скачивать APK через браузер больше не нужно"),
        chg = listOf("Если VPN был включён, после обновления он включается сам"),
    ),
    "1.3.1" to Notes(
        add = listOf("Поиск по названию на вкладке «Серверы» и при выборе серверов для правила"),
    ),
    "1.3.0" to Notes(
        add = listOf(
            "Правила для доменов: напрямую (мимо VPN) или на выбранные серверы, настройка по кнопке справа от домена. Если сервер не отвечает, соединение блокируется",
            "Доменные зоны: .ru, .su, .рф и другие",
        ),
        chg = listOf(
            "«Предварительная маршрутизация» объединена с «Маршрутизацией»",
            "Тест пинга снова проверяет все серверы сразу, результат появляется у каждого по мере ответа",
            "Серверы обновляются раз в минуту, обновление приложения предлагается сразу, как только готов новый релиз",
        ),
        rm = listOf("Кнопка «Обновить» на вкладке «Серверы»", "Режим маршрутизации «Только выбранные»", "Настройка интервала обновления серверов и строка версии в настройках"),
    ),
    "1.2.0" to Notes(
        add = listOf(
            "Предварительная маршрутизация (Настройки → Дополнительные параметры): домены всегда идут через выбранный сервер, независимо от обычной маршрутизации. Если сервер не отвечает, соединение блокируется",
            "На вкладке «Серверы» показано общее число серверов и сколько из них отвечает",
        ),
        chg = listOf(
            "Пинг отдельного сервера теперь по свайпу вправо вместо долгого нажатия",
            "Тест пинга проверяет серверы по очереди и сразу показывает результат каждого",
        ),
    ),
    "1.1.0" to Notes(
        add = listOf(
            "Продление подписки и пополнение баланса прямо в приложении",
            "Докупка устройств, выбор тарифа, если их несколько",
            "Маршрутизация по приложениям и доменам",
            "Автоподключение и запуск после перезагрузки телефона",
            "Вход через Telegram и другие способы, регистрация и восстановление пароля",
            "Пинг отдельного сервера",
            "Окно «Что нового» и инструкция при первом запуске",
        ),
        chg = listOf("Новый вид серверов, устройств и нижней панели", "Единый вид кнопок"),
        fix = listOf("Список приложений в маршрутизации теперь отображается"),
    ),
)

class Section(val title: String?, val items: List<String>)
class Step(val title: String, val sections: List<Section>)

val GUIDE = listOf(
    Step("Главная", listOf(Section(null, listOf(
        "Большая кнопка включает и отключает VPN",
        "Сумма сверху — ваш баланс, нажмите на неё, чтобы пополнить",
        "«Продлить подписку» продлевает тариф, если тарифов несколько, их можно переключить",
    )))),
    Step("Серверы", listOf(Section(null, listOf(
        "Нажмите на сервер, чтобы выбрать его",
        "«Тест пинга» проверяет все серверы, а свайп вправо по серверу — только его",
        "«Автовыбор» сам берёт лучший доступный сервер",
    )))),
    Step("Устройства", listOf(Section(null, listOf(
        "Здесь видно, какие устройства привязаны к подписке",
        "Лишнее можно удалить, а если лимит мал — докупить устройства",
    )))),
    Step("Настройки", listOf(Section(null, listOf(
        "Дополнительные параметры: автоподключение, запуск после перезагрузки, блокировка интернета без VPN (системная настройка Android)",
        "Маршрутизация: выбранные приложения идут мимо VPN, а для доменов и зон (.ru, .su, .рф) можно выбрать: напрямую или на конкретные серверы",
        "Тема приложения",
    )))),
    Step("Обновления", listOf(Section(null, listOf(
        "Приложение само проверяет новые версии",
        "Когда выйдет обновление, появится окно с кнопкой «Обновить»; после скачивания подтвердите установку",
    )))),
)

fun notesSteps(v: String): List<Step> {
    val n = NOTES[v] ?: return emptyList()
    val s = ArrayList<Section>()
    if (n.add.isNotEmpty()) s.add(Section("Добавлено", n.add))
    if (n.chg.isNotEmpty()) s.add(Section("Изменено", n.chg))
    if (n.fix.isNotEmpty()) s.add(Section("Исправлено", n.fix))
    if (n.rm.isNotEmpty()) s.add(Section("Убрано", n.rm))
    return listOf(Step("Что нового в v$v", s))
}

@Composable
fun IntroDialog(vm: AppVm) {
    if (vm.introKind.isEmpty()) return
    val steps = if (vm.introKind == "guide") GUIDE else notesSteps(BuildConfig.VERSION_NAME)
    if (steps.isEmpty()) { vm.closeIntro(); return }
    val i = vm.introStep.coerceIn(0, steps.size - 1); val st = steps[i]; val last = i == steps.size - 1
    val shape = RoundedCornerShape(20.dp)
    Dialog(onDismissRequest = { vm.closeIntro() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(shape).background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(24.dp)) {
            Text(st.title, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                st.sections.forEach { sec ->
                    if (sec.title != null) { Spacer(Modifier.height(6.dp)); Muted(sec.title.uppercase(), 11); Spacer(Modifier.height(4.dp)) }
                    sec.items.forEach { Row(Modifier.padding(vertical = 3.dp)) { Muted("•  ", 14); Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 21.sp) } }
                }
            }
            if (steps.size > 1) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    steps.indices.forEach { k ->
                        Box(Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape)
                            .background(if (k == i) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (steps.size > 1 && !last) OutlinedButton({ vm.closeIntro() }, Modifier.weight(1f)) { Text("Пропустить") }
                Button({ if (last) vm.closeIntro() else vm.introStep = i + 1 }, Modifier.weight(1f)) { Text(if (last) "Готово" else "Далее") }
            }
        }
    }
}
