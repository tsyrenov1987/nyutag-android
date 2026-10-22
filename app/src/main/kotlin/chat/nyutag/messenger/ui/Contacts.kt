// Нютаг. Вкладка «Контакты»: сверху те, кто в Нютаге (собеседники и найденные по номерам из книжки), ниже
// знакомые из книжки, которых можно позвать. Поиск ищет по имени, нику и номеру, а людей вне книжки спрашивает
// у сервера. Касание человека открывает личный чат, нового или уже существующего.

package chat.nyutag.messenger.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.BookEntry
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.core.rooms.People
import chat.nyutag.core.rooms.Person
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.session.AppSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Телефонная книга: доступ и записи с номерами. Читаем в фоне, список держим только в памяти. */
object PhoneBook {
    private const val ASKED = "nyutag.book.asked"

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Спросить ещё можно: ни разу не спрашивали или система разрешает спросить снова. Иначе — только настройки. */
    fun canAsk(activity: Activity): Boolean =
        !prefs(activity).getBoolean(ASKED, false) || activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)

    fun noteAsked(context: Context) = prefs(context).edit().putBoolean(ASKED, true).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("nyutag.ui", Context.MODE_PRIVATE)

    suspend fun read(context: Context): List<BookEntry> = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext emptyList()
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                              ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                              ContactsContract.CommonDataKinds.Phone.NUMBER)
        val names = LinkedHashMap<String, String>()
        val phones = LinkedHashMap<String, MutableList<String>>()
        runCatching {
            context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val number = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    names.putIfAbsent(id, cursor.getString(1).orEmpty())
                    val list = phones.getOrPut(id) { ArrayList() }
                    if (number !in list) list.add(number)
                }
            }
        }
        phones.map { (id, list) -> BookEntry(id, names[id]?.takeIf { it.isNotBlank() } ?: list[0], list) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(session: AppSession, onOpen: (String) -> Unit, onInvite: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val revision by session.revision.collectAsState()
    var canRead by remember { mutableStateOf(PhoneBook.canRead(context)) }
    var canAsk by remember { mutableStateOf(PhoneBook.canAsk(activity)) }
    var inNyutag by remember { mutableStateOf<List<Person>>(emptyList()) }
    var invite by remember { mutableStateOf<List<BookEntry>>(emptyList()) }
    var found by remember { mutableStateOf<List<Person>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var reloads by remember { mutableIntStateOf(0) }
    var fresh by remember { mutableStateOf(false) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PhoneBook.noteAsked(context)
        canRead = granted
        canAsk = PhoneBook.canAsk(activity)
        fresh = true
        reloads++
    }

    LaunchedEffect(revision, reloads) {
        canRead = PhoneBook.canRead(context)
        val book = PhoneBook.read(context)
        val known = session.knownPeople()
        val matches = if (canRead) session.lookup(People.uniquePhones(book), fresh) else emptyMap()
        fresh = false
        val merged = People.merge(book, matches, known)
        if (merged.inNyutag != inNyutag) inNyutag = merged.inNyutag
        if (merged.invite != invite) invite = merged.invite
        refreshing = false
        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "контакты: книжка ${if (canRead) book.size else "закрыта"}, в Нютаге ${inNyutag.size}, позвать ${invite.size}")
    }

    LaunchedEffect(query) {
        val term = query.trim()
        if (term.length < 2) {
            found = emptyList()
            searched = query
            return@LaunchedEffect
        }
        delay(350)
        val result = session.searchPeople(term.removePrefix("@"))
        if (!isActive) return@LaunchedEffect
        found = result
        searched = query
        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "поиск «$term»: найдено ${result.size}")
    }

    fun write(person: Person) {
        if (opening != null) return
        opening = person.userID
        scope.launch {
            val roomID = session.openDirect(person.userID)
            opening = null
            if (roomID != null) onOpen(roomID) else failed = true
        }
    }

    val shownPeople = inNyutag.filter { People.matches(query, it.name, it.userID, listOfNotNull(it.phone)) }
    val shownIDs = shownPeople.map { it.userID }.toSet()
    val shownFound = if (query.trim().length >= 2) found.filter { it.userID !in shownIDs } else emptyList()
    val shownInvite = invite.filter { People.matches(query, it.name, phones = it.phones) }
    val isPhone = query.count(Char::isDigit) >= 10 && People.phoneKey(query) != null
    val nobody = query.isNotEmpty() && shownPeople.isEmpty() && shownFound.isEmpty() && shownInvite.isEmpty() && searched == query

    Column(Modifier.fillMaxSize().background(palette.milk)) {
        Text(words(P.contacts), color = palette.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold,
             modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp))
        TextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(palette.card),
                  singleLine = true, colors = fieldColors(), placeholder = { Text(words(P.searchPeople)) },
                  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        PullToRefreshBox(refreshing, { refreshing = true; fresh = true; reloads++ }, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!canRead && query.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).padding(16.dp),
                               verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(words(P.bookTitle), color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            if (canAsk) {
                                Text(words(P.bookLead), color = palette.quiet, fontSize = 15.sp)
                                BigButton(words(P.bookAllow), busy = false, enabled = true) { ask.launch(Manifest.permission.READ_CONTACTS) }
                            } else {
                                Text(words(P.bookDenied), color = palette.quiet, fontSize = 15.sp)
                                Text(words(P.openSettings), color = palette.sky, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                     modifier = Modifier.clickable {
                                         context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                                      Uri.fromParts("package", context.packageName, null)))
                                     }.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
                if (shownPeople.isNotEmpty()) {
                    header(words(P.inNyutag))
                    items(shownPeople, key = { "p" + it.userID }) { PersonRow(it, opening == it.userID) { write(it) } }
                } else if (query.isEmpty()) {
                    item {
                        Text(words(P.peopleEmpty), color = palette.quiet, fontSize = 15.sp, textAlign = TextAlign.Center,
                             modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
                if (shownFound.isNotEmpty()) {
                    header(words(P.foundByName))
                    items(shownFound, key = { "f" + it.userID }) { PersonRow(it, opening == it.userID) { write(it) } }
                }
                if (shownInvite.isNotEmpty()) {
                    header(words(P.callToNyutag))
                    items(shownInvite, key = { "b" + it.id }) { InviteRow(it) }
                }
                if (nobody) {
                    item {
                        Text(words(P.nobodyFound), color = palette.quiet, fontSize = 15.sp, textAlign = TextAlign.Center,
                             modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                    if (isPhone) {
                        item {
                            Text("＋ " + words(P.phoneNotInNyutag), color = palette.sky, fontSize = 16.sp,
                                 modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card)
                                     .clickable(onClick = onInvite).padding(16.dp))
                        }
                    }
                }
            }
        }
    }
    if (failed) {
        AlertDialog({ failed = false }, confirmButton = { TextButton({ failed = false }) { Text(words(P.done)) } },
                    title = { Text(words(P.cantOpenChat)) })
    }
}

private fun LazyListScope.header(title: String) {
    item(key = "h$title") {
        Text(title.uppercase(), color = LocalPalette.current.quiet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(start = 8.dp, top = 8.dp))
    }
}

@Composable
fun PersonRow(person: Person, busy: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pillow(person.userID, person.name, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(person.name, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@" + ChatDigest.localpart(person.userID), color = palette.quiet, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun InviteRow(entry: BookEntry) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.alpha(0.55f)) { Pillow(entry.id, entry.name, size = 44.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, color = palette.ink, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.phones[0], color = palette.quiet, fontSize = 13.sp, maxLines = 1)
        }
        Text(words(P.inviteShort), color = palette.sky, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { share(context, words(P.inviteText)) }.padding(8.dp))
    }
}

/** Системное «Поделиться» с готовым текстом. */
fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}
