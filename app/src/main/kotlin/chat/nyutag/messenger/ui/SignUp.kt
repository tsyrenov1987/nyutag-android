// Нютаг. Регистрация и сброс пароля. Регистрация в два шага: одна форма (имя, телефон, почта, пароль), затем код
// из письма. Что обязательно и нужен ли код приглашения, решает сервер (`/_nyutag/v1/config`); ник для Matrix ID
// получаем из имени и сверяем с сервером. Сброс: код на почту аккаунта, с ним новый пароль и сразу вход.

package chat.nyutag.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.auth.Nickname
import chat.nyutag.core.auth.isUsernameAvailable
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.nyutag.ContactMedium
import chat.nyutag.core.nyutag.NyutagService
import chat.nyutag.core.nyutag.SignUpConfiguration
import chat.nyutag.core.nyutag.Verification
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.doors.DoorHub
import chat.nyutag.messenger.session.AppSession
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Состояние регистрации: живёт, пока открыт экран, и переживает переход к коду и обратно. */
class SignUpFlow(private val doors: DoorHub) {
    enum class Failure {
        PHONE_TAKEN, EMAIL_TAKEN, BAD_PHONE, BAD_EMAIL, NICK_TAKEN, BAD_NICK, WEAK_PASSWORD,
        BAD_INVITE, INVITE_USED, WRONG_CODE, CODE_EXPIRED, TOO_MANY_TRIES, TOO_OFTEN, MAIL_FAILED, NO_NETWORK, BROKEN,
    }

    class FailureException(val failure: Failure) : Exception(failure.name)

    enum class Nick { EMPTY, CHECKING, FREE, TAKEN, INVALID }

    var configuration by mutableStateOf<SignUpConfiguration?>(null)
        private set
    var nick by mutableStateOf("")
        private set
    var nickState by mutableStateOf(Nick.EMPTY)
        private set
    /** Ник набран руками: имя его больше не перезаписывает. */
    private var nickTyped = false
    /** Письмо с кодом ушло: на этот адрес и с этим номером продолжаем. */
    var verification by mutableStateOf<Verification?>(null)
        private set
    var sentAt by mutableLongStateOf(0L)
        private set
    var phone: String? = null
        private set
    private var nickCheck: Job? = null

    val needsInvite: Boolean get() = configuration?.requiresInvite ?: false

    private fun service() = NyutagService(doors.makeClient())

    suspend fun prepare() {
        if (configuration != null || !doors.waitUntilUp()) return
        configuration = runCatching { service().configuration() }.getOrNull()
    }

    // MARK: Ник

    /** Имя поменялось: ник следует за ним, пока человек не набрал свой. */
    fun nameChanged(name: String, scope: kotlinx.coroutines.CoroutineScope) {
        if (!nickTyped) propose(Nickname.from(name), typed = false, scope)
    }

    fun nickEdited(typed: String, scope: kotlinx.coroutines.CoroutineScope) {
        nickTyped = typed.isNotEmpty()
        propose(Nickname.clean(typed), typed = true, scope)
    }

