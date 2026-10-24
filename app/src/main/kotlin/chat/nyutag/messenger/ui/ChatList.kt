// Нютаг. Список чатов: карточки с аватаром, последней репликой, временем, галочками и золотым счётчиком.

package chat.nyutag.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import chat.nyutag.core.rooms.ChatFolder
import chat.nyutag.core.rooms.ChatLayout
import chat.nyutag.core.rooms.Search
import chat.nyutag.core.store.SearchHit
import chat.nyutag.messenger.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import chat.nyutag.messenger.session.AppSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

@Composable
fun ChatListScreen(session: AppSession, onOpen: (String) -> Unit, onMe: () -> Unit, onNewGroup: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val lines by session.lines.collectAsState()
    // «Избранное» читается из базы: один раз на обновление списка, а не на каждую строку.
    val savedID = remember(lines) { session.savedRoomID }
    val typing by session.typing.collectAsState()
    val layout by session.layout.collectAsState()
    val mutedRooms by session.mutedRooms.collectAsState()
    val locked by session.historyNeedsSignIn.collectAsState()
    val myAvatar by session.myAvatar.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<FolderNaming?>(null) }
    var deleting by remember { mutableStateOf<ChatFolder?>(null) }
    var signingInAgain by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    if (folder != null && layout.folders.none { it.id == folder }) folder = null
    val wanted = query.trim()
    LaunchedEffect(wanted) {
        if (wanted.length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(250)
        hits = session.search(wanted)
        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "поиск «$wanted»: сообщений ${hits.size}")
    }
    Column(Modifier.fillMaxSize().background(palette.milk)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(words(P.chats), color = palette.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("+", color = palette.sky, fontSize = 30.sp,
                 modifier = Modifier.clip(CircleShape).clickable(onClick = onNewGroup).padding(horizontal = 12.dp)
                     .semantics { contentDescription = words(P.groupNew) })
            Box(Modifier.clip(CircleShape).clickable(onClick = onMe).padding(6.dp)) {
                Pillow(session.me.orEmpty(), ChatDigest.localpart(session.me.orEmpty()), 36.dp, myAvatar)
            }
        }
        TextField(query, { query = it }, placeholder = { Text(words(P.searchChats)) }, singleLine = true,
                  leadingIcon = { Text("🔍", fontSize = 15.sp) },
                  trailingIcon = if (query.isEmpty()) null else ({ Text("✕", color = palette.quiet, modifier = Modifier.clickable { query = "" }.padding(8.dp)) }),
                  colors = fieldColors(),
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)))
        if (locked) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { LockedHistoryCard { signingInAgain = true } }
        failure?.let { Box(Modifier.padding(horizontal = 12.dp)) { FailureNote(it) } }
        if (wanted.isNotEmpty()) {
            SearchResults(lines, hits, wanted, typing, words, onOpen) { hit -> session.focus(hit.roomID, hit.eventID); onOpen(hit.roomID) }
            return@Column
        }
        if (layout.folders.isNotEmpty()) {
            FolderBar(layout.folders, folder, onSelect = { folder = it }, onNew = { naming = FolderNaming() },
                      onRename = { naming = FolderNaming(it.id, it.name) }, onDelete = { deleting = it })
        }
        val shown = layout.arrange(lines, folder)
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(words(if (folder != null) P.folderEmpty else P.chatsEmpty), color = palette.quiet, fontSize = 15.sp,
                     textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { line ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        ChatRow(line, typing[line.id], words, pinned = layout.isPinned(line.id), muted = line.id in mutedRooms,
                                saved = line.id == savedID, onLongClick = { menu = true }) { onOpen(line.id) }
                        RowMenu(menu, { menu = false }, line.id, layout, line.id in mutedRooms,
                                onPin = { session.setPinned(line.id, it) },
                                onPlace = { id, inside -> session.place(line.id, id, inside) },
                                onNewFolder = { naming = FolderNaming(with = line.id) },
                                onMute = { quiet -> scope.launch { if (!session.setMuted(line.id, quiet)) failure = words(P.cantDo) } },
                                onLeave = { leaving = line.id })
                    }
                }
            }
        }
    }
    naming?.let { current ->
        FolderNameDialog(current, onDismiss = { naming = null }) { name ->
            naming = null
            if (current.folderID != null) session.renameFolder(current.folderID, name) else session.addFolder(name, current.with)
        }
    }
    deleting?.let { gone ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = palette.card,
                    title = { Text(words(P.folderDelete) + " «${gone.name}»", color = palette.ink, fontSize = 17.sp) },
                    text = { Text(words(P.folderDeleteHint), color = palette.quiet, fontSize = 15.sp) },
                    confirmButton = { TextButton({ deleting = null; session.deleteFolder(gone.id) }) { Text(words(P.folderDelete), color = Danger) } },
                    dismissButton = { TextButton({ deleting = null }) { Text(words(P.cancel), color = palette.sky) } })
    }
    if (signingInAgain) {
        AlertDialog(onDismissRequest = { signingInAgain = false }, containerColor = palette.card,
                    title = { Text(words(P.historyLockedTitle), color = palette.ink, fontSize = 17.sp) },
                    text = { Text(words(P.historySignInAgain), color = palette.quiet, fontSize = 15.sp) },
                    confirmButton = { TextButton({ signingInAgain = false; scope.launch { session.signOut() } }) { Text(words(P.signOut), color = Danger) } },
                    dismissButton = { TextButton({ signingInAgain = false }) { Text(words(P.cancel), color = palette.sky) } })
    }
    leaving?.let { roomID ->
        LeaveDialog(session, roomID, onDismiss = { leaving = null }, onFailed = { failure = words(P.chatLeaveFailed) }, onLeft = {})
    }
}

