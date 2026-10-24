// Нютаг. Сообщества, как на iOS: свои сверху, ниже каталог сервера с поиском. Сообщество — пространство Matrix
// (`m.space`), внутри него группы. Открытое пускает сразу, закрытое — по заявке, которую одобряет админ.

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.ChatLine
import chat.nyutag.core.rooms.CommunityLink
import chat.nyutag.core.rooms.Person
import chat.nyutag.core.rooms.PublicRoom
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.session.AppSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Что знаем о сообществе: из каталога или, для своего, из списка комнат. */
data class Community(
    val id: String,
    val name: String,
    val topic: String? = null,
    val members: Int? = null,
    val joinRule: String? = null,
    val avatar: String? = null,
) {
    val isClosed: Boolean get() = joinRule == "knock"

    companion object {
        fun of(room: PublicRoom) = Community(room.roomID, room.name ?: room.canonicalAlias.orEmpty(), room.topic,
                                             room.numJoinedMembers, room.joinRule, room.avatarURL?.toString())

        fun of(line: ChatLine) = Community(line.id, line.title, avatar = line.avatarURL)

        /** По id: сначала каталог (там описание и правило входа), потом свои. */
        fun find(id: String, session: AppSession): Community? =
            session.catalog.firstOrNull { it.roomID == id }?.let(::of)
                ?: session.communities.value.firstOrNull { it.id == id }?.let(::of)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunitiesScreen(session: AppSession, onCommunity: (String) -> Unit, onNew: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val mineAll by session.communities.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<PublicRoom>?>(session.catalog.takeIf { it.isNotEmpty() }) }
    var failed by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var reloads by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, reloads) {
        // Поиск идёт на сервер: ждём паузы в наборе, чтобы не дёргать каталог на каждую букву.
        if (query.isNotEmpty()) delay(400)
        // Сразу после запуска связь с ядром может ещё подниматься: повторяем, пока каталог не придёт.
        for (pause in listOf(0L, 2L, 4L, 8L, 15L)) {
            if (pause > 0) delay(pause * 1000)
            if (!isActive) return@LaunchedEffect
            val found = session.findCommunities(query)
            failed = found == null
            if (found != null) {
                catalog = found
                break
            }
        }
        refreshing = false
        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "сообществ в каталоге ${catalog?.size ?: -1}, своих ${mineAll.size}")
    }

    val mine = mineAll.filter { query.isEmpty() || it.title.contains(query.trim(), ignoreCase = true) }
    val mineIDs = mineAll.map { it.id }.toSet()
    val others = catalog.orEmpty().filter { it.roomID !in mineIDs }

    Column(Modifier.fillMaxSize().background(palette.milk)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(words(P.communities), color = palette.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("＋", color = palette.sky, fontSize = 28.sp,
                 modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onNew).padding(horizontal = 10.dp, vertical = 2.dp))
        }
        TextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(palette.card),
                  singleLine = true, colors = fieldColors(), placeholder = { Text(words(P.communitiesSearch)) },
                  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        PullToRefreshBox(refreshing, { refreshing = true; reloads++ }, Modifier.fillMaxSize().padding(top = 8.dp).clipToBounds()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mine.isNotEmpty()) {
                    header(words(P.communitiesMine))
                    items(mine, key = { "m" + it.id }) { line ->
                        val known = catalog?.firstOrNull { it.roomID == line.id }
                        CommunityRow(known?.let(Community::of) ?: Community.of(line)) { onCommunity(line.id) }
                    }
                }
                // При поиске пустой раздел «Все» не показываем, если нашлись свои: «Создайте первое» тут неверно.
                val searching = query.isNotBlank()
                if (others.isNotEmpty() || !searching || mine.isEmpty()) header(words(P.communitiesAll))
                if (others.isEmpty()) {
                    item {
                        when {
                            failed -> Note(words(P.communitiesFailed))
                            catalog == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            searching && mine.isNotEmpty() -> {}
                            searching -> Note(words(P.communitiesNotFound))
                            else -> Note(words(P.communitiesEmpty))
                        }
                    }
                }
                items(others, key = { "c" + it.roomID }) { room -> CommunityRow(Community.of(room)) { onCommunity(room.roomID) } }
            }
        }
    }
}