    private fun propose(candidate: String, typed: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
        nickCheck?.cancel()
        nick = candidate
        if (candidate.isEmpty()) { nickState = Nick.EMPTY; return }
        if (!Nickname.isValid(candidate)) { nickState = Nick.INVALID; return }
        nickState = Nick.CHECKING
        nickCheck = scope.launch {
            delay(450)
            if (!doors.waitUntilUp()) return@launch
            val client = doors.makeClient()
            // Отмена (набрали дальше) должна прервать проверку, а не сойти за «занят».
            suspend fun free(nick: String): Boolean = try {
                client.isUsernameAvailable(nick)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (free(candidate)) {
                nickState = Nick.FREE
                return@launch
            }
            // Ник из имени занят — тихо берём свободный вариант с цифрами; свой набранный ник не трогаем.
            if (!typed) {
                for (other in Nickname.alternatives(candidate)) {
                    if (free(other)) {
                        nick = other
                        nickState = Nick.FREE
                        return@launch
                    }
                }
            }
            nickState = Nick.TAKEN
        }
    }

    // MARK: Код из письма

    /** Номер свободен, письмо с кодом ушло на почту. */
    suspend fun sendCode(typedPhone: String, email: String, invite: String) {
        if (!doors.waitUntilUp()) throw FailureException(Failure.NO_NETWORK)
        val service = service()
        val trimmed = typedPhone.trim()
        phone = try {
            if (trimmed.isEmpty()) null else service.checkPhone(trimmed, invite)
        } catch (e: Exception) {
            throw FailureException(failure(e, ContactMedium.MSISDN))
        }
        try {
            verification = service.startVerification(ContactMedium.EMAIL, email.trim(), invite)
            sentAt = System.currentTimeMillis()
        } catch (e: Exception) {
            throw FailureException(failure(e, ContactMedium.EMAIL))
        }
    }

    fun forgetCode() {
        verification = null
        sentAt = 0L
    }

    /** Код верный — заводим аккаунт и сразу входим; имя уходит в профиль. */
    suspend fun finish(token: String, name: String, password: String, invite: String, session: AppSession) {
        val verification = verification ?: throw FailureException(Failure.CODE_EXPIRED)
        val service = service()
        val userID = try {
            if (!service.checkVerification(verification.sid, token)) throw FailureException(Failure.WRONG_CODE)
            service.register(invite, nick, password, sid = verification.sid, phone = phone)
        } catch (e: FailureException) {
            throw e
        } catch (e: Exception) {
            throw FailureException(failure(e, ContactMedium.EMAIL))
        }
        try {
            session.signIn(userID, password, displayName = name.trim())
        } catch (e: AppSession.SignInException) {
            throw FailureException(if (e.failure == AppSession.SignInFailure.TOO_OFTEN) Failure.TOO_OFTEN else Failure.NO_NETWORK)
        }
    }

    companion object {
        fun failure(error: Exception, contact: ContactMedium): Failure {
            if (error is CancellationException) throw error
            val matrix = error as? MatrixError ?: return Failure.BROKEN
            when (matrix.errcode) {
                "NYUTAG_CONTACT_IN_USE" -> return if (contact == ContactMedium.EMAIL) Failure.EMAIL_TAKEN else Failure.PHONE_TAKEN
                "NYUTAG_BAD_CONTACT" -> return if (contact == ContactMedium.EMAIL) Failure.BAD_EMAIL else Failure.BAD_PHONE
                "M_USER_IN_USE" -> return Failure.NICK_TAKEN
                "M_INVALID_USERNAME" -> return Failure.BAD_NICK
                "M_WEAK_PASSWORD" -> return Failure.WEAK_PASSWORD
                "NYUTAG_BAD_CODE", "NYUTAG_UNKNOWN_CODE" -> return Failure.BAD_INVITE
                "NYUTAG_CODE_USED", "NYUTAG_CODE_EXPIRED" -> return Failure.INVITE_USED
                "NYUTAG_VERIFY_WRONG" -> return Failure.WRONG_CODE
                "NYUTAG_VERIFY_EXPIRED", "NYUTAG_UNKNOWN_SESSION", "NYUTAG_CONTACT_NOT_VERIFIED" -> return Failure.CODE_EXPIRED
                "NYUTAG_VERIFY_ATTEMPTS" -> return Failure.TOO_MANY_TRIES
                "M_LIMIT_EXCEEDED" -> return Failure.TOO_OFTEN
                "NYUTAG_DELIVERY_FAILED", "NYUTAG_CHANNEL_UNAVAILABLE" -> return Failure.MAIL_FAILED
            }
            if (matrix.status == 429) return Failure.TOO_OFTEN
            return if (matrix.kind is MatrixError.Kind.Network) Failure.NO_NETWORK else Failure.BROKEN
        }

        fun message(failure: Failure, words: Words): String = words(when (failure) {
            Failure.PHONE_TAKEN -> P.phoneTaken
            Failure.EMAIL_TAKEN -> P.emailTaken
            Failure.BAD_PHONE -> P.badPhone
            Failure.BAD_EMAIL -> P.badEmail
            Failure.NICK_TAKEN -> P.nickTaken
            Failure.BAD_NICK -> P.nickRules
            Failure.WEAK_PASSWORD -> P.weakPassword
            Failure.BAD_INVITE -> P.badInvite
            Failure.INVITE_USED -> P.inviteUsed
            Failure.WRONG_CODE -> P.wrongCode
            Failure.CODE_EXPIRED -> P.codeExpired
            Failure.TOO_MANY_TRIES -> P.tooManyTries
            Failure.TOO_OFTEN -> P.tooOften
            Failure.MAIL_FAILED -> P.mailFailed
            Failure.NO_NETWORK, Failure.BROKEN -> P.noNetwork
        })
    }
}

@Composable
fun SignUpScreen(session: AppSession, doors: DoorHub, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val flow = remember { SignUpFlow(doors) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var editingNick by remember { mutableStateOf(false) }
    var nickText by remember { mutableStateOf(TextFieldValue()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<SignUpFlow.Failure?>(null) }
    var codeShown by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val nickFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        flow.prepare()
        log("регистрация, сервер: ${flow.configuration}")
    }

    if (codeShown) {
        SignUpCodeScreen(session, flow, email, name, password, invite) {
            flow.forgetCode()
            codeShown = false
        }
        return
    }
    BackHandler(onBack = onClose)

    val canSubmit = run {
        val config = flow.configuration
        if (busy || config == null || flow.nickState != SignUpFlow.Nick.FREE || name.isBlank() || '@' !in email ||
            password.length < 8) return@run false
        if (config.requiresPhone && phone.count(Char::isDigit) < 7) return@run false
        if (flow.needsInvite && invite.count { !it.isWhitespace() } < 6) return@run false
        true
    }

    fun submit() {
        if (!canSubmit) return
        busy = true
        failure = null
        scope.launch {
            try {
                flow.sendCode(phone, email, invite)
                codeShown = true
            } catch (e: SignUpFlow.FailureException) {
                failure = e.failure
                log("регистрация, отказ формы: ${e.failure}")
            }
            busy = false
        }
    }

    FormPage(words(P.signUpTitle), onClose) {
        NyutagLogo(52.dp)
        Text(words(P.signUpLead), color = palette.quiet, fontSize = 16.sp)
        FormCard {
            FormField(name, { name = it; flow.nameChanged(it, scope) }, words(P.name), Modifier.focusRequester(nameFocus),
                      KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
            // Адрес под именем: подставляется сам, по касанию правится.
            if (editingNick) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("@", color = palette.quiet, modifier = Modifier.padding(start = 12.dp))
                    // Курсор — в конце подставленного ника, как привычно при правке.
                    TextField(nickText, { typed ->
                                  if (typed.text != nickText.text) flow.nickEdited(typed.text, scope)
                                  nickText = typed
                              }, Modifier.weight(1f).focusRequester(nickFocus), singleLine = true, colors = fieldColors(),
                              placeholder = { Text(words(P.nickLabel)) },
                              keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                                                                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next))
                    NickMark(flow.nickState)
                }
                LaunchedEffect(Unit) { nickFocus.requestFocus() }
            } else if (flow.nick.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().clickable { nickText = TextFieldValue(flow.nick, TextRange(flow.nick.length)); editingNick = true }
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(words(P.nickLabel) + ":", color = palette.quiet, fontSize = 13.sp)
                    // Ник с отметкой — во всю оставшуюся ширину, карандаш прижат вправо.
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("@" + flow.nick, color = palette.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                             modifier = Modifier.weight(1f, fill = false))
                        NickMark(flow.nickState)
                    }
                    Text("✎", color = palette.quiet, fontSize = 14.sp)
                }
            }
            if (flow.nickState == SignUpFlow.Nick.TAKEN || flow.nickState == SignUpFlow.Nick.INVALID) {
                Text(words(if (flow.nickState == SignUpFlow.Nick.TAKEN) P.nickTaken else P.nickRules), color = palette.gold,
                     fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
            FormDivider()
            FormField(phone, { phone = it }, words(P.phone),
                      keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next))
            FormDivider()
            FormField(email, { email = it }, words(P.email),
                      keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                                                 keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
            FormDivider()
            FormField(password, { password = it }, words(P.newPassword), secret = true,
                      keyboard = KeyboardOptions(keyboardType = KeyboardType.Password,
                                                 imeAction = if (flow.needsInvite) ImeAction.Next else ImeAction.Go),
                      onGo = ::submit)
            if (flow.needsInvite || invite.isNotEmpty()) {
                FormDivider()
                FormField(invite, { invite = it }, words(P.inviteCode), mono = true,
                          keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                                                     keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                          onGo = ::submit)
            }
        }
        failure?.let { FailureNote(SignUpFlow.message(it, words)) }
        BigButton(if (busy) words(P.sendingCode) else words(P.getCode), busy, canSubmit, ::submit)
    }
    LaunchedEffect(Unit) { nameFocus.requestFocus() }
}