/** Окно имени папки: новая (`folderID == null`, `with` — чат, который сразу в неё) или переименование. */
private class FolderNaming(val folderID: String? = null, val oldName: String = "", val with: String? = null)

@Composable
private fun FolderNameDialog(naming: FolderNaming, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    // Курсор в конце старого имени: переименование обычно дописывает, а не начинает заново.
    var field by remember { mutableStateOf(TextFieldValue(naming.oldName, TextRange(naming.oldName.length))) }
    val name = field.text
    AlertDialog(onDismissRequest = onDismiss, containerColor = palette.card,
                title = { Text(words(if (naming.folderID == null) P.folderNew else P.folderRename), color = palette.ink, fontSize = 17.sp) },
                text = {
                    TextField(field, { field = if (it.text.length > 40) it.copy(text = it.text.take(40)) else it },
                              placeholder = { Text(words(P.folderName)) }, singleLine = true, colors = fieldColors(),
                              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.plate))
                },
                confirmButton = {
                    TextButton({ onSave(name.trim()) }, enabled = name.isNotBlank()) {
                        Text(words(P.save), color = if (name.isNotBlank()) palette.sky else palette.quiet)
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(words(P.cancel), color = palette.sky) } })
}

/** Полоса папок: «Все», папки, «+». Зажатая папка — переименовать или удалить. */
@Composable
private fun FolderBar(folders: List<ChatFolder>, selected: String?, onSelect: (String?) -> Unit, onNew: () -> Unit,
                      onRename: (ChatFolder) -> Unit, onDelete: (ChatFolder) -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Chip(words(P.folderAll), selected == null, onClick = { onSelect(null) }) }
        items(folders, key = { it.id }) { folder ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Chip(folder.name, selected == folder.id, onClick = { onSelect(folder.id) }, onLongClick = { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(words(P.folderRename)) }, { menu = false; onRename(folder) })
                    DropdownMenuItem({ Text(words(P.folderDelete), color = Danger) }, { menu = false; onDelete(folder) })
                }
            }
        }
        item {
            Box(Modifier.size(34.dp).clip(CircleShape).background(palette.card).clickable(onClick = onNew)
                    .semantics { contentDescription = words(P.folderNew) }, contentAlignment = Alignment.Center) {
                Text("+", color = palette.quiet, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(title: String, on: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) palette.sky else palette.card)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(title, color = if (on) Color.White else palette.ink, fontSize = 15.sp,
             fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** Меню зажатого чата: закрепить, в папку, звук, покинуть. */
@Composable
private fun RowMenu(open: Boolean, onDismiss: () -> Unit, roomID: String, layout: ChatLayout, muted: Boolean, onPin: (Boolean) -> Unit,
                    onPlace: (String, Boolean) -> Unit, onNewFolder: () -> Unit, onMute: (Boolean) -> Unit, onLeave: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var folders by remember { mutableStateOf(false) }
    DropdownMenu(open, { folders = false; onDismiss() }) {
        if (folders) {
            for (folder in layout.folders) {
                val inside = roomID in folder.rooms
                DropdownMenuItem({ Text((if (inside) "✓ " else "") + folder.name) }, { folders = false; onDismiss(); onPlace(folder.id, !inside) })
            }
            DropdownMenuItem({ Text("+ " + words(P.folderNew), color = palette.sky) }, { folders = false; onDismiss(); onNewFolder() })
            return@DropdownMenu
        }
        val pinned = layout.isPinned(roomID)
        DropdownMenuItem({
            Text(words(when { pinned -> P.unpin; layout.canPinMore -> P.pin; else -> P.pinLimit }),
                 color = if (pinned || layout.canPinMore) palette.ink else palette.quiet)
        }, { onDismiss(); onPin(!pinned) }, enabled = pinned || layout.canPinMore)
        DropdownMenuItem({ Text(words(P.folderAdd) + " ›") }, { if (layout.folders.isEmpty()) { onDismiss(); onNewFolder() } else folders = true })
        DropdownMenuItem({ Text(words(if (muted) P.unmute else P.mute)) }, { onDismiss(); onMute(!muted) })
        HorizontalDivider(color = palette.plate)
        DropdownMenuItem({ Text(words(P.chatLeave), color = Danger) }, { onDismiss(); onLeave() })
    }
}

/** Поиск: чаты по названию и реплики по тексту. */
@Composable
private fun SearchResults(lines: List<ChatLine>, hits: List<SearchHit>, wanted: String, typing: Map<String, List<String>>, words: Words,
                          onOpen: (String) -> Unit, onHit: (SearchHit) -> Unit) {
    val palette = LocalPalette.current
    val folded = Search.fold(wanted)
    val chats = lines.filter { it.membership == RoomRecord.Membership.JOIN && Search.fold(it.title).contains(folded) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chats.isEmpty() && hits.isEmpty()) {
            item { Text(words(P.searchNothing), color = palette.quiet, fontSize = 15.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 80.dp)) }
        }
        if (chats.isNotEmpty()) {
            item { SearchHeader(words(P.chats)) }
            items(chats, key = { "chat-" + it.id }) { line -> ChatRow(line, typing[line.id], words) { onOpen(line.id) } }
        }
        if (hits.isNotEmpty()) {
            item { SearchHeader(words(P.searchMessages)) }
            items(hits, key = { "hit-" + it.eventID }) { hit ->
                val line = lines.firstOrNull { it.id == hit.roomID }
                val title = line?.title.orEmpty().ifEmpty { words(P.emptyChat) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).clickable { onHit(hit) }.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pillow(hit.roomID, title, 44.dp, line?.avatarURL)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                 overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(time(hit.ts, words), color = palette.quiet, fontSize = 12.sp)
                        }
                        Text(hit.text, color = palette.quiet, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(title: String) {
    Text(title, color = LocalPalette.current.quiet, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatRow(line: ChatLine, typingIDs: List<String>?, words: Words, pinned: Boolean = false, muted: Boolean = false,
                     saved: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val typing = typingIDs?.firstOrNull()?.let { if (line.isDirect) words(P.typingNow) else words(P.typingName, ChatDigest.localpart(it)) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pillow(line.id, line.title.ifEmpty { "·" }, avatar = line.avatarURL)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Название с замком занимают всё место до времени; замок идёт сразу за текстом, а не у края.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(line.title.ifEmpty { words(P.emptyChat) }, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                         maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    // Группа или чат сообщества: значок, чтобы не путать с личным.
                    if (!line.isDirect && !saved) {
                        val badge = words(if (line.community == null) P.groupBadge else P.communityBadge)
                        Text(if (line.community == null) "👥" else "🏛", fontSize = 11.sp, modifier = Modifier.semantics { contentDescription = badge })
                    }
                    if (line.isEncrypted) LockMark(palette.quiet)
                    if (muted) Text("🔕", fontSize = 11.sp, modifier = Modifier.semantics { contentDescription = words(P.muted) })
                    if (pinned) Text("📌", fontSize = 11.sp, modifier = Modifier.semantics { contentDescription = words(P.pinned) })
                }
                if (line.lastIsMine) Ticks(line.delivery)
                if (line.lastTS > 0) {
                    Text(time(line.lastTS, words), color = if (line.unread > 0) palette.gold else palette.quiet, fontSize = 12.sp)
                }
            }
            line.community?.takeIf { it != line.title }?.let { community ->
                Text(community, color = palette.sky, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(typing ?: preview(line, words), color = if (typing == null) palette.quiet else palette.sky, fontSize = 15.sp,
                     maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (line.mentions > 0) Badge("@", palette.sky, androidx.compose.ui.graphics.Color.White)
                if (line.unread > 0) Badge(if (line.unread > 999) "999+" else "${line.unread}", if (muted) palette.quiet else palette.gold,
                                           if (muted) Color.White else palette.onGold)
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
