// Нютаг. «Устройства», как DevicesView на iOS: где сейчас открыт аккаунт. Своё устройство первым и с пометкой;
// по нажатию любое можно переименовать, чужое — отключить, подтвердив паролем. Своё отключается обычным выходом из меню.
// Значки рисуем сами: набора иконок в зависимостях нет.

package chat.nyutag.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.AccountDevice
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.session.AppSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(session: AppSession, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<AccountDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<AccountDevice?>(null) }
    var renaming by remember { mutableStateOf<AccountDevice?>(null) }
    var signingOut by remember { mutableStateOf<AccountDevice?>(null) }
    val mine = session.deviceID

    suspend fun load() {
        loading = true
        try {
            devices = session.devices()
            failure = null
            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "устройства ${devices.size}, своё первым: ${devices.firstOrNull()?.deviceID == mine}")
        } catch (e: AppSession.SignInException) {
            failure = words(if (e.failure == AppSession.SignInFailure.NO_NETWORK) P.noNetwork else P.devicesFailed)
        }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    signingOut?.let { device ->
        DeviceSignOutScreen(session, device, onClose = { signingOut = null }) { signingOut = null; scope.launch { load() } }
        return
    }
    BackHandler(onBack = onClose)
    FormPage(words(P.devices), onClose, closeTitle = words(P.done)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (devices.isNotEmpty()) FormCard {
                devices.forEachIndexed { index, device ->
                    if (index > 0) FormDivider()
                    DeviceRow(device, device.deviceID == mine) { chosen = device }
                }
            }
            if (loading && devices.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp).size(28.dp), color = palette.sky, strokeWidth = 2.dp)
            }
            Text(words(P.devicesLead), color = palette.quiet, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        }
        failure?.let { FailureNote(it) }
    }

    chosen?.let { device ->
        // Меню устройства: на iOS это свайп и долгое нажатие, здесь — нажатие на строку.
        AlertDialog(onDismissRequest = { chosen = null }, containerColor = palette.card,
                    title = { Text(name(device, words), color = palette.ink, fontSize = 17.sp) },
                    text = {
                        Column {
                            Text(words(P.renameDevice), color = palette.sky, fontSize = 17.sp,
                                 modifier = Modifier.fillMaxWidth().clickable { chosen = null; renaming = device }.padding(vertical = 12.dp))
                            if (device.deviceID != mine) {
                                Text(words(P.signOutDevice), color = Danger, fontSize = 17.sp,
                                     modifier = Modifier.fillMaxWidth().clickable { chosen = null; signingOut = device }.padding(vertical = 12.dp))
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton({ chosen = null }) { Text(words(P.cancel), color = palette.sky) } })
    }
    renaming?.let { device ->
        var newName by remember(device) { mutableStateOf(device.displayName.orEmpty()) }
        AlertDialog(onDismissRequest = { renaming = null }, containerColor = palette.card,
                    title = { Text(words(P.renameDevice), color = palette.ink, fontSize = 17.sp) },
                    text = { FormField(newName, { newName = it }, words(P.unnamedDevice), keyboard = KeyboardOptions(imeAction = ImeAction.Done)) },
                    confirmButton = {
                        TextButton({
                            val name = newName.trim()
                            renaming = null
                            if (name.isNotEmpty()) scope.launch {
                                if (session.renameDevice(device.deviceID, name)) {
                                    if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "устройство ${device.deviceID} переименовано")
                                    load()
                                } else failure = words(P.devicesFailed)
                            }
                        }) { Text(words(P.save), color = palette.sky, fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = { TextButton({ renaming = null }) { Text(words(P.cancel), color = palette.sky) } })
    }
}

private val PHONE_NAMES = listOf("Android", "iPhone", "iPad")

@Composable
private fun DeviceRow(device: AccountDevice, mine: Boolean, onClick: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Телефоны узнаём по имени, которое ставят наши приложения («Нютаг Android», «Нютаг iPhone»).
        val handheld = mine || PHONE_NAMES.any { device.displayName.orEmpty().contains(it, ignoreCase = true) }
        Canvas(Modifier.size(30.dp, 26.dp)) { if (handheld) phone(if (mine) palette.sky else palette.quiet) else laptop(palette.quiet) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name(device, words), color = palette.ink, fontSize = 17.sp)
            if (mine) {
                Text(words(P.thisDevice), color = palette.sky, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            } else device.lastSeenTS?.let { ts ->
                val seen = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(words.locale)
                    .format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
                Text(words(P.lastSeen, seen), color = palette.quiet, fontSize = 12.sp)
            }
            // Через дверь сервер видит адрес входа, а не телефона — такой адрес ничего не говорит, не показываем.
            Text(listOfNotNull(device.deviceID, device.lastSeenIP?.takeIf { it != "127.0.0.1" }).joinToString(" · "),
                 color = palette.quiet, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
        }
    }
}

private fun name(device: AccountDevice, words: Words): String =
    device.displayName?.takeIf { it.isNotEmpty() } ?: words(P.unnamedDevice)

/** Отключение чужого устройства: что будет, пароль, красная кнопка. */
@Composable
private fun DeviceSignOutScreen(session: AppSession, device: AccountDevice, onClose: () -> Unit, onDone: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    FormPage(words(P.devices), onClose) {
        Text(device.displayName ?: device.deviceID, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(words(P.signOutDeviceLead), color = palette.ink, fontSize = 16.sp)
        Text(words(P.deleteAccountPassword), color = palette.quiet, fontSize = 13.sp)
        FormCard {
            FormField(password, { password = it }, words(P.password), secret = true,
                      keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
        }
        failure?.let { FailureNote(it) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Danger.copy(alpha = if (busy || password.isEmpty()) 0.45f else 1f))
                .clickable(enabled = !busy && password.isNotEmpty()) {
                    busy = true
                    failure = null
                    scope.launch {
                        try {
                            session.signOutDevice(device.deviceID, password)
                            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "вышли на устройстве ${device.deviceID}")
                            onDone()
                        } catch (e: AppSession.SignInException) {
                            failure = words(when (e.failure) {
                                AppSession.SignInFailure.WRONG_PASSWORD -> P.wrongPassword
                                AppSession.SignInFailure.NO_NETWORK -> P.noNetwork
                                else -> P.devicesFailed
                            })
                            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "выход на устройстве отклонён: ${e.failure}")
                            busy = false
                        }
                    }
                }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Text(words(P.signOutDevice), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.padding(4.dp))
    }
}

/** Телефон: корпус и динамик сверху. */
private fun DrawScope.phone(color: Color) {
    val h = size.height
    val stroke = Stroke(width = 1.8.dp.toPx())
    drawRoundRect(color, Offset(size.width / 2 - h * 0.27f, h * 0.04f), Size(h * 0.54f, h * 0.92f), CornerRadius(h * 0.1f), style = stroke)
    drawLine(color, Offset(size.width / 2 - h * 0.08f, h * 0.16f), Offset(size.width / 2 + h * 0.08f, h * 0.16f), 1.8.dp.toPx())
}

/** Ноутбук: экран и основание. */
private fun DrawScope.laptop(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.8.dp.toPx())
    drawRoundRect(color, Offset(w * 0.14f, h * 0.12f), Size(w * 0.72f, h * 0.58f), CornerRadius(h * 0.06f), style = stroke)
    drawLine(color, Offset(w * 0.02f, h * 0.84f), Offset(w * 0.98f, h * 0.84f), 2.2.dp.toPx())
}