@Composable
private fun SignUpCodeScreen(session: AppSession, flow: SignUpFlow, email: String, name: String, password: String,
                             invite: String, onBack: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<SignUpFlow.Failure?>(null) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onBack)

    fun check() {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try {
                flow.finish(code, name, password, invite, session)
                log("регистрация выполнена")
            } catch (e: SignUpFlow.FailureException) {
                failure = e.failure
                code = ""
                busy = false
                log("регистрация, отказ кода: ${e.failure}")
            }
        }
    }

    FormPage(words(P.codeTitle), onBack) {
        Text(words(P.codeSent, email.trim()), color = palette.ink, fontSize = 16.sp)
        CodeField(code, { typed ->
            code = typed.filter(Char::isDigit).take(6)
            if (code.length == 6) check()
        }, Modifier.focusRequester(focus))
        if (busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(words(P.creatingAccount), color = palette.quiet)
            }
        }
        failure?.let { FailureNote(SignUpFlow.message(it, words)) }
        Text(words(P.codeSpam), color = palette.quiet, fontSize = 13.sp)
        ResendButton(flow.verification, flow.sentAt, busy) {
            scope.launch {
                // Номер уже проверен; повторное письмо — новая сессия подтверждения.
                failure = try {
                    flow.sendCode(flow.phone.orEmpty(), email, invite)
                    null
                } catch (e: SignUpFlow.FailureException) {
                    e.failure
                }
            }
        }
        LinkText(words(P.changeDetails), palette.quiet, onBack)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** «Забыли пароль?»: код приходит на почту аккаунта; с кодом задаётся новый пароль, и приложение сразу входит. */
