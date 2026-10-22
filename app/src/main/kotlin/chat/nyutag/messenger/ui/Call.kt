// Нютаг. Звонок на экране: поверх приложения — входящий с «Принять/Отклонить», исходящий и разговор с таймером;
// свёрнутый звонок — полоска сверху, по нажатию экран возвращается. Значки нарисованы здесь же, без библиотек.

package chat.nyutag.messenger.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.calls.CallEndReason
import chat.nyutag.core.calls.CallPhase
import chat.nyutag.messenger.calls.CallDesk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Разрешение на микрофон перед звонком: `ask(then)` спросит, если надо, и вызовет `then(granted)`. */
class MicAccess(val ask: ((Boolean) -> Unit) -> Unit)

@Composable
fun rememberMicAccess(): MicAccess {
    val context = LocalContext.current
    // Колбэк ждёт ответа системы; экрану его показывать не нужно, поэтому не состояние Compose.
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.invoke(granted)
        pending[0] = null
    }
    return remember(launcher) {
        MicAccess { then ->
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                then(true)
            } else {
                pending[0] = then
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@Composable
fun CallScreen(desk: CallDesk) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val session by desk.current.collectAsState()
    val phase by desk.phase.collectAsState()
    val peerName by desk.peerName.collectAsState()
    val members by desk.members.collectAsState()
    val muted by desk.muted.collectAsState()
    val speaker by desk.speaker.collectAsState()
    val mic = rememberMicAccess()
    var micDenied by remember(session) { mutableStateOf(false) }
    val talking = phase == CallPhase.Calling || phase == CallPhase.Connecting || phase is CallPhase.Live
    // «Назад» сворачивает идущий звонок; входящий ждёт ответа.
    BackHandler { if (talking) desk.setFolded(true) }

    Box(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.sky, palette.sky.copy(alpha = 0.75f).compositeOver(palette.milk))))
            .clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (talking) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                            .clickable { desk.setFolded(true) }, contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(18.dp)) { chevronDown(Color.White) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Pillow(session?.roomID ?: "·", peerName, 128.dp)
            Text(peerName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                 overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
            CallStatus(phase, Color.White.copy(alpha = 0.85f), 19)
            if (session?.group == true && talking) {
                Text(if (members.isEmpty()) words(P.callGroupWaiting) else "${words(P.callGroupMembers)} ${members.size + 1}\n" +
                         members.joinToString(", "),
                     color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, textAlign = TextAlign.Center,
                     modifier = Modifier.padding(horizontal = 32.dp))
            }
            if (micDenied) {
                Text(words(P.callMicDenied), color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
                     modifier = Modifier.padding(horizontal = 32.dp))
            }
            Spacer(Modifier.weight(1f))
            when {
                phase == CallPhase.Ringing -> Row(horizontalArrangement = Arrangement.spacedBy(72.dp)) {
                    RoundButton(words(P.callDecline), Color(0xFFE5484D), onClick = { scope.launch { desk.hangUp() } }) {
                        handset(Color.White, down = true)
                    }
                    RoundButton(words(P.callAccept), palette.read, onClick = {
                        // Без микрофона всё равно принимаем: собеседника хотя бы слышно.
                        mic.ask { granted ->
                            micDenied = !granted
                            scope.launch { desk.accept() }
                        }
                    }) { handset(Color.White, down = false) }
                }
                talking -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    val off = Color.White.copy(alpha = 0.18f)
                    val sky = palette.sky
                    RoundButton(words(P.callMute), if (muted) Color.White else off, onClick = { desk.setMuted(!muted) }) {
                        microphone(if (muted) sky else Color.White, crossed = muted)
                    }
                    RoundButton(words(P.callSpeaker), if (speaker) Color.White else off, onClick = { desk.setSpeaker(!speaker) }) {
                        loudspeaker(if (speaker) sky else Color.White)
                    }
                    RoundButton(words(P.callHangUp), Color(0xFFE5484D), onClick = { scope.launch { desk.hangUp() } }) {
                        handset(Color.White, down = true)
                    }
                }
                else -> Spacer(Modifier.height(96.dp))
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    LaunchedEffect(session) {
        // Разговор без разрешения на микрофон: подсказка, где его включить.
        if (session?.isCaller == true &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micDenied = true
    }
}

/** Полоска свёрнутого звонка над приложением. */
@Composable
fun CallStrip(desk: CallDesk) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val phase by desk.phase.collectAsState()
    val peerName by desk.peerName.collectAsState()
    Row(Modifier.fillMaxWidth().background(palette.read).clickable { desk.setFolded(false) }.statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(16.dp)) { handset(Color.White, down = false) }
        Text(peerName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
             overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        CallStatus(phase, Color.White, 15)
        Spacer(Modifier.weight(1f))
        Text(words(P.callBack), color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun CallStatus(phase: CallPhase?, color: Color, size: Int) {
    val words = LocalWords.current
    val text = when (phase) {
        CallPhase.Calling -> words(P.callCalling)
        CallPhase.Ringing -> words(P.callIncoming)
        CallPhase.Connecting -> words(P.callConnecting)
        is CallPhase.Live -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(phase) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000 - now % 1_000)
                }
            }
            clock((now - phase.since) / 1_000)
        }
        is CallPhase.Ended -> words(ending(phase.reason))
        null -> " "
    }
    Text(text, color = color, fontSize = size.sp, maxLines = 1)
}

