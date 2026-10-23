// Нютаг. Меню под своим аватаром, как MeMenu на iOS: профиль, «Избранное», приглашение, язык; выход и удаление
// аккаунта и список устройств спрятаны в «Дополнительно». Отсюда же правка профиля (имя, «о себе», номер, восточный год).

package chat.nyutag.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.core.rooms.EastYear
import chat.nyutag.core.rooms.People
import chat.nyutag.core.rooms.Profile
import chat.nyutag.core.rooms.ProfileAbout
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.data.Pictures
import chat.nyutag.messenger.session.AppSession
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val Danger = Color(0xFFD03A2F)

@Composable
fun MeScreen(session: AppSession, onClose: () -> Unit, onOpen: (String) -> Unit, onInvite: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("") }
    var askSignOut by remember { mutableStateOf(false) }
    when (page) {
        "profile" -> { ProfileEditScreen(session) { page = "" }; return }
        "delete" -> { DeleteAccountScreen(session) { page = "" }; return }
        "devices" -> { DevicesScreen(session) { page = "" }; return }
        "wallpaper" -> { WallpaperPicker(null) { page = "" }; return }
        "donate" -> { DonateScreen { page = "" }; return }
    }
    BackHandler(onBack = onClose)
    val me = session.me.orEmpty()
    val myAvatar by session.myAvatar.collectAsState()
    // Имя из профиля; пока не загрузилось (или не задано) — логин.
    var myName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(me) { myName = session.profile(me)?.displayName?.trim()?.ifEmpty { null } }
    FormPage("", onClose, closeTitle = words(P.done)) {
        FormCard {
            Row(Modifier.fillMaxWidth().clickable { page = "profile" }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Pillow(me, myName ?: ChatDigest.localpart(me), 64.dp, myAvatar)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(myName ?: ChatDigest.localpart(me), color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(me, color = palette.quiet, fontSize = 13.sp)
                }
                Text("›", color = palette.quiet, fontSize = 22.sp)
            }
        }
        FormCard {
            MenuRow(words(P.savedMessages), palette.sky) {
                scope.launch { session.openSaved(words(P.savedMessages), words(P.savedMessagesTopic))?.let { onClose(); onOpen(it) } }
            }
            FormDivider()
            MenuRow(words(P.inviteFriend), palette.sky) { onInvite() }
            FormDivider()
            MenuRow(words(P.wallpaper), palette.sky) { page = "wallpaper" }
            FormDivider()
            MenuRow(words(P.donate), palette.gold) { page = "donate" }
        }
        Section(words(P.language), footer = words(P.buryatLettersHint)) {
            FormCard {
                Lang.entries.forEachIndexed { index, lang ->
                    if (index > 0) FormDivider()
                    Row(Modifier.fillMaxWidth().clickable { words.choose(lang) }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(lang.ownName, color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (words.lang == lang) Text("✓", color = palette.sky, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                FormDivider()
                Row(Modifier.fillMaxWidth().clickable { words.chooseLetters(!words.buryatLetters) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(words(P.buryatLetters), color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Switch(words.buryatLetters, words::chooseLetters,
                           colors = SwitchDefaults.colors(checkedTrackColor = palette.gold, checkedThumbColor = Color.White))
                }
            }
        }
        Section(words(P.advanced)) {
            FormCard {
                MenuRow(words(P.devices), palette.ink) { page = "devices" }
                FormDivider()
                MenuRow(words(P.signOut), palette.ink) { askSignOut = true }
                FormDivider()
                MenuRow(words(P.deleteAccount), Danger) { page = "delete" }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NyutagLogo(44.dp)
            Text("Нютаг ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = palette.quiet, fontSize = 13.sp)
        }
    }
    if (askSignOut) {
        AlertDialog(onDismissRequest = { askSignOut = false }, containerColor = palette.card,
                    title = { Text(words(P.signOutAsk), color = palette.ink, fontSize = 17.sp) },
                    confirmButton = {
                        TextButton({ askSignOut = false; onClose(); scope.launch { session.signOut() } }) {
                            Text(words(P.signOut), color = Danger)
                        }
                    },
                    dismissButton = { TextButton({ askSignOut = false }) { Text(words(P.cancel), color = palette.sky) } })
    }
}

@Composable
private fun MenuRow(title: String, color: Color, onClick: () -> Unit) {
    Text(title, color = color, fontSize = 17.sp,
         modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp))
}

/** Подпись над карточкой и необязательная сноска под ней, как секции списка на iOS. */
@Composable
private fun Section(title: String?, footer: String? = null, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let {
            Text(it.uppercase(), color = palette.quiet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.padding(start = 8.dp))
        }
        content()
        footer?.let { Text(it, color = palette.quiet, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
    }
}

@Composable
private fun ProfileEditScreen(session: AppSession, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf<Profile?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    var toonto by rememberSaveable { mutableStateOf("") }
    var ug by rememberSaveable { mutableStateOf("") }
    var eastYear by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var hasPhone by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val myAvatar by session.myAvatar.collectAsState()
    var photoBusy by remember { mutableStateOf(false) }
    var photoFailed by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }

    /** Снимок → квадрат 512 px → на сервер. `null` — убрать аватар. */
    fun setAvatar(picked: ByteArray?) {
        photoBusy = true
        photoFailed = false
        scope.launch {
            val jpeg = picked?.let { withContext(Dispatchers.Default) { runCatching { Pictures.squareJPEG(it) }.getOrNull() } }
            photoFailed = if (picked != null && jpeg == null) true else !session.setAvatar(jpeg)
            photoBusy = false
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (data == null) photoFailed = true else setAvatar(data)
        }
    }

    LaunchedEffect(Unit) {
        val me = session.me ?: return@LaunchedEffect
        val profile = session.profile(me) ?: return@LaunchedEffect
        if (old == null && name.isEmpty()) {
            val fields = ProfileAbout(profile)
            name = profile.displayName ?: ChatDigest.localpart(me)
            about = fields.about
            toonto = fields.toonto
            ug = fields.ug
            eastYear = fields.eastYear
        }
        old = profile
        hasPhone = session.hasPhone()
        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "профиль загружен: о себе ${if (ProfileAbout(profile).isEmpty) "пусто" else "есть"}, номер ${hasPhone ?: "?"}")
    }

    if (viewing) {
        myAvatar?.let { AvatarViewer(session, it, name) { viewing = false }; return }
    }
    if (picking) {
        EastYearPicker(eastYear, { eastYear = it; picking = false }) { picking = false }
        return
    }
    BackHandler(onBack = onClose)

    fun save() {
        val before = old ?: return
        busy = true
        scope.launch {
            failed = !session.saveProfile(name, ProfileAbout(about, toonto, ug, eastYear), before)
            val number = phone.trim()
            if (!failed && number.isNotEmpty()) {
                if (People.phoneKey(number) != null && session.setPhone(number)) {
                    hasPhone = true
                    phone = ""
                } else {
                    failed = true
                }
            }
            busy = false
            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "профиль ${if (failed) "не сохранён" else "сохранён"}")
            if (!failed) onClose()
        }
    }

    val ready = old != null
    FormPage(words(P.profile), onClose, action = words(P.save), actionEnabled = ready && !busy, onAction = ::save) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.clip(RoundedCornerShape(33.dp)).clickable(enabled = myAvatar != null) { viewing = true },
                contentAlignment = Alignment.Center) {
                Pillow(session.me.orEmpty(), name.ifEmpty { ChatDigest.localpart(session.me.orEmpty()) }, 96.dp, myAvatar)
                if (photoBusy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(words(P.avatarChange), color = palette.sky, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.clip(RoundedCornerShape(12.dp))
                         .clickable(enabled = !photoBusy) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                         .padding(horizontal = 12.dp, vertical = 8.dp))
                if (myAvatar != null) {
                    Text(words(P.avatarRemove), color = Danger, fontSize = 16.sp,
                         modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !photoBusy) { setAvatar(null) }
                             .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            if (photoFailed) FailureNote(words(P.avatarFailed))
        }
        Section(words(P.name)) {
            FormCard { FormField(name, { name = it }, words(P.name)) }
        }
        Section(words(P.profileAbout)) {
            FormCard {
                TextField(about, { about = it.take(ProfileAbout.MAX_ABOUT) }, Modifier.fillMaxWidth(), enabled = ready, minLines = 2, maxLines = 6,
                          colors = fieldColors(), placeholder = { Text(words(P.profileAboutPlaceholder)) })
            }
        }
        Section(words(P.profileToonto)) {
            FormCard { FormField(toonto, { toonto = it.take(ProfileAbout.MAX_SHORT) }, words(P.profileToontoPlaceholder)) }
        }
        Section(words(P.profileUg)) {
            FormCard { FormField(ug, { ug = it.take(ProfileAbout.MAX_SHORT) }, words(P.profileUgPlaceholder)) }
        }
        Section(words(P.myPhone), words(if (hasPhone == true) P.myPhoneSet else P.myPhoneHint)) {
            FormCard {
                FormField(phone, { phone = it }, words(if (hasPhone == true) P.myPhoneChange else P.myPhonePlaceholder),
                          keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done))
            }
        }
        Section(null, if (failed) words(P.profileSaveFailed) else null) {
            FormCard {
                Row(Modifier.fillMaxWidth().clickable(enabled = ready) { picking = true }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(words(P.profileEastYear), color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text(EastYear.fromKey(eastYear)?.let(words::eastYear) ?: words(P.profileEastYearNone), color = palette.quiet, fontSize = 17.sp)
                }
            }
        }
    }
}

/** Год выбирают по числу, на сервер уходит стихия и животное. */
@Composable
private fun EastYearPicker(key: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    BackHandler(onBack = onClose)
    @Composable
    fun row(title: String, chosen: Boolean, value: String) {
        Row(Modifier.fillMaxWidth().clickable { onPick(value) }.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(title, color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (chosen) Text("✓", color = palette.sky, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
    FormPage(words(P.profileEastYear), onClose) {
        Section(null, words(P.profileEastYearHint)) {
            FormCard { row(words(P.profileEastYearNone), key.isEmpty(), "") }
        }
        FormCard {
            for (year in LocalDate.now().year downTo 1925) {
                val east = EastYear.ofYear(year)
                if (year != LocalDate.now().year) FormDivider()
                row("$year · ${words.eastYear(east)}", east.key == key, east.key)
            }
        }
    }
}

/** Удаление аккаунта: предупреждение, пароль, красная кнопка. После удаления приложение само уходит на экран входа. */
@Composable
private fun DeleteAccountScreen(session: AppSession, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    FormPage(words(P.deleteAccount), onClose) {
        Text(words(P.deleteAccountLead), color = palette.ink, fontSize = 16.sp)
        Text(words(P.deleteAccountPassword), color = palette.quiet, fontSize = 13.sp)
        FormCard {
            FormField(password, { password = it }, words(P.password), secret = true,
                      keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
        }
        failure?.let { FailureNote(it) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Danger.copy(alpha = if (busy || password.isEmpty()) 0.45f else 1f))
                .clickable(enabled = !busy && password.isNotEmpty()) {
                    busy = true
                    failure = null
                    scope.launch {
                        try {
                            session.deleteAccount(password)
                        } catch (e: AppSession.SignInException) {
                            failure = words(when (e.failure) {
                                AppSession.SignInFailure.WRONG_PASSWORD -> P.wrongPassword
                                AppSession.SignInFailure.NO_NETWORK -> P.noNetwork
                                else -> P.deleteAccountFailed
                            })
                            busy = false
                        }
                    }
                }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center) {
            Text(words(P.deleteAccount), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.padding(4.dp))
    }
}

/** Аватар во весь экран: щипок увеличивает до четырёх раз, касание сбрасывает или закрывает. */
@Composable
fun AvatarViewer(session: AppSession, url: String, title: String, onClose: () -> Unit) {
    val words = LocalWords.current
    BackHandler(onBack = onClose)
    var zoom by remember { mutableFloatStateOf(1f) }
    val image by produceState<android.graphics.Bitmap?>(null, url) {
        value = session.avatarFull(url)
        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "аватар во весь экран ${value?.let { "${it.width}x${it.height}" } ?: "не загружен"}")
    }
    Box(Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { if (zoom == 1f) onClose() else zoom = 1f } }
            .pointerInput(Unit) { detectTransformGestures { _, _, scale, _ -> zoom = (zoom * scale).coerceIn(1f, 4f) } },
        contentAlignment = Alignment.Center) {
        image?.let {
            Image(it.asImageBitmap(), contentDescription = title, contentScale = ContentScale.Fit,
                  modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom))
        } ?: CircularProgressIndicator(color = Color.White)
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                 overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(words(P.done), color = Color.White, fontSize = 17.sp,
                 modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
    }
}
