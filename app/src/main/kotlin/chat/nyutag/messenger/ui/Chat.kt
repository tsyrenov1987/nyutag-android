// Нютаг. Переписка: пузыри (свои — синие, чужие — карточки), имя автора в начале серии, плашки дней и событий,
// цитата ответа, «изм.», время и галочки внутри пузыря. Отправленное, но ещё не пришедшее с сервера, висит
// внизу с часиками; не ушедшее — с пометкой, нажатие повторяет отправку.

package chat.nyutag.messenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import chat.nyutag.core.nyutag.BuryatLetters
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
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
import chat.nyutag.messenger.BuildConfig
import android.widget.Toast
import androidx.compose.runtime.produceState
import chat.nyutag.messenger.session.AppSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Своя реплика до прихода с сервера. `eventID` — когда сервер принял: ждём её в ленте. */
private data class Outgoing(val transactionID: String, val text: String, val ts: Long,
                            val eventID: String? = null, val failed: Boolean = false,
                            val replyTo: String? = null, val quote: Pair<String, String>? = null)

/** Что можно сделать с репликой из меню долгого нажатия. */
private sealed class Action {
    object Reply : Action()
    object Copy : Action()
    object Edit : Action()
    object Delete : Action()
    object Forward : Action()
    object Report : Action()
    data class React(val key: String) : Action()
}

