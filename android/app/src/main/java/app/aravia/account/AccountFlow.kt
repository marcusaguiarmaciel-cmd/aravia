package app.aravia.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aravia.arara.AraraStage
import app.aravia.arara.AraraView
import app.aravia.legal.LegalText
import app.aravia.ui.Barlow
import app.aravia.ui.Look
import kotlinx.coroutines.launch

/** Diamonds in rows, the same grafismo as the iOS welcome. */
@Composable
fun Grafismo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val step = 34.dp.toPx() / 2.2f
        var row = 0
        var y = step / 2
        while (y < size.height + step) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < size.width + step) {
                val r = step * 0.24f
                val d = Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }
                drawPath(d, Look.straw.copy(alpha = 0.14f), style = Stroke(1.5f))
                x += step
            }
            y += step; row++
        }
    }
}

@Composable
fun AccountFlow() {
    val state by Account.state.collectAsState()
    var step by remember { mutableStateOf("welcome") }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Look.night, Look.nightDeep)))) {
        if (!state.signedIn && step == "welcome") Grafismo(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            CompositionLocalProvider(LocalContentColor provides Look.straw) {
                when {
                    state.checking -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Look.straw)
                        Spacer(Modifier.height(16.dp))
                        Text("Entrando…", fontFamily = Barlow, fontSize = 18.sp)
                    }
                    state.signedIn && !state.emailVerified -> VerifyEmail()
                    state.signedIn && state.needsTerms -> TermsUpdate()
                    step == "signUp" -> SignUp { step = "welcome" }
                    step == "signIn" -> SignIn { step = "welcome" }
                    else -> Welcome({ step = "signUp" }, { step = "signIn" })
                }
            }
        }
    }
}

@Composable
private fun Welcome(signUp: () -> Unit, signIn: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(210.dp).offset(24.dp, 6.dp).background(Look.ochre.copy(alpha = 0.85f), CircleShape))
            AraraView(AraraStage.ADULT, Modifier.size(260.dp))
        }
        Text("ARAVIA", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 54.sp, letterSpacing = 12.sp)
        Text("Seu relógio, seus caminhos,\numa arara que cresce com você.", fontFamily = Barlow, fontSize = 19.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        PrimaryButton("Criar conta", onClick = signUp)
        TextButton(onClick = signIn, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Já tenho conta", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Look.straw)
        }
    }
}

@Composable
private fun SignUp(back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var terms by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf(false) }
    var adult by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val name = Account.normalize(username)
    val passwordOk = password.length >= 8 && password.any { it.isLetter() } && password.any { it.isDigit() }
    val ready = Account.isValidUsername(name) && email.contains("@") && passwordOk && password == confirm && terms && health && adult

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Criar conta", back)
        Field("Nome de usuário", username, { username = it }, hint = "3 a 20 letras minúsculas, números, ponto ou _")
        if (username.isNotEmpty() && !Account.isValidUsername(name)) Note("Use de 3 a 20 caracteres: letras sem acento, números, ponto ou _.", true)
        Field("E-mail", email, { email = it }, keyboard = KeyboardType.Email)
        Field("Senha", password, { password = it }, secret = true)
        Field("Confirmar senha", confirm, { confirm = it }, secret = true)
        if (password.isNotEmpty() && !passwordOk) Note("Pelo menos 8 caracteres, com letras e números.", true)
        if (confirm.isNotEmpty() && password != confirm) Note("As senhas não são iguais.", true)
        // Consents, each on its own and unchecked by default (LGPD arts. 8 and 11), as on iOS.
        Check(terms, { terms = it }) {
            Text(buildAnnotatedString {
                append("Li e aceito os ")
                withStyle(SpanStyle(color = Look.urucum, fontWeight = FontWeight.Bold)) { append("Termos de Uso") }
                append(" e a ")
                withStyle(SpanStyle(color = Look.urucum, fontWeight = FontWeight.Bold)) { append("Política de Privacidade") }
                append(".")
            }, fontFamily = Barlow, fontSize = 16.sp)
        }
        Row(Modifier.padding(start = 38.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LinkText("Ler os Termos") { reading = "terms" }
            LinkText("Ler a Política") { reading = "privacy" }
        }
        Column(Modifier.fillMaxWidth().background(Look.field, RoundedCornerShape(14.dp))
            .border(1.5.dp, Look.urucum.copy(alpha = 0.7f), RoundedCornerShape(14.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dados de saúde", color = Look.urucum, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(LegalText.HEALTH_CONSENT, fontFamily = Barlow, fontSize = 14.sp)
            Check(health, { health = it }) { Text("Autorizo o uso dos meus dados de saúde como descrito acima.", fontFamily = Barlow, fontSize = 16.sp) }
        }
        Check(adult, { adult = it }) { Text("Declaro que tenho 18 anos ou mais.", fontFamily = Barlow, fontSize = 16.sp) }
        error?.let { Note(it, true) }
        PrimaryButton("Criar conta", enabled = ready && !busy, busy = busy) {
            scope.launch {
                busy = true; error = null
                try { Account.signUp(username, email, password, Account.Consent(terms, health, adult)) }
                catch (e: Exception) { error = e.message }
                busy = false
            }
        }
        Text("Vamos mandar um link para confirmar o seu e-mail.", fontFamily = Barlow, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Look.straw.copy(alpha = 0.7f))
    }
    reading?.let { LegalDialog(it) { reading = null } }
}

@Composable
private fun SignIn(back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Entrar", back)
        Field("E-mail", email, { email = it }, keyboard = KeyboardType.Email)
        Field("Senha", password, { password = it }, secret = true)
        error?.let { Note(it, true) }
        info?.let { Note(it, false) }
        PrimaryButton("Entrar", enabled = email.isNotEmpty() && password.isNotEmpty() && !busy, busy = busy) {
            scope.launch {
                busy = true; error = null
                try { Account.signIn(email, password) } catch (e: Exception) { error = e.message }
                busy = false
            }
        }
        TextButton(onClick = {
            scope.launch {
                error = null
                try {
                    Account.sendPasswordReset(email)
                    info = "Se existir uma conta com esse e-mail, chegou um link para criar uma senha nova."
                } catch (e: Exception) { error = e.message }
            }
        }, enabled = email.contains("@"), modifier = Modifier.fillMaxWidth()) {
            Text("Esqueci a senha", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Look.urucum)
        }
    }
}

