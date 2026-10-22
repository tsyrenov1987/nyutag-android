// Нютаг. Переписка: пузыри (свои — синие, чужие — карточки), имя автора в начале серии, плашки дней и событий,
// цитата ответа, «изм.», время и галочки внутри пузыря. Отправленное, но ещё не пришедшее с сервера, висит
// внизу с часиками; не ушедшее — с пометкой, нажатие повторяет отправку.

package chat.nyutag.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.ChatLine
import chat.nyutag.core.rooms.FeedItem
import chat.nyutag.core.rooms.TransactionID
import chat.nyutag.messenger.session.AppSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Своя реплика до прихода с сервера. `eventID` — когда сервер принял: ждём её в ленте. */
private data class Outgoing(val transactionID: String, val text: String, val ts: Long,
                            val eventID: String? = null, val failed: Boolean = false)

@Composable
fun ChatScreen(session: AppSession, roomID: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val revision by session.revision.collectAsState()
    val lines by session.lines.collectAsState()
    val typingAll by session.typing.collectAsState()
    val line = lines.firstOrNull { it.id == roomID }
    var limit by remember(roomID) { mutableStateOf(60) }
    var items by remember(roomID) { mutableStateOf<List<FeedItem>>(emptyList()) }
    val outbox = remember(roomID) { mutableStateListOf<Outgoing>() }
    var draft by remember(roomID) { mutableStateOf("") }
    var loadingOlder by remember(roomID) { mutableStateOf(false) }
    var noMoreHistory by remember(roomID) { mutableStateOf(false) }
    var lastRead by remember(roomID) { mutableStateOf<String?>(null) }
    var typingSentAt by remember(roomID) { mutableStateOf(0L) }
    var typingLine by remember(roomID) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(roomID, revision, limit) {
        val fresh = session.feed(roomID, limit)
        items = fresh
        val arrived = fresh.mapTo(HashSet()) { it.id }
        outbox.removeAll { it.eventID != null && it.eventID in arrived }
        // Прочитано всё, что видно: отметка на последней чужой реплике.
        fresh.lastOrNull { !it.isMine && !it.isNotice }?.id?.let { id ->
            if (id != lastRead) {
                lastRead = id
                session.markRead(roomID, id)
            }
        }
    }

    LaunchedEffect(roomID, typingAll) {
        val who = typingAll[roomID].orEmpty()
        typingLine = when {
            who.isEmpty() -> null
            line?.isDirect == true -> words(P.typingNow)
            who.size == 1 -> words(P.typingName, session.displayName(roomID, who[0]))
            else -> words(P.typingMany, session.displayName(roomID, who[0]), "${who.size - 1}")
        }
    }

    // Долистали до верха (у перевёрнутого списка — до конца): сначала шире окно из базы, потом страница с сервера.
    LaunchedEffect(listState, roomID) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) ->
                if (last == null || total == 0 || last < total - 3 || loadingOlder || noMoreHistory) return@collect
                loadingOlder = true
                if (items.size >= limit) {
                    limit += 60
                } else {
                    val more = session.loadOlder(roomID)
                    if (more) limit += 60 else noMoreHistory = true
                }
                loadingOlder = false
            }
    }

    fun send(text: String, transactionID: String = TransactionID.make()) {
        val index = outbox.indexOfFirst { it.transactionID == transactionID }
        val pending = Outgoing(transactionID, text, System.currentTimeMillis())
        if (index >= 0) outbox[index] = pending else outbox.add(pending)
        scope.launch {
            val eventID = session.sendText(roomID, text, transactionID)
            val at = outbox.indexOfFirst { it.transactionID == transactionID }
            if (at < 0) return@launch
            when {
                eventID == null -> outbox[at] = outbox[at].copy(failed = true)
                items.any { it.id == eventID } -> outbox.removeAt(at)
                else -> outbox[at] = outbox[at].copy(eventID = eventID)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(palette.milk).safeDrawingPadding().imePadding()) {
        Header(line, typingLine, onBack)
        val rows = remember(items, outbox.toList(), words.lang) { layout(items, outbox.toList(), line, words) }
        // Новая реплика внизу: своя — всегда к ней, чужая — если читатель и так внизу (не листает историю).
        val newest = rows.lastOrNull()
        LaunchedEffect(newest?.key) {
            val mine = (newest as? Row.Bubble)?.mine == true
            if (newest != null && (mine || listState.firstVisibleItemIndex <= 2)) listState.animateScrollToItem(0)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true,
                   contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            items(rows.asReversed(), key = { it.key }) { row ->
                when (row) {
                    is Row.Plate -> Plate(row.text)
                    is Row.Bubble -> Bubble(row) { row.retry?.let { (tx, text) -> send(text, tx) } }
                }
            }
        }
        Composer(draft, onChange = { text ->
            draft = text
            val now = System.currentTimeMillis()
            if (text.isNotBlank() && now - typingSentAt > 8_000) {
                typingSentAt = now
                scope.launch { session.setTyping(roomID, true) }
            }
        }, onSend = {
            val text = draft.trim()
            if (text.isEmpty()) return@Composer
            draft = ""
            typingSentAt = 0
            scope.launch { session.setTyping(roomID, false) }
            send(text)
        })
    }
}

@Composable
private fun Header(line: ChatLine?, typing: String?, onBack: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(palette.card).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("‹", color = palette.sky, fontSize = 34.sp,
             modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 12.dp))
        val title = line?.title.orEmpty().ifEmpty { words(P.emptyChat) }
        Pillow(line?.id.orEmpty(), title, 38.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (line?.isEncrypted == true) LockMark(palette.quiet)
            }
            if (typing != null) Text(typing, color = palette.sky, fontSize = 13.sp, maxLines = 1)
        }
    }
}

