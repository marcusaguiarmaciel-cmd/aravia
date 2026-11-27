package app.aravia.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.aravia.arara.AraraNest
import app.aravia.arara.AraraProgress
import app.aravia.arara.AraraView
import app.aravia.band.BandPermissions
import app.aravia.band.BandSettings
import app.aravia.band.BandVault
import app.aravia.band.BindSheet
import app.aravia.band.KeyImportButton
import app.aravia.band.MonitoringSheet
import app.aravia.health.ProfileFields
import app.aravia.health.ProfileStore
import app.aravia.integrations.Strava
import app.aravia.integrations.StravaCard
import app.aravia.ui.Appearance
import app.aravia.ui.Aravia
import app.aravia.ui.Barlow
import kotlinx.coroutines.launch

/**
 * Full Quick Start after the first sign-in on this phone (Android plan, "Quick Start"):
 * Arara, Profile, Watch (QR or local bind), Monitoring, Stay connected (+ battery exemption),
 * Notifications, Appearance, Strava, Nest and Done summary. Every screen can be skipped and the
 * app resumes where it closed.
 */
object QuickStart {
    enum class Step(val title: String) {
        ARARA("Sua arara"),
        PROFILE("Seu perfil"),
        BAND("Seu relógio"),
        MONITORING("O que o relógio mede"),
        STAY("Ficar conectado"),
        ALERTS("Avisos"),
        LOOK("Aparência"),
        STRAVA("Strava"),
        NEST("Ninho"),
        DONE("Pronto"),
    }

    private lateinit var prefs: android.content.SharedPreferences
    /** Bumped when the Quick Start ends or starts again, so the screens that ask redraw. */
    var version by mutableStateOf(0); private set

    fun shows(uid: String): Boolean { version; return !isDone(uid) }

    fun init(context: Context) { prefs = context.getSharedPreferences("aravia", Context.MODE_PRIVATE) }

    fun isDone(uid: String) = prefs.getBoolean("quickStartDone.$uid", false)
    fun finish(uid: String) {
        prefs.edit().putBoolean("quickStartDone.$uid", true).remove("quickStartStep").remove("quickStartSkipped").apply()
        version++
    }

    fun again(uid: String) {
        prefs.edit().putBoolean("quickStartDone.$uid", false).putString("quickStartStep", Step.ARARA.name).remove("quickStartSkipped").apply()
        version++
    }

    internal var step: Step
        get() = runCatching { Step.valueOf(prefs.getString("quickStartStep", "ARARA")!!) }.getOrDefault(Step.ARARA)
        set(v) = prefs.edit().putString("quickStartStep", v.name).apply()

    internal var skipped: Set<String>
        get() = prefs.getStringSet("quickStartSkipped", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("quickStartSkipped", v).apply()
}

@Composable
fun QuickStartView(uid: String) {
    val c = Aravia.colors
    var step by remember { mutableStateOf(QuickStart.step) }
    var skipped by remember { mutableStateOf(QuickStart.skipped) }
    var revisiting by remember { mutableStateOf(false) }
    fun go(next: QuickStart.Step, skip: Boolean) {
        skipped = if (skip) skipped + step.name else skipped - step.name
        QuickStart.skipped = skipped
        step = next; QuickStart.step = next
    }
    fun next(skip: Boolean = false) {
        if (revisiting) { revisiting = false; go(QuickStart.Step.DONE, skip) } else go(QuickStart.Step.entries[step.ordinal + 1], skip)
    }

    Column(Modifier.fillMaxSize().background(c.canvas).statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${step.ordinal + 1} de ${QuickStart.Step.entries.size}", fontFamily = Barlow, color = c.textSecondary)
            Spacer(Modifier.weight(1f))
            if (step != QuickStart.Step.DONE) TextButton(onClick = { next(skip = true) }) { Text("Pular") }
        }
        LinearProgressIndicator(progress = { (step.ordinal + 1f) / QuickStart.Step.entries.size }, color = c.action,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Text(step.title, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, color = c.textPrimary)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                QuickStart.Step.ARARA -> AraraStep { next() }
                QuickStart.Step.PROFILE -> ProfileStep { next() }
                QuickStart.Step.BAND -> BandStep { next() }
                QuickStart.Step.MONITORING -> MonitoringStep { next() }
                QuickStart.Step.STAY -> StayStep { next() }
                QuickStart.Step.ALERTS -> AlertsStep { next() }
                QuickStart.Step.LOOK -> LookStep { next() }
                QuickStart.Step.STRAVA -> StravaStep { next() }
                QuickStart.Step.NEST -> NestStep { next() }
                QuickStart.Step.DONE -> DoneStep(skipped, revisit = { revisiting = true; step = it; QuickStart.step = it }) { QuickStart.finish(uid) }
            }
        }
    }
}

@Composable
private fun Line(text: String) = Text(text, fontFamily = Barlow, fontSize = 16.sp, color = Aravia.colors.textSecondary)

