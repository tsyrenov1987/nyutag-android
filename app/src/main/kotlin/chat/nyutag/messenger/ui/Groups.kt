// Нютаг. Группы и люди: новая группа с выбором людей, «О группе» (участники, звук, выход) и карточка
// собеседника с блокировкой. Те же экраны, что на iOS.

package chat.nyutag.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.core.rooms.EastYear
import chat.nyutag.core.rooms.People
import chat.nyutag.core.rooms.Person
import chat.nyutag.core.rooms.Profile
import chat.nyutag.core.rooms.ProfileAbout
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.session.AppSession
import kotlinx.coroutines.launch

/** Выбор людей галочками из тех, с кем уже есть общие чаты; `hidden` — кого не показывать (уже в группе). */
@Composable
private fun PeoplePicker(session: AppSession, chosen: MutableList<String>, hidden: Set<String> = emptySet()) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var people by remember { mutableStateOf<List<Person>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { people = session.acquaintances().filter { it.userID !in hidden } }
    val all = people
    if (all == null) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        return
    }
    if (all.isEmpty()) {
        Text(words(P.groupNobody), color = palette.quiet, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 8.dp))
        Text(words(P.groupWhoHint), color = palette.quiet, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        return
    }
    if (all.size > 6) {
        FormCard {
            TextField(query, { query = it }, placeholder = { Text(words(P.searchPeople)) }, singleLine = true,
                      colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        }
    }
    val shown = all.filter { query.isBlank() || People.matches(query, it.name, it.userID) }
    FormCard {
        shown.forEachIndexed { index, person ->
            if (index > 0) FormDivider()
            val on = person.userID in chosen
            Row(Modifier.fillMaxWidth().clickable { if (on) chosen.remove(person.userID) else chosen.add(person.userID) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pillow(person.userID, person.name, 40.dp, person.avatarURL)
                Column(Modifier.weight(1f)) {
                    Text(person.name, color = palette.ink, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@" + ChatDigest.localpart(person.userID), color = palette.quiet, fontSize = 13.sp, maxLines = 1)
                }
                Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = palette.sky))
            }
        }
    }
    Text(words(P.groupWhoHint), color = palette.quiet, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
}

/** Новая группа: название и кого позвать. */
@Composable
fun GroupCreateScreen(session: AppSession, onClose: () -> Unit, onCreated: (String) -> Unit) {
    BackHandler(onBack = onClose)
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val ready = name.isNotBlank() && chosen.isNotEmpty() && !busy
    FormPage(words(P.groupNew), onClose, action = words(P.groupCreate), actionEnabled = ready, onAction = {
        busy = true
        failed = false
        scope.launch {
            val roomID = session.createChat(name, chosen.toList())
            busy = false
            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "группа ${if (roomID == null) "не создана" else "создана"}, людей ${chosen.size}")
            if (roomID == null) failed = true else onCreated(roomID)
        }
    }) {
        FormCard {
            TextField(name, { name = it }, placeholder = { Text(words(P.groupName)) }, singleLine = true, colors = fieldColors(),
                      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                      modifier = Modifier.fillMaxWidth())
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        if (failed) FailureNote(words(P.groupFailed))
        Text(words(P.groupWho).uppercase(), color = palette.quiet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(start = 8.dp))
        PeoplePicker(session, chosen)
    }
}

/** «О группе»: участники, позвать ещё, звук, выход. */
@Composable
fun GroupInfoScreen(session: AppSession, roomID: String, onClose: () -> Unit, onLeft: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val lines by session.lines.collectAsState()
    val mutedRooms by session.mutedRooms.collectAsState()
    val revision by session.revision.collectAsState()
    val line = lines.firstOrNull { it.id == roomID }
    var members by remember { mutableStateOf<List<Person>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<String?>(null) }
    var askLeave by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) { members = session.members(roomID) }
    person?.let { PersonScreen(session, it) { person = null }; return }
    if (adding) {
        AddMembersScreen(session, roomID, members.mapTo(HashSet()) { it.userID }) { added ->
            adding = false
            scope.launch { members = session.members(roomID) }
            if (added == false) failure = words(P.groupAddFailed)
        }
        return
    }
    BackHandler(onBack = onClose)
    val title = line?.title.orEmpty().ifEmpty { words(P.emptyChat) }
    FormPage(words(P.groupInfo), onClose, closeTitle = words(P.done)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pillow(roomID, title, 88.dp, line?.avatarURL)
            Text(title, color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(words(P.communityMembers, "${members.size + 1}"), color = palette.quiet, fontSize = 13.sp)
        }
        FormCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(words(P.mute), color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Switch(roomID in mutedRooms, { quiet -> scope.launch { if (!session.setMuted(roomID, quiet)) failure = words(P.cantDo) } },
                       colors = SwitchDefaults.colors(checkedTrackColor = palette.sky, checkedThumbColor = Color.White))
            }
        }
        Text(words(P.groupMembers).uppercase(), color = palette.quiet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(start = 8.dp))
        FormCard {
            Text("+  " + words(P.groupAdd), color = palette.sky, fontSize = 17.sp,
                 modifier = Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 16.dp, vertical = 14.dp))
            for (member in members) {
                FormDivider()
                Row(Modifier.fillMaxWidth().clickable { person = member.userID }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pillow(member.userID, member.name, 40.dp, member.avatarURL)
                    Column(Modifier.weight(1f)) {
                        Text(member.name, color = palette.ink, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@" + ChatDigest.localpart(member.userID), color = palette.quiet, fontSize = 13.sp, maxLines = 1)
                    }
                    Text("›", color = palette.quiet, fontSize = 22.sp)
                }
            }
        }
        failure?.let { FailureNote(it) }
        FormCard {
            Text(words(P.chatLeave), color = Danger, fontSize = 17.sp,
                 modifier = Modifier.fillMaxWidth().clickable { askLeave = true }.padding(horizontal = 16.dp, vertical = 14.dp))
        }
    }
    if (askLeave) LeaveDialog(session, roomID, onDismiss = { askLeave = false }, onFailed = { failure = words(P.chatLeaveFailed) }, onLeft = onLeft)
}