private sealed class Row(val key: String) {
    class Plate(key: String, val text: String) : Row(key)
    class Bubble(
        key: String,
        val mine: Boolean,
        /** Имя автора над пузырём: в группе, на первой реплике серии. */
        val author: String?,
        val text: String,
        /** Текст служебный (замок, удалено): курсивом и тише. */
        val muted: Boolean,
        val quote: Pair<String, String>?,
        val forwarded: Boolean,
        val edited: Boolean,
        val time: String,
        val delivery: ChatLine.Delivery,
        val reactions: List<FeedItem.Reaction>,
        val pending: Boolean = false,
        val failed: Boolean = false,
        val retry: Pair<String, String>? = null,
    ) : Row(key)
}

/** Лента в строки экрана: плашки дней, события, пузыри и очередь отправки. Старые первыми. */
private fun layout(items: List<FeedItem>, outbox: List<Outgoing>, line: ChatLine?, words: Words): List<Row> {
    val zone = ZoneId.systemDefault()
    val rows = ArrayList<Row>()
    var lastDay: LocalDate? = null
    var lastSender: String? = null
    var lastTS = 0L
    val group = line?.isDirect != true
    fun day(ts: Long) {
        val day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        if (day != lastDay) {
            lastDay = day
            lastSender = null
            rows += Row.Plate("day-$day", dayLabel(day, words))
        }
    }
    for (item in items) {
        day(item.ts)
        val body = item.body
        if (body is FeedItem.Body.Notice) {
            rows += Row.Plate(item.id, notice(body.notice, words))
            lastSender = null
            continue
        }
        val firstInRun = item.sender != lastSender || item.ts - lastTS > 10 * 60_000
        lastSender = item.sender
        lastTS = item.ts
        val (text, muted) = when (body) {
            is FeedItem.Body.Text -> body.text to false
            is FeedItem.Body.Attachment -> (kindLabel(body.kind, words) + (body.caption?.let { "\n$it" } ?: "")) to false
            FeedItem.Body.Locked -> words(P.waitingKey) to true
            FeedItem.Body.Deleted -> words(P.deleted) to true
            is FeedItem.Body.Notice -> "" to true
        }
        rows += Row.Bubble(
            key = item.id, mine = item.isMine, author = if (group && !item.isMine && firstInRun) item.senderName else null,
            text = text, muted = muted, quote = item.reply?.let { it.senderName to kindLabel(it.preview, words) },
            forwarded = item.forwarded, edited = item.edited, time = clock(item.ts),
            delivery = if (!item.isMine) ChatLine.Delivery.NONE else if (item.isRead) ChatLine.Delivery.READ else ChatLine.Delivery.SENT,
            reactions = item.reactions,
        )
    }
    for (out in outbox) {
        day(out.ts)
        rows += Row.Bubble(
            key = "tx-${out.transactionID}", mine = true, author = null, text = out.text, muted = false, quote = null,
            forwarded = false, edited = false, time = if (out.failed) words(P.notSent) else clock(out.ts),
            delivery = ChatLine.Delivery.NONE, reactions = emptyList(), pending = !out.failed, failed = out.failed,
            retry = if (out.failed) out.transactionID to out.text else null,
        )
    }
    return rows
}