@Composable
fun PasswordResetScreen(session: AppSession, doors: DoorHub, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf<Verification?>(null) }
    var sentAt by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun message(error: Exception): String {
        if ((error as? MatrixError)?.errcode == "NYUTAG_CONTACT_UNKNOWN") return words(P.resetUnknown)
        return SignUpFlow.message(SignUpFlow.failure(error, ContactMedium.EMAIL), words)
    }

    fun send() {
        busy = true
        failure = null
        scope.launch {
            if (!doors.waitUntilUp()) {
                failure = words(P.noNetwork)
            } else {
                try {
                    verification = NyutagService(doors.makeClient()).startPasswordReset(email.trim())
                    sentAt = System.currentTimeMillis()
                } catch (e: Exception) {
                    failure = message(e)
                }
            }
            log("сброс пароля, письмо ${if (verification == null) "не ушло: $failure" else "ушло"}")
            busy = false
        }
    }

    fun reset() {
        val sent = verification ?: return
        busy = true
        failure = null
        scope.launch {
            val userID = try {
                NyutagService(doors.makeClient()).resetPassword(sent.sid, code, password)
            } catch (e: Exception) {
                failure = message(e)
                code = ""
                busy = false
                return@launch
            }
            try {
                session.signIn(userID, password)
            } catch (e: AppSession.SignInException) {
                failure = words(if (e.failure == AppSession.SignInFailure.TOO_OFTEN) P.tooOften else P.noNetwork)
                busy = false
            }
        }
    }

    BackHandler(onBack = onClose)
    FormPage(words(P.resetTitle), onClose) {
        val sent = verification
        if (sent == null) {
            Text(words(P.resetLead), color = palette.ink, fontSize = 16.sp)
            FormCard {
                FormField(email, { email = it }, words(P.email),
                          keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                                                     keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                          onGo = { if (!busy && '@' in email) send() })
            }
            failure?.let { FailureNote(it) }
            BigButton(words(P.resetSend), busy, !busy && '@' in email, ::send)
        } else {
            Text(words(P.codeSent, email.trim()), color = palette.ink, fontSize = 16.sp)
            FormCard {
                CodeField(code, { code = it.filter(Char::isDigit).take(6) }, plain = true)
                FormDivider()
                FormField(password, { password = it }, words(P.resetNewPassword), secret = true,
                          keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                          onGo = { if (!busy && code.length == 6 && password.length >= 8) reset() })
            }
            failure?.let { FailureNote(it) }
            BigButton(words(P.resetSubmit), busy, !busy && code.length == 6 && password.length >= 8, ::reset)
            Text(words(P.codeSpam), color = palette.quiet, fontSize = 13.sp)
            ResendButton(sent, sentAt, busy, ::send)
            LinkText(words(P.changeDetails), palette.quiet) {
                verification = null
                code = ""
                failure = null
            }
        }
    }
}