@Composable
private fun CommunityRow(community: Community, onClick: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pillow(community.id, community.name.ifEmpty { "·" }, size = 44.dp, avatar = community.avatar)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(community.name.ifEmpty { words(P.communities) }, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(community.members?.let { words(P.communityMembers, it.toString()) },
                                        if (community.isClosed) words(P.communityClosedBadge) else null)
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), color = palette.quiet, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Карточка сообщества: описание, вступить или выйти, список групп. */
@Composable
fun CommunityScreen(session: AppSession, spaceID: String, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mine by session.communities.collectAsState()
    var card by remember(spaceID) { mutableStateOf<Community?>(null) }
    val community = remember(spaceID, mine) { Community.find(spaceID, session) } ?: card ?: Community(spaceID, "")
    val joined = mine.any { it.id == spaceID }
    var groups by remember { mutableStateOf<List<PublicRoom>?>(null) }
    var canAdd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var facts by remember { mutableStateOf<AppSession.RoomFacts?>(null) }
    var managing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Person?>(null) }
    var profile by remember { mutableStateOf<String?>(null) }
    val revision by session.revision.collectAsState()
    val me = session.me.orEmpty()
    var allPeople by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<String?>(null) }
    suspend fun loadGroups() {
        val found = session.communityGroups(spaceID)
        groups = found?.first ?: groups ?: emptyList()
        canAdd = found?.second ?: false
        if (joined) session.facts(spaceID)?.let { facts = it }
    }
    LaunchedEffect(spaceID, joined, revision) {
        // По ссылке, а в каталоге его нет: название и правило входа спросим у сервера.
        if (card == null && Community.find(spaceID, session) == null) card = session.communityCard(spaceID)?.let(Community::of)
        loadGroups()
        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "сообщество: ${if (joined) "участник" else "гость"}, групп ${groups?.size}, добавлять ${if (canAdd) "можно" else "нельзя"}, людей ${facts?.roster?.joined?.size}")
    }
    profile?.let { PersonScreen(session, it) { profile = null }; return }
    if (managing) {
        RoomAdminScreen(session, spaceID, community = true, onClose = { managing = false }) { loadGroups() }
        return
    }
    BackHandler(onBack = onClose)
    val name = facts?.name?.ifEmpty { null } ?: community.name
    val topic = facts?.topic ?: community.topic
    val photo = facts?.avatarURL ?: community.avatar
    val closed = facts?.isClosed ?: community.isClosed

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } finally { busy = false }
        }
    }

    /** Общий чат — группа, где больше всего людей; если групп ещё нет, админ её создаёт. */
    fun write() = act {
        val general = groups?.maxByOrNull { it.numJoinedMembers }
        val roomID = general?.roomID?.takeIf { session.joinGroup(it) }
            ?: if (general == null && canAdd) session.createGroup(name.ifBlank { words(P.communityGeneralChat) }, spaceID)
                ?.also { groups = session.communityGroups(spaceID)?.first ?: groups } else null
        if (roomID != null) onOpen(roomID) else problem = true
    }

    FormPage(name, onClose, closeTitle = words(P.close)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pillow(community.id, name.ifEmpty { "·" }, size = 96.dp, avatar = photo)
            Text(name, color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            val count = facts?.roster?.joined?.size ?: community.members
            val badge = listOfNotNull((facts != null || community.joinRule != null).takeIf { it }?.let { words(if (closed) P.communityClosed else P.communityOpen) },
                                      count?.let { words(P.communityMembers, it.toString()) })
            if (badge.isNotEmpty()) Text(badge.joinToString(" · "), color = palette.quiet, fontSize = 13.sp)
            topic?.takeIf { it.isNotEmpty() }?.let {
                Text(it, color = palette.ink, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            if (joined) {
                Box(Modifier.padding(top = 4.dp, start = 40.dp, end = 40.dp)) {
                    BigButton(words(P.communityWrite), busy, enabled = !busy && (groups?.isNotEmpty() == true || canAdd)) { write() }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundAction(words(P.communityInvite), "＋", Modifier.weight(1f)) {
                        share(context, words(P.communityInviteText, name, CommunityLink.web(spaceID)))
                    }
                    if (facts?.let { canManage(it.powers, me) } == true) {
                        RoundAction(words(P.manage), "⚙", Modifier.weight(1f), badge = facts?.roster?.knocking?.size ?: 0) { managing = true }
                    }
                    RoundAction(words(P.communityLeave), "⇥", Modifier.weight(1f), tint = palette.quiet) { leaving = true }
                }
            } else if (!requested) {
                Box(Modifier.padding(top = 4.dp, start = 40.dp, end = 40.dp)) {
                    BigButton(words(if (community.isClosed) P.communityRequest else P.communityJoin), busy, enabled = !busy) {
                        act {
                            when (session.joinCommunity(spaceID, community.joinRule)) {
                                AppSession.CommunityJoin.JOINED -> problem = false
                                AppSession.CommunityJoin.REQUESTED -> { requested = true; problem = false }
                                AppSession.CommunityJoin.FAILED -> problem = true
                            }
                            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "вступление в сообщество: ${if (requested) "заявка" else if (problem) "не вышло" else "участник"}")
                        }
                    }
                }
            }
        }
        if (requested) FormCard { Text(words(P.communityRequested), color = palette.ink, fontSize = 15.sp, modifier = Modifier.padding(16.dp)) }
        if (problem) FailureNote(words(P.communityFailed))
        if (!joined && !requested) Text(words(P.communityJoinToWrite), color = palette.quiet, fontSize = 13.sp,
                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        Text(words(P.communityGroups).uppercase(), color = palette.quiet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(start = 8.dp))
        FormCard {
            val list = groups
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(words(P.communityNoGroups), color = palette.ink, fontSize = 16.sp)
                    Text(words(if (joined && canAdd) P.communityNoGroupsAdmin else P.communityNoGroupsHint), color = palette.quiet, fontSize = 13.sp)
                }
                else -> list.forEachIndexed { index, group ->
                    if (index > 0) FormDivider()
                    Row(Modifier.fillMaxWidth().clickable(enabled = joined && !busy) {
                            act { if (session.joinGroup(group.roomID)) onOpen(group.roomID) else problem = true }
                        }.padding(horizontal = 16.dp, vertical = 10.dp).alpha(if (joined) 1f else 0.6f),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pillow(group.roomID, group.name ?: "·", size = 40.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(group.name ?: words(P.emptyChat), color = palette.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(words(P.communityMembers, group.numJoinedMembers.toString()), color = palette.quiet, fontSize = 12.sp)
                        }
                        if (joined) Text("›", color = palette.sky, fontSize = 22.sp)
                    }
                }
            }
            if (joined && canAdd) {
                if (!groups.isNullOrEmpty()) FormDivider()
                Text("＋ " + words(P.communityGroupNew), color = palette.sky, fontSize = 16.sp,
                     modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { groupName = ""; naming = true }.padding(16.dp))
            }
        }
        if (joined && !groups.isNullOrEmpty()) Text(words(P.communityGroupsHint), color = palette.quiet, fontSize = 13.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp))
        val list = facts?.let(::byRole)
        if (joined && !list.isNullOrEmpty()) {
            Text(words(P.communityPeople, list.size.toString()).uppercase(), color = palette.quiet, fontSize = 13.sp,
                 fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            // Нажатие — меню, если права позволяют, иначе личный чат с человеком; себя не открываем.
            for (person in if (allPeople) list else list.take(8)) {
                PersonRow(person, opening == person.userID, roleNote(facts?.powers, person.userID, words)) {
                    if (memberHasActions(facts?.powers, me, person.userID)) { menu = person; return@PersonRow }
                    if (person.userID == session.me || opening != null) return@PersonRow
                    opening = person.userID
                    scope.launch {
                        val roomID = session.openDirect(person.userID)
                        opening = null
                        if (roomID != null) onOpen(roomID) else problem = true
                    }
                }
            }
            if (list.size > 8 && !allPeople) {
                Text(words(P.communityShowAll), color = palette.sky, fontSize = 16.sp,
                     modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { allPeople = true }.padding(16.dp))
            }
        }
    }

    val powers = facts?.powers
    menu?.let { target ->
        if (powers != null) MemberMenu(session, spaceID, powers, community = true, person = target, onDismiss = { menu = null },
                                       onProfile = { menu = null; profile = it }) { loadGroups() }
    }
    if (leaving) {
        AlertDialog({ leaving = false },
                    confirmButton = {
                        TextButton({
                            leaving = false
                            act { problem = !session.leaveCommunity(spaceID) }
                        }) { Text(words(P.communityLeave), color = androidx.compose.ui.graphics.Color(0xFFD03A2F)) }
                    },
                    dismissButton = { TextButton({ leaving = false }) { Text(words(P.cancel)) } },
                    title = { Text(words(P.communityLeaveAsk)) })
    }
    if (naming) {
        AlertDialog({ naming = false },
                    confirmButton = {
                        TextButton({
                            naming = false
                            val name = groupName.trim()
                            if (name.isNotEmpty()) act {
                                val roomID = session.createGroup(name, spaceID)
                                if (roomID == null) {
                                    problem = true
                                } else {
                                    groups = session.communityGroups(spaceID)?.first ?: groups
                                    onOpen(roomID)
                                }
                            }
                        }, enabled = groupName.isNotBlank()) { Text(words(P.communityCreate)) }
                    },
                    dismissButton = { TextButton({ naming = false }) { Text(words(P.cancel)) } },
                    title = { Text(words(P.communityGroupNew)) },
                    text = {
                        TextField(groupName, { groupName = it }, singleLine = true, placeholder = { Text(words(P.communityName)) })
                    })
    }
}