private fun clock(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun dayLabel(day: LocalDate, words: Words): String {
    val today = LocalDate.now()
    return when (day) {
        today -> words(P.today)
        today.minusDays(1) -> words(P.yesterday)
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMMM" else "d MMMM yyyy", words.locale))
    }
}

private fun notice(notice: FeedItem.Notice, words: Words): String = when (notice) {
    is FeedItem.Notice.Created -> words(P.noticeCreated)
    is FeedItem.Notice.Joined -> words(P.noticeJoined, notice.name)
    is FeedItem.Notice.Left -> words(P.noticeLeft, notice.name)
    is FeedItem.Notice.Invited -> words(P.noticeInvited, notice.name, notice.by)
    is FeedItem.Notice.Removed -> words(P.noticeRemoved, notice.name, notice.by)
    is FeedItem.Notice.Banned -> words(P.noticeBanned, notice.name, notice.by)
    is FeedItem.Notice.RenamedSelf -> words(P.noticeRenamed, notice.from, notice.to)
    is FeedItem.Notice.RoomNamed -> words(P.noticeRoomName, notice.name)
    is FeedItem.Notice.RoomPhoto -> words(P.noticeRoomPhoto)
    is FeedItem.Notice.RoomTopic -> words(P.noticeTopic, notice.topic)
    is FeedItem.Notice.EncryptionOn -> words(P.noticeEncrypted)
}

@Composable
private fun Plate(text: String) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, color = palette.ink.copy(alpha = 0.75f), fontSize = 12.sp, textAlign = TextAlign.Center,
             modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(palette.plate).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun Bubble(row: Row.Bubble, onRetry: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val ink = if (row.mine) Color.White else palette.ink
    val quiet = if (row.mine) Color.White.copy(alpha = 0.72f) else palette.quiet
    Column(Modifier.fillMaxWidth().padding(top = if (row.author != null) 8.dp else 2.dp, bottom = 2.dp),
           horizontalAlignment = if (row.mine) Alignment.End else Alignment.Start) {
        row.author?.let {
            Text(it, color = palette.sky, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                   .background(if (row.mine) palette.mine.copy(alpha = if (row.pending) 0.8f else 1f) else palette.card)
                   .clickable(enabled = row.failed, onClick = onRetry)
                   .padding(horizontal = 12.dp, vertical = 7.dp)) {
            if (row.forwarded) Text(words(P.forwarded), color = quiet, fontSize = 12.sp)
            row.quote?.let { (name, preview) ->
                Row(Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (row.mine) Color.White.copy(alpha = 0.14f) else palette.plate.copy(alpha = 0.6f))) {
                    Box(Modifier.width(3.dp).heightIn(min = 34.dp).background(if (row.mine) Color.White else palette.sky))
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(name, color = if (row.mine) Color.White else palette.sky, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(preview, color = quiet, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(row.text, color = if (row.muted) quiet else ink, fontSize = 16.sp,
                 fontStyle = if (row.muted) androidx.compose.ui.text.font.FontStyle.Italic else null)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.edited) Text(words(P.edited), color = quiet, fontSize = 11.sp)
                Text(row.time, color = if (row.failed) palette.gold else quiet, fontSize = 11.sp)
                if (row.pending) Text("🕓", fontSize = 10.sp)
                if (row.mine) Ticks(row.delivery, color = quiet, readColor = palette.readOnBlue)
            }
        }
        if (row.reactions.isNotEmpty()) {
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (reaction in row.reactions) {
                    Text("${reaction.key} ${reaction.count}", fontSize = 13.sp, color = palette.ink,
                         modifier = Modifier.clip(RoundedCornerShape(12.dp))
                             .background(if (reaction.mine) palette.sky.copy(alpha = 0.18f) else palette.card)
                             .padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun Composer(text: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(palette.card).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(palette.milk).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (text.isEmpty()) Text(words(P.messageField), color = palette.quiet, fontSize = 16.sp)
            BasicTextField(text, onChange, Modifier.fillMaxWidth(), maxLines = 6,
                           textStyle = TextStyle(color = palette.ink, fontSize = 16.sp), cursorBrush = SolidColor(palette.sky),
                           keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
        val ready = text.isNotBlank()
        Box(Modifier.size(42.dp).clip(CircleShape).background(if (ready) palette.sky else palette.sky.copy(alpha = 0.4f))
                .clickable(enabled = ready, onClick = onSend),
            contentAlignment = Alignment.Center) {
            Text("➤", color = Color.White, fontSize = 18.sp)
        }
    }
}