/** Быстрые реакции в меню, по порядку, как на iOS. */
private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

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
    // Чат был в списке и пропал — его покинули на другом устройстве: писать туда уже нельзя.
    var listed by remember(roomID) { mutableStateOf(false) }
    LaunchedEffect(line != null) { if (line != null) listed = true else if (listed) onBack() }
    var limit by remember(roomID) { mutableStateOf(60) }
    var items by remember(roomID) { mutableStateOf<List<FeedItem>>(emptyList()) }
    val outbox = remember(roomID) { mutableStateListOf<Outgoing>() }
    var draft by remember(roomID) { mutableStateOf(TextFieldValue()) }
    var loadingOlder by remember(roomID) { mutableStateOf(false) }
    var noMoreHistory by remember(roomID) { mutableStateOf(false) }
    var lastRead by remember(roomID) { mutableStateOf<String?>(null) }
    var typingSentAt by remember(roomID) { mutableStateOf(0L) }
    var typingLine by remember(roomID) { mutableStateOf<String?>(null) }
    var replyTo by remember(roomID) { mutableStateOf<FeedItem?>(null) }
    var editing by remember(roomID) { mutableStateOf<FeedItem?>(null) }
    var deleting by remember(roomID) { mutableStateOf<FeedItem?>(null) }
    var failed by remember(roomID) { mutableStateOf(false) }
    var forwarding by remember(roomID) { mutableStateOf<FeedItem?>(null) }
    var reporting by remember(roomID) { mutableStateOf<FeedItem?>(null) }
    var reportSent by remember(roomID) { mutableStateOf(false) }
    var choosingWallpaper by remember(roomID) { mutableStateOf(false) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val mic = rememberMicAccess()
    val camera = rememberCameraAccess()
    // Звонить можно один на один и в шифрованную группу; состав комнаты меняется синхронизацией.
    val callKind by produceState(AppSession.CallKind.NONE, roomID, revision) { value = session.callKind(roomID) }

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

    fun send(text: String, transactionID: String = TransactionID.make(), reply: FeedItem? = null,
             retry: Outgoing? = null) {
        val index = outbox.indexOfFirst { it.transactionID == transactionID }
        val pending = Outgoing(transactionID, text, System.currentTimeMillis(),
                               replyTo = reply?.id ?: retry?.replyTo, quote = reply?.let { quoteOf(it, words) } ?: retry?.quote)
        if (index >= 0) outbox[index] = pending else outbox.add(pending)
        scope.launch {
            val eventID = session.sendText(roomID, text, transactionID, pending.replyTo)
            val at = outbox.indexOfFirst { it.transactionID == transactionID }
            if (at < 0) return@launch
            when {
                eventID == null -> outbox[at] = outbox[at].copy(failed = true)
                items.any { it.id == eventID } -> outbox.removeAt(at)
                else -> outbox[at] = outbox[at].copy(eventID = eventID)
            }
        }
    }

    /** Сервер не принял правку, удаление или реакцию: короткое «не получилось». */
    fun run(what: String, block: suspend () -> Boolean) {
        scope.launch {
            val ok = block()
            if (!ok) failed = true
            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "$what ${if (ok) "ушло" else "не прошло"}")
        }
    }

    fun act(action: Action, item: FeedItem) {
        when (action) {
            Action.Reply -> { editing = null; replyTo = item }
            Action.Copy -> (item.body as? FeedItem.Body.Text)?.let { copyText(context, it.text) }
            Action.Edit -> (item.body as? FeedItem.Body.Text)?.let { replyTo = null; editing = item; draft = TextFieldValue(it.text, TextRange(it.text.length)) }
            Action.Delete -> deleting = item
            Action.Forward -> forwarding = item
            Action.Report -> reporting = item
            is Action.React -> run("реакция ${action.key}") { session.toggleReaction(roomID, item, action.key) }
        }
    }

    forwarding?.let { item ->
        ForwardScreen(session, item, roomID) { forwarding = null }
        return
    }
    if (choosingWallpaper) {
        WallpaperPicker(roomID) { choosingWallpaper = false }
        return
    }
    reporting?.let { item ->
        ReportScreen(session, item, roomID, onClose = { reporting = null }) { reporting = null; reportSent = true }
        return
    }
    Column(Modifier.fillMaxSize().background(palette.milk).safeDrawingPadding().imePadding()) {
        Header(line, typingLine, onBack, onWallpaper = { choosingWallpaper = true },
               onCall = if (callKind == AppSession.CallKind.NONE) null else ({
                   mic.ask { granted ->
                       if (granted) scope.launch { session.startCall(roomID) }
                       else Toast.makeText(context, words(P.callMicDenied), Toast.LENGTH_LONG).show()
                   }
               }),
               onVideoCall = if (callKind != AppSession.CallKind.DIRECT) null else ({
                   mic.ask { granted ->
                       if (!granted) Toast.makeText(context, words(P.callMicDenied), Toast.LENGTH_LONG).show()
                       // Без камеры звоним всё равно: собеседника видно, себя — нет.
                       else camera.ask { seen ->
                           if (!seen) Toast.makeText(context, words(P.callCameraDenied), Toast.LENGTH_LONG).show()
                           scope.launch { session.startCall(roomID, video = true) }
                       }
                   }
               }))
        val rows = remember(items, outbox.toList(), words.lang) { layout(items, outbox.toList(), line, words) }
        // Новая реплика внизу: своя — всегда к ней, чужая — если читатель и так внизу (не листает историю).
        val newest = rows.lastOrNull()
        LaunchedEffect(newest?.key) {
            val mine = (newest as? Row.Bubble)?.mine == true
            if (newest != null && (mine || listState.firstVisibleItemIndex <= 2)) listState.animateScrollToItem(0)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        WallpaperBackground(roomID, Modifier.matchParentSize())
        LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                   contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            items(rows.asReversed(), key = { it.key }) { row ->
                when (row) {
                    is Row.Plate -> Plate(row.text)
                    is Row.Bubble -> Bubble(row, onRetry = {
                        row.retry?.let { (tx, text) -> send(text, tx, retry = outbox.firstOrNull { it.transactionID == tx }) }
                    }, onAction = { action -> row.item?.let { act(action, it) } })
                }
            }
        }
        }
        editing?.let { item ->
            ComposerBanner("✎ " + words(P.editing), summary(item, words)) { editing = null; draft = TextFieldValue() }
        } ?: replyTo?.let { item ->
            ComposerBanner(item.senderName, summary(item, words)) { replyTo = null }
        }
        val old = (editing?.body as? FeedItem.Body.Text)?.text
        Composer(draft, editing = editing != null, ready = draft.text.isNotBlank() && draft.text.trim() != old, onChange = { value ->
            // «hайн» → «һайн» при наборе; длина не меняется, курсор остаётся на месте. Пока клавиатура составляет слово,
            // текст не трогаем: подмена посреди составления сбивает Gboard, и он вставляет слово заново.
            draft = (if (words.buryatLetters && value.composition == null) BuryatLetters.fix(value.text) else null)
                ?.let { value.copy(text = it) } ?: value
            val text = draft.text
            val now = System.currentTimeMillis()
            if (editing == null && text.isNotBlank() && now - typingSentAt > 8_000) {
                typingSentAt = now
                scope.launch { session.setTyping(roomID, true) }
            }
        }, onLetter = { base ->
            val start = draft.selection.min
            val letter = BuryatLetters.letter(base, draft.text.substring(0, start))
            draft = TextFieldValue(draft.text.replaceRange(start, draft.selection.max, letter.toString()), TextRange(start + 1))
        }, onSend = {
            val text = draft.text.let { if (words.buryatLetters) BuryatLetters.fix(it) ?: it else it }.trim()
            if (text.isEmpty()) return@Composer
            draft = TextFieldValue()
            val target = editing
            if (target != null) {
                editing = null
                if (text != old) run("правка") { session.edit(roomID, target.id, text) }
                return@Composer
            }
            typingSentAt = 0
            scope.launch { session.setTyping(roomID, false) }
            val reply = replyTo
            replyTo = null
            send(text, reply = reply)
        })
    }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null },
                    title = { Text(words(P.deleteAsk)) },
                    confirmButton = {
                        TextButton({ deleting = null; run("удаление") { session.delete(roomID, item.id) } }) {
                            Text(words(P.deleteAction), color = Color(0xFFD03A2F))
                        }
                    },
                    dismissButton = { TextButton({ deleting = null }) { Text(words(P.cancel), color = palette.sky) } })
    }
    if (reportSent) {
        AlertDialog(onDismissRequest = { reportSent = false }, title = { Text(words(P.reportSent)) },
                    confirmButton = { TextButton({ reportSent = false }) { Text(words(P.done), color = palette.sky) } })
    }
    if (failed) {
        AlertDialog(onDismissRequest = { failed = false }, title = { Text(words(P.cantDo)) },
                    confirmButton = { TextButton({ failed = false }) { Text(words(P.done), color = palette.sky) } })
    }
}

