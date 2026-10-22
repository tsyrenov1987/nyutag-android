// Нютаг. «Пригласить друга»: в Нютаг попадают по одноразовому коду. Экран показывает свежий код крупно и QR
// ссылки на страницу приглашения, отдаёт готовый текст в «Поделиться» и держит список своих кодов с отзывом.
// Неиспользованный живой код показываем повторно, а не выпускаем новый на каждое открытие: у сервера лимит.

package chat.nyutag.messenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.nyutag.InviteCode
import chat.nyutag.core.nyutag.IssuedInvite
import chat.nyutag.core.nyutag.QrCode
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.session.AppSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun InviteScreen(session: AppSession, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<InviteCode?>(null) }
    var mine by remember { mutableStateOf<List<IssuedInvite>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AppSession.InviteFailure?>(null) }
    BackHandler(onBack = onClose)

    suspend fun make() {
        busy = true
        try {
            current = session.createInvite()
            failure = null
            session.myInvites()?.let { mine = it }
        } catch (e: AppSession.InviteException) {
            failure = e.failure
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        busy = true
        val list = session.myInvites().orEmpty()
        mine = list
        busy = false
        val fresh = list.firstOrNull { it.usedBy == null && alive(it.invite) }?.invite
        if (fresh != null) current = fresh else make()
        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "приглашение ${if (current == null) "нет: $failure" else "показано"}, кодов ${mine.size}")
    }

    FormPage(words(P.inviteFriend), onClose) {
        FormCard {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 16.dp),
                   horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val invite = current
                if (invite != null) {
                    QrCode.encode(invite.link)?.let { QrImage(it) }
                    Text(invite.code, color = palette.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pill(words(P.inviteShare), palette.sky, Color.White) { share(context, words(P.inviteShareText, invite.code, invite.link)) }
                        Pill(words(P.copy), palette.plate, palette.sky) { copy(context, invite.code) }
                    }
                } else if (busy) {
                    CircularProgressIndicator()
                    Text(words(P.inviteCreating), color = palette.quiet, fontSize = 15.sp)
                }
                failure?.let {
                    Text(words(phrase(it)), color = Color(0xFFD03A2F), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                // После отзыва показанного кода карточка не должна пустеть: остаётся «Новый код».
                if (current != null || !busy) {
                    Text(words(P.inviteNew), color = if (busy) palette.quiet else palette.sky, fontSize = 14.sp,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp))
                             .combinedClickable(enabled = !busy) { scope.launch { make() } }.padding(6.dp))
                }
            }
        }
        Text(words(P.inviteHint), color = palette.quiet, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        if (mine.isNotEmpty()) {
            Text(words(P.inviteMine).uppercase(), color = palette.quiet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.padding(start = 8.dp))
            FormCard {
                mine.forEachIndexed { index, item ->
                    if (index > 0) FormDivider()
                    IssuedRow(item, words) {
                        scope.launch {
                            if (session.revokeInvite(item.invite.code)) {
                                mine = mine.filter { it.invite.code != item.invite.code }
                                if (current?.code == item.invite.code) current = null
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Строка выпущенного кода; живой можно отозвать долгим нажатием. */
@Composable
private fun IssuedRow(item: IssuedInvite, words: Words, onRevoke: () -> Unit) {
    val palette = LocalPalette.current
    val live = item.usedBy == null && alive(item.invite)
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().combinedClickable(enabled = live, onLongClick = { menu = true }) {}
                   .padding(horizontal = 16.dp, vertical = 10.dp),
               verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.invite.code, color = if (live) palette.ink else palette.quiet, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
            Text(status(item, words), color = palette.quiet, fontSize = 12.sp)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(words(P.inviteRevoke), color = Color(0xFFD03A2F)) }, { menu = false; onRevoke() })
        }
    }
}

@Composable
private fun Pill(title: String, background: Color, color: Color, onClick: () -> Unit) {
    Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.clip(RoundedCornerShape(50)).background(background).combinedClickable(onClick = onClick)
             .padding(horizontal = 18.dp, vertical = 10.dp))
}

/** QR на белой подложке, середину закрывает знак Нютага (уровень коррекции H это выдерживает). */
@Composable
private fun QrImage(qr: QrCode) {
    Box(Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            val cell = size.width / qr.size
            for (y in 0 until qr.size) for (x in 0 until qr.size) {
                // Чуть внахлёст, чтобы между модулями не просвечивали волоски.
                if (qr[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(3.dp)) { NyutagLogo(44.dp) }
    }
}

private fun alive(invite: InviteCode): Boolean = invite.expiresAt?.isAfter(Instant.now()) ?: true

private fun status(item: IssuedInvite, words: Words): String {
    item.usedBy?.let { return words(P.inviteUsedBy, ChatDigest.localpart(it)) }
    val expires = item.invite.expiresAt ?: return ""
    if (!expires.isAfter(Instant.now())) return words(P.inviteExpired)
    val text = DateTimeFormatter.ofPattern("d MMMM, HH:mm", words.locale).format(expires.atZone(ZoneId.systemDefault()))
    return words(P.inviteValidTill, text)
}

private fun phrase(failure: AppSession.InviteFailure): Phrase = when (failure) {
    AppSession.InviteFailure.LIMIT -> P.inviteLimit
    AppSession.InviteFailure.NO_NETWORK -> P.noNetwork
    AppSession.InviteFailure.BROKEN -> P.inviteFailed
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Нютаг", text))
}
