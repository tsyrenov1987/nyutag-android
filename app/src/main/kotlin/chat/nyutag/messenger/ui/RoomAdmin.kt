// Нютаг. Управление группой и сообществом, как на iOS: оформление (фото, название, описание), «пишут только админы»
// или вход по заявке, ссылка-приглашение, заявки, заблокированные. И меню участника: назначить или снять админа,
// удалить, заблокировать. Что показывать, решают права из `m.room.power_levels` (RoomPowers).

package chat.nyutag.messenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.CommunityLink
import chat.nyutag.core.rooms.Person
import chat.nyutag.core.rooms.RoomPowers
import chat.nyutag.core.sync.EventType
import chat.nyutag.messenger.data.Pictures
import chat.nyutag.messenger.session.AppSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что-то из управления доступно: показывать кнопку «Изменить» или «Управление». */
fun canManage(powers: RoomPowers, me: String): Boolean =
    listOf(EventType.NAME, EventType.AVATAR, EventType.TOPIC, EventType.POWER_LEVELS, EventType.JOIN_RULES)
        .any { powers.canSetState(me, it) } || powers.canBan(me, "")

/** Пометка роли справа в списке участников. */
fun roleNote(powers: RoomPowers?, userID: String, words: Words): String? = when (powers?.role(userID)) {
    RoomPowers.Role.OWNER -> words(P.roleOwner)
    RoomPowers.Role.ADMIN -> words(P.communityAdmin)
    RoomPowers.Role.MODERATOR -> words(P.roleModerator)
    else -> null
}

/** Участники: сначала владелец и админы, дальше по имени. */
fun byRole(facts: AppSession.RoomFacts): List<Person> =
    facts.roster.joined.sortedByDescending { facts.powers.level(it.userID) }

/** Есть ли что сделать с участником, кроме профиля. */
fun memberHasActions(powers: RoomPowers?, me: String, userID: String): Boolean {
    if (powers == null || userID == me) return false
    return powers.canKick(me, userID) || powers.canBan(me, userID) ||
        powers.canSetLevel(me, userID, RoomPowers.ADMIN) || powers.canSetLevel(me, userID, 0)
}

