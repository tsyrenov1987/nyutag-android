// Нютаг. Первые экраны: выбор языка и вход.

package chat.nyutag.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.messenger.session.AppSession
import kotlinx.coroutines.launch

@Composable
fun LanguageScreen(words: Words) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().background(palette.milk).safeDrawingPadding().padding(horizontal = 24.dp),
           horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
        NyutagLogo(104.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Нютаг", color = palette.ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Хэлэн шэлэгты · Выберите язык · Choose a language", color = palette.quiet, fontSize = 13.sp,
                 textAlign = TextAlign.Center)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (lang in Lang.entries) {
                val buryat = lang == Lang.bxr
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (buryat) palette.gold else palette.card).clickable { words.choose(lang) },
                    contentAlignment = Alignment.Center) {
                    Text(lang.ownName, color = if (buryat) palette.onGold else palette.ink, fontSize = 20.sp,
                         fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun SignInScreen(session: AppSession) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AppSession.SignInFailure?>(null) }
    val passwordFocus = remember { FocusRequester() }
    val canSubmit = !busy && identifier.isNotBlank() && password.isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        busy = true
        failure = null
        scope.launch {
            try {
                session.signIn(identifier, password)
            } catch (e: AppSession.SignInException) {
                failure = e.failure
                busy = false
            }
        }
    }

    val colors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
        focusedPlaceholderColor = palette.quiet, unfocusedPlaceholderColor = palette.quiet,
    )
    Column(Modifier.fillMaxSize().background(palette.milk).safeDrawingPadding().imePadding()
               .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
           verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NyutagLogo(64.dp)
            Spacer(Modifier.padding(top = 4.dp))
            Text(words(P.welcome), color = palette.ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(words(P.signInLead), color = palette.quiet, fontSize = 16.sp)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.card)) {
            TextField(identifier, { identifier = it }, Modifier.fillMaxWidth(), singleLine = true, colors = colors,
                      placeholder = { Text(words(P.identifier)) },
                      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                                                        keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                      keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }))
            HorizontalDivider(Modifier.padding(start = 16.dp), color = palette.plate)
            TextField(password, { password = it }, Modifier.fillMaxWidth().focusRequester(passwordFocus), singleLine = true,
                      colors = colors, placeholder = { Text(words(P.password)) },
                      visualTransformation = PasswordVisualTransformation(),
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                      keyboardActions = KeyboardActions(onGo = { submit() }))
        }
        failure?.let {
            FailureNote(when (it) {
                AppSession.SignInFailure.WRONG_PASSWORD -> words(P.wrongPassword)
                AppSession.SignInFailure.TOO_OFTEN -> words(P.tooOften)
                AppSession.SignInFailure.NO_NETWORK, AppSession.SignInFailure.BROKEN -> words(P.noNetwork)
            })
        }
        // Сброс пароля и регистрация в приложении — следующим шагом; пока ведут на сайт.
        Text(words(P.forgotPassword), color = palette.sky, fontSize = 15.sp, textAlign = TextAlign.End,
             modifier = Modifier.fillMaxWidth().clickable { uri.openUri("https://nyutag.chat/") })
        BigButton(if (busy) words(P.signingIn) else words(P.signIn), busy, canSubmit, ::submit)
        Text(words(P.noAccount) + " " + words(P.signUp), color = palette.quiet, fontSize = 15.sp, textAlign = TextAlign.Center,
             modifier = Modifier.fillMaxWidth().clickable { uri.openUri("https://nyutag.chat/") })
    }
}