@Composable
private fun Header(line: ChatLine?, typing: String?, onBack: () -> Unit, onWallpaper: () -> Unit, onCall: (() -> Unit)?,
                   onVideoCall: (() -> Unit)?) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(palette.card).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("‹", color = palette.sky, fontSize = 34.sp,
             modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 12.dp))
        val title = line?.title.orEmpty().ifEmpty { words(P.emptyChat) }
        Pillow(line?.id.orEmpty(), title, 38.dp, line?.avatarURL)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (line?.isEncrypted == true) LockMark(palette.quiet)
            }
            if (typing != null) Text(typing, color = palette.sky, fontSize = 13.sp, maxLines = 1)
        }
        if (onCall != null) CallButton(palette.sky, onClick = onCall)
        if (onVideoCall != null) VideoCallButton(palette.sky, onClick = onVideoCall)
        Box {
            Text("⋯", color = palette.sky, fontSize = 26.sp,
                 modifier = Modifier.clip(CircleShape).clickable { menu = true }.padding(horizontal = 12.dp, vertical = 2.dp))
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(words(P.wallpaperForChat)) }, { menu = false; onWallpaper() })
            }
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
        /** Реплика из ленты: по ней меню долгого нажатия. У очереди отправки её нет. */
        val item: FeedItem? = null,
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
            reactions = item.reactions, item = item.takeIf { body != FeedItem.Body.Deleted },
        )
    }
    for (out in outbox) {
        day(out.ts)
        rows += Row.Bubble(
            key = "tx-${out.transactionID}", mine = true, author = null, text = out.text, muted = false, quote = out.quote,
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
private fun Bubble(row: Row.Bubble, onRetry: () -> Unit, onAction: (Action) -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    val ink = if (row.mine) Color.White else palette.ink
    val quiet = if (row.mine) Color.White.copy(alpha = 0.72f) else palette.quiet
    Column(Modifier.fillMaxWidth().padding(top = if (row.author != null) 8.dp else 2.dp, bottom = 2.dp),
           horizontalAlignment = if (row.mine) Alignment.End else Alignment.Start) {
        row.author?.let {
            Text(it, color = palette.sky, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        Box {
            Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                       .background(if (row.mine) palette.mine.copy(alpha = if (row.pending) 0.8f else 1f) else palette.card)
                       .combinedClickable(enabled = row.failed || row.item != null,
                                          onLongClick = if (row.item != null) ({ menu = true }) else null,
                                          onClick = { if (row.failed) onRetry() })
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
            row.item?.let { item -> MessageMenu(menu, item, { menu = false }) { menu = false; onAction(it) } }
        }
        if (row.reactions.isNotEmpty()) {
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (reaction in row.reactions) {
                    Text("${reaction.key} ${reaction.count}", fontSize = 13.sp, color = palette.ink,
                         modifier = Modifier.clip(RoundedCornerShape(12.dp))
                             .background(if (reaction.mine) palette.sky.copy(alpha = 0.18f) else palette.card)
                             .clickable(enabled = row.item != null) { onAction(Action.React(reaction.key)) }
                             .padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun Composer(text: TextFieldValue, editing: Boolean, ready: Boolean, onChange: (TextFieldValue) -> Unit,
                     onLetter: (Char) -> Unit, onSend: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var typing by remember { mutableStateOf(false) }
    // Строка «ө ү һ»: на русской раскладке этих букв нет. Кнопки не забирают фокус, клавиатура остаётся.
    if (typing && words.buryatLetters) {
        Row(Modifier.fillMaxWidth().background(palette.card).padding(start = 10.dp, end = 10.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (letter in BuryatLetters.row) {
                Box(Modifier.size(52.dp, 34.dp).clip(RoundedCornerShape(10.dp)).background(palette.milk).clickable { onLetter(letter) },
                    contentAlignment = Alignment.Center) {
                    Text(letter.toString(), color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().background(palette.card).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(palette.milk).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (text.text.isEmpty()) Text(words(P.messageField), color = palette.quiet, fontSize = 16.sp)
            BasicTextField(text, onChange, Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused }, maxLines = 6,
                           textStyle = TextStyle(color = palette.ink, fontSize = 16.sp), cursorBrush = SolidColor(palette.sky),
                           keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
        Box(Modifier.size(42.dp).clip(CircleShape).background(if (ready) palette.sky else palette.sky.copy(alpha = 0.4f))
                .clickable(enabled = ready, onClick = onSend),
            contentAlignment = Alignment.Center) {
            Text(if (editing) "✓" else "➤", color = Color.White, fontSize = 18.sp)
        }
    }
}

/** Меню долгого нажатия: ряд быстрых реакций, ниже действия. */
@Composable
private fun MessageMenu(expanded: Boolean, item: FeedItem, onDismiss: () -> Unit, onAction: (Action) -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    DropdownMenu(expanded, onDismiss) {
        if (item.body != FeedItem.Body.Locked) {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (key in quickReactions) {
                    val mine = item.reactions.any { it.key == key && it.mine }
                    Text(key, fontSize = 22.sp, modifier = Modifier.clip(CircleShape)
                        .background(if (mine) palette.sky.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onAction(Action.React(key)) }.padding(6.dp))
                }
            }
            HorizontalDivider(color = palette.plate)
        }
        DropdownMenuItem({ Text(words(P.reply)) }, { onAction(Action.Reply) })
        if (item.body is FeedItem.Body.Text) DropdownMenuItem({ Text(words(P.copy)) }, { onAction(Action.Copy) })
        if (item.canForward) DropdownMenuItem({ Text(words(P.forward)) }, { onAction(Action.Forward) })
        if (item.canEdit) DropdownMenuItem({ Text(words(P.editAction)) }, { onAction(Action.Edit) })
        if (item.canDelete) DropdownMenuItem({ Text(words(P.deleteAction), color = Color(0xFFD03A2F)) }, { onAction(Action.Delete) })
        if (item.canReport) DropdownMenuItem({ Text(words(P.reportAction), color = Color(0xFFD03A2F)) }, { onAction(Action.Report) })
    }
}

/** Над полем ввода: на что отвечаем или что правим, с крестиком. */
@Composable
private fun ComposerBanner(title: String, text: String, onClose: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(palette.card).padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).heightIn(min = 34.dp).clip(RoundedCornerShape(2.dp)).background(palette.sky))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.sky, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(text, color = palette.quiet, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("✕", color = palette.quiet, fontSize = 16.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(12.dp))
    }
}

/** Одна строка о реплике для плашки ответа и правки. */
private fun summary(item: FeedItem, words: Words): String = when (val body = item.body) {
    is FeedItem.Body.Text -> body.text.replace('\n', ' ')
    is FeedItem.Body.Attachment -> kindLabel(body.kind, words)
    FeedItem.Body.Locked -> words(P.waitingKey)
    FeedItem.Body.Deleted -> words(P.deleted)
    is FeedItem.Body.Notice -> ""
}

/** Цитата для своей реплики-ответа, пока она в очереди отправки. */
private fun quoteOf(item: FeedItem, words: Words): Pair<String, String> = item.senderName to summary(item, words)

private fun copyText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Нютаг", text))
}