/** Кнопка под шапкой: значок и подпись; `badge` — число в углу (новые заявки). */
@Composable
fun RoundAction(title: String, glyph: String, modifier: Modifier = Modifier, tint: Color = LocalPalette.current.sky,
                badge: Int = 0, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(modifier) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.card).clickable(onClick = onClick)
                   .padding(vertical = 10.dp, horizontal = 4.dp),
               horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(glyph, color = tint, fontSize = 20.sp)
            Text(title, color = tint, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
        }
        if (badge > 0) {
            Text("$badge", color = palette.onGold, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(palette.gold)
                     .padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

/** Меню участника: профиль, назначить или снять админа, удалить, заблокировать (с вопросом). */
@Composable
fun MemberMenu(session: AppSession, roomID: String, powers: RoomPowers, community: Boolean, person: Person,
               onDismiss: () -> Unit, onProfile: (String) -> Unit, onChanged: suspend () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val me = session.me.orEmpty()
    var banning by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun run(work: suspend () -> Boolean) {
        scope.launch {
            if (work()) { onChanged(); onDismiss() } else failed = true
        }
    }

    when {
        failed -> AlertDialog(onDismissRequest = onDismiss, containerColor = palette.card,
                              title = { Text(words(P.cantDo), color = palette.ink, fontSize = 17.sp) },
                              confirmButton = { TextButton(onDismiss) { Text(words(P.done), color = palette.sky) } })
        banning -> AlertDialog(onDismissRequest = onDismiss, containerColor = palette.card,
                               title = { Text(words(P.banAsk, person.name), color = palette.ink, fontSize = 17.sp) },
                               confirmButton = {
                                   TextButton({ run { session.ban(person.userID, roomID, community) } }) { Text(words(P.banMember), color = Danger) }
                               },
                               dismissButton = { TextButton(onDismiss) { Text(words(P.cancel), color = palette.sky) } })
        else -> AlertDialog(onDismissRequest = onDismiss, containerColor = palette.card,
                            title = { Text(person.name, color = palette.ink, fontSize = 17.sp) },
                            text = {
                                Column {
                                    MenuItem(words(P.profile), palette.ink) { onProfile(person.userID) }
                                    val role = powers.role(person.userID)
                                    if (role < RoomPowers.Role.ADMIN && powers.canSetLevel(me, person.userID, RoomPowers.ADMIN)) {
                                        MenuItem(words(P.makeAdmin), palette.ink) { run { session.setLevel(person.userID, RoomPowers.ADMIN, roomID) } }
                                    }
                                    if ((role == RoomPowers.Role.ADMIN || role == RoomPowers.Role.MODERATOR) && powers.canSetLevel(me, person.userID, 0)) {
                                        MenuItem(words(P.removeAdmin), palette.ink) { run { session.setLevel(person.userID, 0, roomID) } }
                                    }
                                    if (powers.canKick(me, person.userID)) {
                                        MenuItem(words(if (community) P.removeFromCommunity else P.removeFromGroup), Danger) {
                                            run { session.remove(person.userID, roomID, community) }
                                        }
                                    }
                                    if (powers.canBan(me, person.userID)) MenuItem(words(P.banMember), Danger) { banning = true }
                                }
                            },
                            confirmButton = { TextButton(onDismiss) { Text(words(P.cancel), color = palette.sky) } })
    }
}

@Composable
private fun MenuItem(title: String, color: Color, onClick: () -> Unit) {
    Text(title, color = color, fontSize = 17.sp,
         modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp))
}

/** Подпись над карточкой и сноска под ней, как секции списка на iOS. */
@Composable
private fun AdminSection(title: String?, footer: String? = null, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let {
            Text(it.uppercase(), color = palette.quiet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
        content()
        footer?.let { Text(it, color = palette.quiet, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
    }
}

/** Управление группой или сообществом. Заявки и участники обновляются с синхронизацией; набранное название не трогаем. */
@Composable
fun RoomAdminScreen(session: AppSession, roomID: String, community: Boolean, onClose: () -> Unit, onChanged: suspend () -> Unit = {}) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by session.revision.collectAsState()
    val me = session.me.orEmpty()
    var facts by remember { mutableStateOf<AppSession.RoomFacts?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val dirty = facts?.let { it.name != name || it.topic != topic } ?: false
    BackHandler(onBack = onClose)

    suspend fun load() {
        val fresh = session.facts(roomID)
        if (fresh == null) { unavailable = facts == null; return }
        if (facts == null || !dirty) { name = fresh.name; topic = fresh.topic }
        facts = fresh
    }
    LaunchedEffect(revision) { load() }

    fun change(work: suspend () -> Boolean) {
        scope.launch {
            if (!work()) failed = true
            load()
            onChanged()
        }
    }

    /** Снимок → квадрат 512 px → на сервер. `null` — убрать фото. */
    fun setPhoto(picked: ByteArray?) {
        photoBusy = true
        scope.launch {
            val jpeg = picked?.let { withContext(Dispatchers.Default) { runCatching { Pictures.squareJPEG(it) }.getOrNull() } }
            if (picked != null && jpeg == null) failed = true else if (!session.setRoomPhoto(jpeg, roomID)) failed = true
            photoBusy = false
            load()
            onChanged()
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (data == null) failed = true else setPhoto(data)
        }
    }

    fun save() {
        val current = facts ?: return
        busy = true
        scope.launch {
            var ok = true
            if (name != current.name) ok = session.setRoomName(name, roomID) && ok
            if (topic != current.topic) ok = session.setRoomTopic(topic, roomID) && ok
            busy = false
            onChanged()
            if (ok) onClose() else { failed = true; load() }
        }
    }

    FormPage(words(P.manage), onClose, closeTitle = words(if (dirty) P.cancel else P.done),
             action = if (dirty) words(P.save) else null, actionEnabled = !busy && name.isNotBlank(), onAction = ::save) {
        val current = facts
        if (current == null) {
            if (unavailable) FailureNote(words(P.cantDo))
            else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@FormPage
        }
        fun can(type: String) = current.powers.canSetState(me, type)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Pillow(roomID, name.ifEmpty { "·" }, 88.dp, current.avatarURL)
                if (photoBusy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
            }
            if (can(EventType.AVATAR)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(words(P.roomPhotoChange), color = palette.sky, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.clip(RoundedCornerShape(12.dp))
                             .clickable(enabled = !photoBusy) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                             .padding(horizontal = 12.dp, vertical = 8.dp))
                    if (current.avatarURL != null) {
                        Text(words(P.roomPhotoRemove), color = Danger, fontSize = 16.sp,
                             modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !photoBusy) { setPhoto(null) }
                                 .padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        FormCard {
            TextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), enabled = can(EventType.NAME),
                      placeholder = { Text(words(P.communityName)) })
            FormDivider()
            TextField(topic, { topic = it }, Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 6, colors = fieldColors(),
                      enabled = can(EventType.TOPIC), placeholder = { Text(words(if (community) P.communityAbout else P.groupAbout)) })
        }
        if (community) {
            AdminSection(words(P.access), words(if (current.isClosed) P.communityClosedHint else P.communityOpenHint)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.plate).padding(3.dp)) {
                    for (closed in listOf(false, true)) {
                        val selected = current.isClosed == closed
                        Text(words(if (closed) P.communityClosed else P.communityOpen), fontSize = 15.sp, textAlign = TextAlign.Center,
                             color = palette.ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                             modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                 .background(if (selected) palette.card else Color.Transparent)
                                 .clickable(enabled = !selected && can(EventType.JOIN_RULES)) { change { session.setClosed(closed, roomID) } }
                                 .padding(vertical = 8.dp))
                    }
                }
            }
            val link = CommunityLink.web(roomID)
            AdminSection(words(P.inviteLink), words(P.inviteLinkHint)) {
                FormCard {
                    Text(link, color = palette.quiet, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    FormDivider()
                    Text(words(P.communityInvite), color = palette.sky, fontSize = 17.sp,
                         modifier = Modifier.fillMaxWidth().clickable { share(context, words(P.communityInviteText, name, link)) }
                             .padding(horizontal = 16.dp, vertical = 14.dp))
                    FormDivider()
                    Text(words(P.copyLink) + if (copied) "  ✓" else "", color = palette.sky, fontSize = 17.sp,
                         modifier = Modifier.fillMaxWidth().clickable { copyLink(context, link); copied = true }
                             .padding(horizontal = 16.dp, vertical = 14.dp))
                }
            }
            val knocking = current.roster.knocking
            if (current.isClosed || knocking.isNotEmpty()) {
                AdminSection(words(P.requests, knocking.size.toString())) {
                    if (knocking.isEmpty()) {
                        FormCard { Text(words(P.requestsNone), color = palette.quiet, fontSize = 15.sp, modifier = Modifier.padding(16.dp)) }
                    }
                    for (person in knocking) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { PersonRow(person) {} }
                            if (current.powers.canInvite(me)) {
                                Text("✕", color = palette.quiet, fontSize = 22.sp,
                                     modifier = Modifier.clip(CircleShape).clickable { change { session.answerRequest(person.userID, roomID, accept = false) } }
                                         .padding(10.dp))
                                Text("✓", color = palette.sky, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                     modifier = Modifier.clip(CircleShape).clickable { change { session.answerRequest(person.userID, roomID, accept = true) } }
                                         .padding(10.dp))
                            }
                        }
                    }
                }
            }
        } else {
            AdminSection(null, words(P.onlyAdminsWriteHint)) {
                FormCard {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📣  " + words(P.onlyAdminsWrite), color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Switch(current.powers.onlyAdminsWrite, { on -> change { session.setOnlyAdminsWrite(on, roomID) } },
                               enabled = can(EventType.POWER_LEVELS),
                               colors = SwitchDefaults.colors(checkedTrackColor = palette.sky, checkedThumbColor = Color.White))
                    }
                }
            }
        }
        if (current.roster.banned.isNotEmpty()) {
            AdminSection(words(P.bannedSection)) {
                for (person in current.roster.banned) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { PersonRow(person) {} }
                        if (current.powers.canBan(me, person.userID)) {
                            Text(words(P.unban), color = palette.sky, fontSize = 15.sp,
                                 modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { change { session.unban(person.userID, roomID, community) } }
                                     .padding(8.dp))
                        }
                    }
                }
            }
        }
    }
    if (failed) {
        AlertDialog(onDismissRequest = { failed = false }, containerColor = palette.card,
                    title = { Text(words(P.cantDo), color = palette.ink, fontSize = 17.sp) },
                    confirmButton = { TextButton({ failed = false }) { Text(words(P.done), color = palette.sky) } })
    }
}

private fun copyLink(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Нютаг", text))
}