// MARK: Детали форм

/** Страница формы: полоса с «Отмена» и заголовком, ниже прокручиваемое содержимое. */
@Composable
internal fun FormPage(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().background(palette.milk).safeDrawingPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp)) {
            Text(words(P.cancel), color = palette.sky, fontSize = 17.sp,
                 modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onClose).padding(vertical = 8.dp))
            Text(title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
               verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
    }
}

@Composable
internal fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LocalPalette.current.card), content = content)
}

@Composable
internal fun FormDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = LocalPalette.current.plate)

@Composable
internal fun fieldColors(): TextFieldColors {
    val palette = LocalPalette.current
    return TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
        focusedPlaceholderColor = palette.quiet, unfocusedPlaceholderColor = palette.quiet,
    )
}

@Composable
private fun FormField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
                      keyboard: KeyboardOptions = KeyboardOptions.Default, secret: Boolean = false, mono: Boolean = false,
                      onGo: () -> Unit = {}) {
    TextField(value, onChange, modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(),
              placeholder = { Text(placeholder) },
              textStyle = if (mono) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp) else TextStyle.Default,
              visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
              keyboardOptions = keyboard, keyboardActions = KeyboardActions(onGo = { onGo() }))
}

/** Поле кода из письма: шесть цифр крупно по центру. `plain` — без своей карточки, внутри [FormCard]. */
@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, plain: Boolean = false) {
    val palette = LocalPalette.current
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = if (plain) 28.sp else 34.sp,
                          fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = palette.ink)
    val box = if (plain) modifier.fillMaxWidth() else modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.card)
    TextField(value, onChange, box.padding(vertical = if (plain) 0.dp else 8.dp), singleLine = true, colors = fieldColors(),
              textStyle = style,
              placeholder = { Text("000000", style = style.copy(color = palette.quiet), modifier = Modifier.fillMaxWidth()) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
}

/** «Отправить ещё раз»: доступна, когда истекла пауза сервера; до того — обратный отсчёт. */
@Composable
private fun ResendButton(verification: Verification?, sentAt: Long, busy: Boolean, onResend: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sentAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val wait = (verification?.resendAfterSeconds ?: 0) * 1000L
    val left = if (verification == null) 0 else maxOf(0, ceil((wait - (now - sentAt)) / 1000.0).toInt())
    val ready = left == 0 && !busy
    Text(if (left == 0) words(P.resend) else words(P.resendIn, left.toString()),
         color = if (ready) palette.sky else palette.quiet, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.clickable(enabled = ready, onClick = onResend).padding(vertical = 4.dp))
}

@Composable
private fun LinkText(text: String, color: Color, onClick: () -> Unit) {
    Text(text, color = color, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp))
}

@Composable
private fun NickMark(state: SignUpFlow.Nick) {
    val palette = LocalPalette.current
    when (state) {
        SignUpFlow.Nick.CHECKING -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        SignUpFlow.Nick.FREE -> Text("✓", color = palette.read, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        SignUpFlow.Nick.TAKEN, SignUpFlow.Nick.INVALID -> Text("!", color = palette.gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        SignUpFlow.Nick.EMPTY -> Unit
    }
}

private fun log(line: String) {
    if (BuildConfig.DEBUG) android.util.Log.d("ui-check", line)
}
