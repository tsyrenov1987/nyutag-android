// Нютаг. Список чатов: карточки с аватаром, последней репликой, временем, галочками и золотым счётчиком.

package chat.nyutag.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.core.rooms.ChatLine
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.crypto.PasswordUnlockOutcome
import chat.nyutag.messenger.session.AppSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

@Composable
fun ChatListScreen(session: AppSession, onOpen: (String) -> Unit, onMe: () -> Unit, onHistoryKey: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val lines by session.lines.collectAsState()
    val typing by session.typing.collectAsState()
    val history by session.history.collectAsState()
    val locked = history == PasswordUnlockOutcome.NeedsRecoveryKey
    Column(Modifier.fillMaxSize().background(palette.milk)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(words(P.chats), color = palette.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).clickable(onClick = onMe).padding(6.dp)) {
                Pillow(session.me.orEmpty(), ChatDigest.localpart(session.me.orEmpty()), 36.dp)
            }
        }
        if (locked) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { LockedHistoryCard(onHistoryKey) }
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(words(P.chatsEmpty), color = palette.quiet, fontSize = 15.sp, modifier = Modifier.padding(32.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines, key = { it.id }) { line -> ChatRow(line, typing[line.id], words) { onOpen(line.id) } }
            }
        }
    }
}

@Composable
internal fun ChatRow(line: ChatLine, typingIDs: List<String>?, words: Words, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val typing = typingIDs?.firstOrNull()?.let { if (line.isDirect) words(P.typingNow) else words(P.typingName, ChatDigest.localpart(it)) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).clickable(onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pillow(line.id, line.title.ifEmpty { "·" })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Название с замком занимают всё место до времени; замок идёт сразу за текстом, а не у края.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(line.title.ifEmpty { words(P.emptyChat) }, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                         maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (line.isEncrypted) LockMark(palette.quiet)
                }
                if (line.lastIsMine) Ticks(line.delivery)
                if (line.lastTS > 0) {
                    Text(time(line.lastTS, words), color = if (line.unread > 0) palette.gold else palette.quiet, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(typing ?: preview(line, words), color = if (typing == null) palette.quiet else palette.sky, fontSize = 15.sp,
                     maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (line.mentions > 0) Badge("@", palette.sky, androidx.compose.ui.graphics.Color.White)
                if (line.unread > 0) Badge(if (line.unread > 999) "999+" else "${line.unread}", palette.gold, palette.onGold)
            }
        }
    }
}

@Composable
private fun Badge(text: String, background: androidx.compose.ui.graphics.Color, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.sizeIn(minWidth = 22.dp, minHeight = 22.dp).clip(CircleShape).background(background).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 8.dp))
    }
}

private fun preview(line: ChatLine, words: Words): String {
    if (line.membership == RoomRecord.Membership.INVITE) return words(P.invited)
    val last = line.last ?: return words(P.noMessages)
    val body = when (last) {
        is PushPreview.Kind.Text -> last.text
        PushPreview.Kind.Image -> "🖼 " + words(P.image)
        PushPreview.Kind.Video -> "🎬 " + words(P.video)
        PushPreview.Kind.Audio -> "🎙 " + words(P.audio)
        PushPreview.Kind.File -> "📎 " + words(P.file)
        PushPreview.Kind.Location -> "📍 " + words(P.location)
        PushPreview.Kind.Poll -> "📊 " + words(P.poll)
        PushPreview.Kind.Call -> "📞 " + words(P.call)
        else -> kindLabel(last, words)
    }
    if (line.lastIsMine) return "${words(P.you)}: $body"
    if (!line.isDirect && line.lastSenderName != null) return "${line.lastSenderName}: $body"
    return body
}

/** Подпись вида реплики без значка: для цитаты, вложения в пузыре и списка. */
fun kindLabel(kind: PushPreview.Kind, words: Words): String = when (kind) {
    is PushPreview.Kind.Text -> kind.text
    PushPreview.Kind.Image -> words(P.image)
    PushPreview.Kind.Video -> words(P.video)
    PushPreview.Kind.Audio -> words(P.audio)
    PushPreview.Kind.File -> words(P.file)
    PushPreview.Kind.Location -> words(P.location)
    PushPreview.Kind.Sticker -> words(P.sticker)
    PushPreview.Kind.Poll -> words(P.poll)
    PushPreview.Kind.Call -> words(P.call)
    PushPreview.Kind.Invite -> words(P.invited)
    PushPreview.Kind.Locked, PushPreview.Kind.Other, PushPreview.Kind.Signal -> words(P.newMessage)
}

/** Сегодня — часы, вчера — «Вчера», неделя — день недели, дальше — число и месяц. */
fun time(ms: Long, words: Words): String {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli(ms).atZone(zone)
    val today = LocalDate.now(zone)
    val day = moment.toLocalDate()
    return when {
        day == today -> moment.format(DateTimeFormatter.ofPattern("HH:mm"))
        day == today.minusDays(1) -> words(P.yesterday)
        ChronoUnit.DAYS.between(day, today) < 7 -> moment.dayOfWeek.getDisplayName(TextStyle.SHORT, words.locale)
        else -> moment.format(DateTimeFormatter.ofPattern("dd.MM"))
    }
}