@Composable
private fun VerifyEmail() {
    val scope = rememberCoroutineScope()
    val state by Account.state.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.MarkEmailUnread, null, tint = Look.urucum, modifier = Modifier.size(64.dp))
        Text("Confirme o seu e-mail", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 30.sp)
        Text("Mandamos um link para ${state.email ?: "o seu e-mail"}. Abra o e-mail, toque no link e volte aqui. Se não achar, olhe a caixa de spam.",
            fontFamily = Barlow, fontSize = 17.sp, textAlign = TextAlign.Center)
        message?.let { Text(it, color = Look.ochre, fontFamily = Barlow, fontSize = 15.sp, textAlign = TextAlign.Center) }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Já confirmei", busy = checking) {
            scope.launch {
                checking = true
                runCatching { Account.refresh() }.onFailure { message = it.message }
                if (!Account.state.value.emailVerified) message = "Ainda não aparece como confirmado. Toque no link do e-mail e tente de novo."
                checking = false
            }
        }
        TextButton(onClick = {
            scope.launch {
                runCatching { Account.sendVerification() }
                    .onSuccess { message = "E-mail enviado de novo." }
                    .onFailure { message = Account.explain(it as? Exception ?: Exception(it)) }
            }
        }) {
            Text("Reenviar e-mail", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Look.urucum)
        }
        TextButton(onClick = { Account.signOut() }) { Text("Usar outra conta", fontFamily = Barlow, color = Look.straw.copy(alpha = 0.7f)) }
    }
}

@Composable
private fun TermsUpdate() {
    val scope = rememberCoroutineScope()
    val state by Account.state.collectAsState()
    var terms by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf(false) }
    var adult by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (state.acceptedVersion == null) "Antes de começar" else "Os termos mudaram", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 30.sp)
        Text("Leia e aceite para continuar usando o Aravia.", fontFamily = Barlow, fontSize = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LinkText("Termos de Uso") { reading = "terms" }
            LinkText("Política de Privacidade") { reading = "privacy" }
        }
        Check(terms, { terms = it }) { Text("Li e aceito os Termos de Uso e a Política de Privacidade.", fontFamily = Barlow, fontSize = 16.sp) }
        Text(LegalText.HEALTH_CONSENT, fontFamily = Barlow, fontSize = 14.sp)
        Check(health, { health = it }) { Text("Autorizo o uso dos meus dados de saúde.", fontFamily = Barlow, fontSize = 16.sp) }
        Check(adult, { adult = it }) { Text("Declaro que tenho 18 anos ou mais.", fontFamily = Barlow, fontSize = 16.sp) }
        error?.let { Note(it, true) }
        PrimaryButton("Continuar", enabled = terms && health && adult) {
            scope.launch { runCatching { Account.accept(Account.Consent(terms, health, adult)) }.onFailure { error = it.message } }
        }
        TextButton(onClick = { Account.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Sair", color = Look.straw) }
    }
    reading?.let { LegalDialog(it) { reading = null } }
}

@Composable
fun LegalDialog(which: String, close: () -> Unit) {
    val sections = if (which == "terms") LegalText.TERMS else LegalText.PRIVACY
    AlertDialog(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text("OK") } },
        title = { Text(if (which == "terms") "Termos de Uso" else "Política de Privacidade", fontFamily = Barlow) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Versão ${LegalText.VERSION} · em vigor desde ${LegalText.EFFECTIVE_DATE}", fontSize = 13.sp)
                sections.forEach {
                    Text(it.title, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(it.body, fontFamily = Barlow, fontSize = 15.sp)
                }
            }
        })
}

// shared bits

@Composable
private fun Header(title: String, back: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.clickable(onClick = back), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            Spacer(Modifier.width(6.dp))
            Text("Voltar", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Text(title, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 34.sp)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, hint: String = "",
                  keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        TextField(value, onChange, singleLine = true,
            placeholder = { Text(hint, color = Look.straw.copy(alpha = 0.35f), fontFamily = Barlow) },
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
            textStyle = LocalTextStyle.current.copy(fontFamily = Barlow, fontSize = 18.sp, color = Look.straw),
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Look.field, unfocusedContainerColor = Look.field,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Look.urucum),
            modifier = Modifier.fillMaxWidth().border(1.dp, Look.fieldLine, RoundedCornerShape(12.dp)))
    }
}

@Composable
private fun Note(text: String, bad: Boolean) =
    Text(text, color = if (bad) Look.urucum else Look.ochre, fontFamily = Barlow, fontSize = 14.sp)

@Composable
private fun LinkText(text: String, onClick: () -> Unit) =
    Text(text, color = Look.urucum, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onClick))

@Composable
private fun Check(checked: Boolean, onChange: (Boolean) -> Unit, label: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.Top) {
        Icon(if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
            tint = if (checked) Look.urucum else Look.straw.copy(alpha = 0.6f), modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { label() }
    }
}

@Composable
fun PrimaryButton(title: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(27.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Look.urucum, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(54.dp)) {
        if (busy) { CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
        Text(title, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
    }
}