@Composable
private fun RoundButton(caption: String, fill: Color, onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(68.dp).clip(CircleShape).background(fill).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) { Canvas(Modifier.size(28.dp), onDraw = glyph) }
        Text(caption, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(88.dp),
             textAlign = TextAlign.Center)
    }
}

/** Кнопка звонка в шапке чата. */
@Composable
fun CallButton(tint: Color, size: Dp = 22.dp, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).clickable(onClick = onClick).padding(10.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) { handset(tint, down = false) }
    }
}

private fun clock(seconds: Long): String {
    val total = maxOf(seconds, 0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun ending(reason: CallEndReason): Phrase = when (reason) {
    CallEndReason.HANG_UP -> P.callEnded
    CallEndReason.DECLINED -> P.callDeclined
    CallEndReason.NO_ANSWER -> P.callNoAnswer
    CallEndReason.BUSY -> P.callBusy
    CallEndReason.LOST -> P.callLost
    CallEndReason.ELSEWHERE -> P.callElsewhere
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a))
}

// MARK: Значки

/** Трубка: дуга с наушником и микрофоном на концах. `down` — лежит (отбой), иначе поднята. */
private fun DrawScope.handset(color: Color, down: Boolean) {
    val w = size.width
    rotate(if (down) 0f else -40f) {
        val stroke = w * 0.2f
        val radius = w * 0.42f
        val center = Offset(w / 2, w * 0.82f)
        drawArc(color, 200f, 140f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round))
        val ear = Size(w * 0.3f, w * 0.2f)
        for (x in listOf(w * 0.02f, w * 0.68f)) {
            drawRoundRect(color, Offset(x, w * 0.5f), ear, CornerRadius(w * 0.08f))
        }
    }
}

private fun DrawScope.microphone(color: Color, crossed: Boolean) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(w * 0.08f, cap = StrokeCap.Round)
    drawRoundRect(color, Offset(w * 0.36f, h * 0.06f), Size(w * 0.28f, h * 0.5f), CornerRadius(w * 0.14f))
    drawArc(color, 0f, 180f, false, Offset(w * 0.22f, h * 0.18f), Size(w * 0.56f, h * 0.52f), style = stroke)
    drawLine(color, Offset(w / 2, h * 0.7f), Offset(w / 2, h * 0.9f), w * 0.08f, StrokeCap.Round)
    drawLine(color, Offset(w * 0.34f, h * 0.92f), Offset(w * 0.66f, h * 0.92f), w * 0.08f, StrokeCap.Round)
    if (crossed) drawLine(color, Offset(w * 0.14f, h * 0.08f), Offset(w * 0.86f, h * 0.9f), w * 0.09f, StrokeCap.Round)
}

private fun DrawScope.loudspeaker(color: Color) {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(w * 0.08f, h * 0.36f)
        lineTo(w * 0.26f, h * 0.36f)
        lineTo(w * 0.5f, h * 0.14f)
        lineTo(w * 0.5f, h * 0.86f)
        lineTo(w * 0.26f, h * 0.64f)
        lineTo(w * 0.08f, h * 0.64f)
        close()
    }
    drawPath(body, color)
    val stroke = Stroke(w * 0.07f, cap = StrokeCap.Round)
    drawArc(color, -45f, 90f, false, Offset(w * 0.38f, h * 0.3f), Size(w * 0.36f, h * 0.4f), style = stroke)
    drawArc(color, -50f, 100f, false, Offset(w * 0.3f, h * 0.14f), Size(w * 0.62f, h * 0.72f), style = stroke)
}

private fun DrawScope.chevronDown(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.1f, h * 0.32f)
        lineTo(w * 0.5f, h * 0.72f)
        lineTo(w * 0.9f, h * 0.32f)
    }
    drawPath(path, color, style = Stroke(w * 0.14f, cap = StrokeCap.Round))
}