@Composable
private fun AraraStep(next: () -> Unit) {
    val scope = rememberCoroutineScope()
    val nest by AraraNest.state.collectAsState()
    var name by remember(nest.name) { mutableStateOf(if (nest.name == "Arara") "" else nest.name) }
    val stage = AraraProgress(AraraNest.totalXp(nest.accountXp ?: 0)).stage
    LaunchedEffect(Unit) { AraraNest.syncAccount(AraraNest.Contribution(0, 0, 0)) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AraraView(stage, Modifier.size(180.dp)) }
    Line(if (nest.name != "Arara") "A sua conta já tem uma arara: ${nest.name}. Ela é a mesma em todos os seus celulares."
         else "Ela nasce de um ovo e cresce com os seus passos, rotas e treinos. Que nome ela vai ter?")
    OutlinedTextField(name, { name = it.take(24) }, label = { Text("Nome da arara") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { scope.launch { if (name.isNotBlank() && name != nest.name) AraraNest.rename(name); next() } },
        modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
}

@Composable
private fun ProfileStep(next: () -> Unit) {
    val profile by ProfileStore.profile.collectAsState()
    ProfileFields(initial = profile, saveLabel = "Salvar e continuar") { next() }
}

@Composable
private fun BandStep(next: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    val band by BandVault.band.collectAsState()
    val hcState by app.aravia.health.HealthConnectBridge.state.collectAsState()
    val nearby by app.aravia.band.BandLink.nearby.collectAsState()
    var showsBind by remember { mutableStateOf(false) }
    var autoTarget by remember { mutableStateOf<app.aravia.band.BandLink.ScannedBand?>(null) }

    LaunchedEffect(Unit) {
        if (BandPermissions.granted(context)) app.aravia.band.BandLink.refreshKnownDevices()
    }

    val detectedWatches = remember(nearby) {
        nearby.filter {
            (it.likelyWatch || it.hasMiBeacon) &&
                (it.source == app.aravia.band.BandLink.BandSource.CONNECTED ||
                    it.source == app.aravia.band.BandLink.BandSource.BONDED)
        }
    }

    Line("O Aravia funciona com qualquer relógio (Galaxy Watch, Garmin, Amazfit, Xiaomi/Redmi, Polar, Fitbit, Wear OS e cintas cardíacas) — sempre guardando seus dados só neste celular.")

    app.aravia.band.HealthConnectCard(compact = true, onConnected = next)

    val b = band
    if (b != null || hcState.connected) {
        Button(
            onClick = next,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
        ) { Text("Continuar", fontWeight = FontWeight.SemiBold) }
    }

    if (b == null) {
        Text("Ou conecte direto por Bluetooth (sem app da fabricante)", fontWeight = FontWeight.SemiBold, color = c.textPrimary, fontSize = 15.sp)
        if (detectedWatches.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Relógio encontrado neste celular", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                detectedWatches.take(2).forEach { dev ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dev.name, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                            Text(
                                dev.source.label,
                                fontSize = 12.sp,
                                color = if (dev.source == app.aravia.band.BandLink.BandSource.CONNECTED) c.good else c.action,
                            )
                        }
                        OutlinedButton(onClick = { autoTarget = dev; showsBind = true }) { Text("Parear direto") }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { autoTarget = null; showsBind = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Parear Xiaomi/Redmi direto (sem Mi Fitness)")
        }
        app.aravia.band.ManualKeyButton(done = next)
        KeyImportButton(done = next)
        Line("Você pode pular por enquanto e conectar qualquer relógio depois na aba Relógios.")
    } else {
        Line("Conexão direta guardada para ${b.shortName}.")
    }

    if (showsBind) {
        BindSheet(
            autoConnect = autoTarget,
            onPaired = next,
            onClose = { showsBind = false; autoTarget = null },
        )
    }
}

@Composable
private fun MonitoringStep(next: () -> Unit) {
    val band by BandVault.band.collectAsState()
    var showsMon by remember { mutableStateOf(false) }
    val b = band
    if (b == null) {
        Line("Quando você conectar um relógio, poderá ajustar aqui a frequência de batimentos, SpO₂, estresse e sono.")
        Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
    } else {
        Line("Escolha de quanto em quanto tempo ${b.shortName} mede batimentos, oxigênio no sangue e estresse. Só aparecem as opções que o relógio aceita.")
        OutlinedButton(onClick = { showsMon = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Abrir monitoramento do relógio")
        }
        Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
        if (showsMon) MonitoringSheet(b) { showsMon = false }
    }
}

@Composable
private fun StayStep(next: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    val stay by BandSettings.stayConnected.collectAsState()
    val askBle = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) BandSettings.setStayConnected(true)
    }
    Line("Com \"Fica conectado\" ligado, o Aravia mantém o canal Bluetooth aberto em segundo plano para o relógio encontrar este celular.")
    Row(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Fica conectado", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            Text("O Mi Fitness não conecta enquanto estiver ligado.", fontSize = 13.sp, color = c.textSecondary)
        }
        Switch(checked = stay, onCheckedChange = { on ->
            if (on) {
                if (BandPermissions.granted(context)) BandSettings.setStayConnected(true)
                else askBle.launch(BandPermissions.needed)
            } else BandSettings.setStayConnected(false)
        })
    }
    Line("No Android, a economia de bateria pode derrubar a conexão com o relógio em segundo plano.")
    OutlinedButton(onClick = { BandSettings.openBatterySettings(context) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (BandSettings.isIgnoringBatteryOptimizations(context)) "Economia de bateria já liberada" else "Tirar o Aravia da economia de bateria")
    }
    Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
}