/** Новое сообщество: название, описание, открытое или по заявке. */
@Composable
fun NewCommunityScreen(session: AppSession, onClose: () -> Unit, onCreated: (String) -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    FormPage(words(P.communityNew), onClose) {
        FormCard {
            TextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(),
                      placeholder = { Text(words(P.communityName)) })
            FormDivider()
            TextField(about, { about = it }, Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 5, colors = fieldColors(),
                      placeholder = { Text(words(P.communityAbout)) })
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.plate).padding(3.dp)) {
            for (choice in listOf(true, false)) {
                val selected = open == choice
                Text(words(if (choice) P.communityOpen else P.communityClosed), fontSize = 15.sp, textAlign = TextAlign.Center,
                     color = palette.ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                     modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                         .background(if (selected) palette.card else androidx.compose.ui.graphics.Color.Transparent)
                         .clickable { open = choice }.padding(vertical = 8.dp))
            }
        }
        Text(words(if (open) P.communityOpenHint else P.communityClosedHint), color = palette.quiet, fontSize = 13.sp,
             modifier = Modifier.padding(horizontal = 8.dp))
        if (problem) FailureNote(words(P.communityFailed))
        BigButton(words(P.communityCreate), busy, enabled = name.isNotBlank() && !busy) {
            busy = true
            scope.launch {
                val id = session.createCommunity(name.trim(), about, open, name.trim())
                busy = false
                if (id == null) problem = true else onCreated(id)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LocalPalette.current.quiet, fontSize = 15.sp, textAlign = TextAlign.Center,
         modifier = Modifier.fillMaxWidth().padding(16.dp))
}

private fun LazyListScope.header(title: String) {
    item(key = "h$title") {
        Text(title.uppercase(), color = LocalPalette.current.quiet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(start = 8.dp, top = 8.dp))
    }
}