/** Позвать в группу ещё людей; `onDone(null)` — передумали, `true`/`false` — позвали всех или не всех. */
@Composable
private fun AddMembersScreen(session: AppSession, roomID: String, current: Set<String>, onDone: (Boolean?) -> Unit) {
    BackHandler { onDone(null) }
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val chosen = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    FormPage(words(P.groupAdd), { onDone(null) }, action = words(P.done), actionEnabled = chosen.isNotEmpty() && !busy, onAction = {
        busy = true
        scope.launch {
            val ok = session.invite(chosen.toList(), roomID)
            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "позвано в группу ${chosen.size}: ${if (ok) "все" else "не все"}")
            busy = false
            onDone(ok)
        }
    }) {
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        PeoplePicker(session, chosen, hidden = current)
    }
}

/** Вопрос перед выходом из чата: чат исчезнет из списка на всех устройствах. */
@Composable
fun LeaveDialog(session: AppSession, roomID: String, onDismiss: () -> Unit, onFailed: () -> Unit, onLeft: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, containerColor = palette.card,
                title = { Text(words(P.chatLeave), color = palette.ink, fontSize = 17.sp) },
                text = { Text(words(P.chatLeaveHint), color = palette.quiet, fontSize = 15.sp) },
                confirmButton = {
                    TextButton({
                        onDismiss()
                        scope.launch {
                            val ok = session.leaveChat(roomID)
                            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "выход из чата ${if (ok) "прошёл" else "не прошёл"}")
                            if (ok) onLeft() else onFailed()
                        }
                    }) { Text(words(P.chatLeave), color = Danger) }
                },
                dismissButton = { TextButton(onDismiss) { Text(words(P.cancel), color = palette.sky) } })
}

/** Карточка человека: имя, ник, «о себе» и блокировка. */
@Composable
fun PersonScreen(session: AppSession, userID: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val blocked by session.blocked.collectAsState()
    var profile by remember { mutableStateOf<Profile?>(null) }
    var askBlock by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(userID) { profile = session.profile(userID) }
    val name = profile?.displayName?.takeIf { it.isNotEmpty() } ?: ChatDigest.localpart(userID)
    val isBlocked = userID in blocked
    FormPage(words(P.profile), onClose, closeTitle = words(P.done)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pillow(userID, name, 88.dp, profile?.avatarURL)
            Text(name, color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(userID, color = palette.quiet, fontSize = 13.sp)
            if (isBlocked) Text("✋ " + words(P.blocked), color = palette.quiet, fontSize = 13.sp)
        }
        profile?.let { ProfileAbout(it) }?.takeIf { !it.isEmpty }?.let { about ->
            FormCard {
                var first = true
                for ((label, value) in listOf(words(P.profileAbout) to about.about, words(P.profileToonto) to about.toonto,
                                              words(P.profileUg) to about.ug,
                                              words(P.profileEastYear) to (EastYear.fromKey(about.eastYear)?.let(words::eastYear) ?: ""))) {
                    if (value.isEmpty()) continue
                    if (!first) FormDivider()
                    first = false
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, color = palette.quiet, fontSize = 13.sp)
                        Text(value, color = palette.ink, fontSize = 17.sp)
                    }
                }
            }
        }
        if (userID != session.me) {
            FormCard {
                if (isBlocked) {
                    Text(words(P.unblock), color = palette.ink, fontSize = 17.sp,
                         modifier = Modifier.fillMaxWidth().clickable { scope.launch { failed = !session.setBlocked(userID, false) } }
                             .padding(horizontal = 16.dp, vertical = 14.dp))
                } else {
                    Text(words(P.block), color = Danger, fontSize = 17.sp,
                         modifier = Modifier.fillMaxWidth().clickable { askBlock = true }.padding(horizontal = 16.dp, vertical = 14.dp))
                }
            }
        }
        if (failed) FailureNote(words(P.cantDo))
    }
    if (askBlock) {
        AlertDialog(onDismissRequest = { askBlock = false }, containerColor = palette.card,
                    title = { Text(words(P.blockAsk), color = palette.ink, fontSize = 17.sp) },
                    confirmButton = {
                        TextButton({
                            askBlock = false
                            scope.launch {
                                failed = !session.setBlocked(userID, true)
                                if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "блокировка ${if (failed) "не прошла" else "прошла"}")
                            }
                        }) { Text(words(P.block), color = Danger) }
                    },
                    dismissButton = { TextButton({ askBlock = false }) { Text(words(P.cancel), color = palette.sky) } })
    }
}