@Composable
private fun AlertsStep(next: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok }
    Line("As notificações mostram o cronômetro e os batimentos do treino na tela de bloqueio, avisam quando o descanso acaba e tocam quando o relógio procura o celular.")
    if (granted) {
        Line("Notificações permitidas neste celular.")
    } else if (Build.VERSION.SDK_INT >= 33) {
        Button(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Permitir notificações")
        }
    }
    Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
}

@Composable
private fun LookStep(next: () -> Unit) {
    val c = Aravia.colors
    val mode by Appearance.mode.collectAsState()
    Line("Você pode mudar isso quando quiser.")
    Appearance.Mode.entries.forEach { m ->
        Row(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(14.dp)).clickable { Appearance.set(m) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == m, onClick = { Appearance.set(m) })
            Text(m.title, fontFamily = Barlow, fontSize = 17.sp, color = c.textPrimary)
        }
    }
    Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
}

@Composable
private fun StravaStep(next: () -> Unit) {
    Line("Conecte uma vez para enviar rotas de GPS e treinos automaticamente ao Strava.")
    StravaCard()
    Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
}

@Composable
private fun NestStep(next: () -> Unit) {
    val c = Aravia.colors
    val scope = rememberCoroutineScope()
    val nest by AraraNest.state.collectAsState()
    var typed by remember { mutableStateOf(nest.pendingInvite ?: "") }
    val myXp = nest.accountXp ?: 0
    val code = nest.code
    if (code != null) {
        Line("Você já está num ninho. Código: $code")
        Line("Quem cuida dela: ${nest.members.joinToString { it.name }}")
        Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
    } else {
        Line("No ninho, outras pessoas cuidam da mesma arara e os pontos de todos se somam.")
        Button(onClick = { scope.launch { AraraNest.create(myXp) } }, enabled = !nest.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Criar um ninho")
        }
        OutlinedTextField(typed, { typed = it.uppercase().take(6) }, label = { Text("Recebeu um código?") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { scope.launch { AraraNest.join(typed, myXp) } }, enabled = typed.length == 6 && !nest.busy,
            modifier = Modifier.fillMaxWidth()) { Text("Entrar no ninho") }
        TextButton(onClick = next) { Text("Seguir sozinho") }
    }
    if (nest.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    nest.error?.let { Text(it, color = c.low) }
}

@Composable
private fun DoneStep(skipped: Set<String>, revisit: (QuickStart.Step) -> Unit, finish: () -> Unit) {
    val c = Aravia.colors
    val nest by AraraNest.state.collectAsState()
    val profile by ProfileStore.profile.collectAsState()
    val band by BandVault.band.collectAsState()
    val hcState by app.aravia.health.HealthConnectBridge.state.collectAsState()
    val stay by BandSettings.stayConnected.collectAsState()
    val mode by Appearance.mode.collectAsState()
    val strava by Strava.state.collectAsState()
    val watchSummary = when {
        band != null && hcState.connected -> "${band!!.shortName} + Health Connect"
        band != null -> band!!.shortName
        hcState.connected -> "Health Connect conectado"
        else -> "não conectado ainda"
    }
    val items = listOf(
        Triple(QuickStart.Step.ARARA, "Arara", nest.name),
        Triple(QuickStart.Step.PROFILE, "Perfil", profile.age?.let { "$it anos · sono ${profile.sleepNeed / 60} h" } ?: "padrão"),
        Triple(QuickStart.Step.BAND, "Relógio", watchSummary),
        Triple(QuickStart.Step.STAY, "Ficar conectado", if (stay) "ligado" else "desligado"),
        Triple(QuickStart.Step.LOOK, "Aparência", mode.title),
        Triple(QuickStart.Step.STRAVA, "Strava", if (strava.connected) "conectado" else "não conectado"),
        Triple(QuickStart.Step.NEST, "Ninho", nest.code?.let { "código $it" } ?: "sozinho"),
    )
    items.forEach { (s, title, value) ->
        Row(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                Text(if (s.name in skipped) "Pulado" else value, fontFamily = Barlow, color = if (s.name in skipped) c.fair else c.textSecondary)
            }
            if (s.name in skipped || (s == QuickStart.Step.BAND && band == null)) TextButton(onClick = { revisit(s) }) { Text("Fazer agora") }
        }
    }
    Line("Tudo isso muda depois em Perfil e Ajustes (toque no seu avatar no topo), onde também dá para refazer este guia inicial.")
    Button(onClick = finish, modifier = Modifier.fillMaxWidth()) { Text("Começar") }
}
