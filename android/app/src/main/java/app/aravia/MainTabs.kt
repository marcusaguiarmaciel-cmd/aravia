package app.aravia

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.aravia.account.Account
import app.aravia.account.LegalDialog
import app.aravia.account.RewardedPremium
import app.aravia.arara.AraraCare
import app.aravia.arara.AraraNest
import app.aravia.arara.AraraProgress
import app.aravia.arara.AraraView
import app.aravia.band.ActivityStore
import app.aravia.band.BandSettings
import app.aravia.band.BandVault
import app.aravia.band.BindSheet
import app.aravia.band.DiagnosticsSheet
import app.aravia.band.FindPhone
import app.aravia.band.FindPhoneOverlay
import app.aravia.band.KeyImportButton
import app.aravia.band.MonitoringSheet
import app.aravia.band.SyncSection
import app.aravia.health.DataExport
import app.aravia.health.HealthHistory
import app.aravia.health.ProfileSheet
import app.aravia.health.ProfileStore
import app.aravia.health.ProgressScreen
import app.aravia.health.ProgressSubTab
import app.aravia.health.TodayScreen
import app.aravia.integrations.StravaCard
import app.aravia.onboarding.QuickStart
import app.aravia.routes.RouteStore
import app.aravia.training.TrainSubTab
import app.aravia.training.TrainingSections
import app.aravia.training.TrainingStore
import app.aravia.ui.Appearance
import app.aravia.ui.Aravia
import app.aravia.ui.Barlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val title: String) {
    TODAY("Hoje"),
    TRAIN("Treinar"),
    PROGRESS("Evolução"),
    WATCHES("Relógios"),
}

@Composable
fun MainTabs() {
    var tab by rememberSaveable {
        mutableStateOf(
            when (Demo.screen) {
                "band", "monitoring", "diagnostics", "bind" -> Tab.WATCHES
                "sleep", "trends" -> Tab.PROGRESS
                "today" -> Tab.TODAY
                "session", "route", "story", "overlay", "video" -> Tab.TRAIN
                else -> Tab.TODAY
            },
        )
    }
    var progressSection by rememberSaveable {
        mutableStateOf(if (Demo.screen == "sleep") ProgressSubTab.SLEEP else ProgressSubTab.TRENDS)
    }
    var trainSection by rememberSaveable {
        mutableStateOf(
            when (Demo.screen) {
                "route", "story", "overlay", "video" -> TrainSubTab.ROUTES
                "session" -> TrainSubTab.HISTORY
                else -> TrainSubTab.NOW
            },
        )
    }
    var showsProfileHub by rememberSaveable { mutableStateOf(Demo.screen == "beta") }

    val c = Aravia.colors
    val nest by AraraNest.state.collectAsState()
    val ringingFor by FindPhone.ringingFor.collectAsState()

    LaunchedEffect(nest.pendingInvite) {
        if (nest.pendingInvite != null) {
            tab = Tab.TRAIN
            trainSection = TrainSubTab.NOW
        }
    }

    val openProfile: () -> Unit = { showsProfileHub = true }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = c.canvas,
            bottomBar = {
                NavigationBar(containerColor = c.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(t.title, fontFamily = Barlow) },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.TODAY -> Icons.Filled.WbSunny
                                        Tab.TRAIN -> Icons.AutoMirrored.Filled.DirectionsRun
                                        Tab.PROGRESS -> Icons.AutoMirrored.Filled.ShowChart
                                        Tab.WATCHES -> Icons.Filled.Watch
                                    },
                                    null,
                                )
                            },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.TODAY -> TodayScreen(
                        headerTrailing = { ProfileAvatarButton(onClick = openProfile) },
                        onOpenTrain = {
                            tab = Tab.TRAIN
                            trainSection = TrainSubTab.NOW
                        },
                        onOpenRoutes = {
                            tab = Tab.TRAIN
                            trainSection = TrainSubTab.ROUTES
                        },
                        onOpenSleep = {
                            tab = Tab.PROGRESS
                            progressSection = ProgressSubTab.SLEEP
                        },
                        onOpenWatches = { tab = Tab.WATCHES },
                    )
                    Tab.TRAIN -> TrainTab(
                        section = trainSection,
                        onSelectSection = { trainSection = it },
                        onOpenProfile = openProfile,
                        onOpenWatches = { tab = Tab.WATCHES },
                    )
                    Tab.PROGRESS -> ProgressScreen(
                        section = progressSection,
                        onSelectSection = { progressSection = it },
                        headerTrailing = { ProfileAvatarButton(onClick = openProfile) },
                        onOpenWatches = { tab = Tab.WATCHES },
                    )
                    Tab.WATCHES -> WatchesTab(onOpenProfile = openProfile)
                }
            }
        }

        ringingFor?.let { bandName ->
            FindPhoneOverlay(bandName = bandName, onStop = { FindPhone.stop() })
        }
    }

    if (showsProfileHub) {
        ProfileHubDialog(onClose = { showsProfileHub = false })
    }
}

/** Compact top-right profile/account avatar button shared across all 4 tabs, matching iOS. */
@Composable
private fun ProfileAvatarButton(onClick: () -> Unit) {
    val c = Aravia.colors
    val account by Account.state.collectAsState()
    val rewarded by RewardedPremium.state.collectAsState()
    val initial = account.username?.firstOrNull()?.uppercaseChar()?.toString()
        ?: account.email?.firstOrNull()?.uppercaseChar()?.toString()
    val hasBadge = account.hasEarlyAccess || rewarded.isAdPassActive()

    Box(
        Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(c.surface)
                .border(1.dp, c.textSecondary.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (initial != null) {
                Text(initial, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.textPrimary)
            } else {
                Icon(Icons.Filled.Person, "Perfil e ajustes", tint = c.textPrimary, modifier = Modifier.size(18.dp))
            }
        }
        if (hasBadge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (account.hasEarlyAccess) c.action else c.good)
                    .border(1.5.dp, c.canvas, CircleShape),
            )
        }
    }
}

@Composable
private fun SectionHeaderLabel(text: String) {
    Text(
        text.uppercase(),
        fontFamily = Barlow,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
        color = Aravia.colors.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TrainTab(
    section: TrainSubTab,
    onSelectSection: (TrainSubTab) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenWatches: () -> Unit,
) {
    val c = Aravia.colors
    val nest by AraraNest.state.collectAsState()
    val care by AraraCare.state.collectAsState()
    var showsNest by remember { mutableStateOf(false) }
    val sessions by TrainingStore.sessions.collectAsState()
    val routes by RouteStore.routes.collectAsState()
    val storedVersion by ActivityStore.version.collectAsState()
    val hcVersion by app.aravia.health.HealthConnectStore.version.collectAsState()
    var history by remember { mutableStateOf<HealthHistory?>(null) }
    LaunchedEffect(storedVersion, hcVersion) {
        history = withContext(Dispatchers.IO) { HealthHistory.load() }
    }
    val totalSteps = history?.totalSteps ?: 0
    val activeDays = remember(history, sessions, routes) {
        history?.let { AraraCare.activeDaysThisWeek(it, sessions, routes) } ?: 0
    }
    val mine = AraraNest.Contribution(totalSteps, routes.size, sessions.size)
    val own = AraraProgress.xp(totalSteps, routes.size, sessions.size)
    val myXp = nest.accountXp ?: own
    val progress = AraraProgress(AraraNest.totalXp(myXp))
    LaunchedEffect(totalSteps, sessions.size, routes.size) {
        AraraNest.syncAccount(mine)
        AraraNest.noteStage(AraraProgress(AraraNest.totalXp(AraraNest.state.value.accountXp ?: 0)).stage)
    }
    LaunchedEffect(nest.pendingInvite) { if (nest.pendingInvite != null) showsNest = true }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Treinar",
                fontFamily = Barlow,
                fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            ProfileAvatarButton(onClick = onOpenProfile)
        }

        // 3 calm sub-sections matching iOS: Agora | Rotas | Histórico
        Row(
            Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TrainSubTab.entries.forEach { tab ->
                val selected = tab == section
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (selected) c.raised else Color.Transparent, RoundedCornerShape(9.dp))
                        .clickable { onSelectSection(tab) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.title,
                        fontFamily = Barlow,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 14.sp,
                        color = if (selected) c.textPrimary else c.textSecondary,
                    )
                }
            }
        }

        if (section == TrainSubTab.NOW) {
            Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AraraView(progress.stage, Modifier.size(130.dp), celebratedAt = nest.celebratedAt)
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.clickable { showsNest = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text(nest.name, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.textPrimary)
                            Spacer(Modifier.width(6.dp))
                            Icon(if (nest.members.size > 1) Icons.Filled.Group else Icons.Filled.PersonAdd, null, tint = c.action)
                        }
                        Text(
                            if (nest.members.size > 1) "${progress.stage.title} · cuidada por ${nest.members.size} pessoas"
                            else progress.stage.title,
                            fontFamily = Barlow,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = c.action,
                        )
                        Text(
                            if (care.resting) care.reason.message else progress.stage.line,
                            fontFamily = Barlow,
                            fontSize = 14.sp,
                            color = c.textSecondary,
                        )
                        progress.stage.next?.let { next ->
                            LinearProgressIndicator(progress = { progress.fraction }, color = c.action, modifier = Modifier.fillMaxWidth())
                            Text(
                                "${progress.xp} de ${next.threshold} pontos para virar ${next.title.lowercase()}",
                                fontFamily = Barlow,
                                fontSize = 12.sp,
                                color = c.textSecondary,
                            )
                        }
                    }
                }
                HorizontalDivider(color = c.textSecondary.copy(alpha = 0.16f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (care.resting) "Arara descansando (${care.reason.title})"
                        else "Semana ativa: $activeDays de ${care.weeklyGoal} dias",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (care.resting) c.fair else c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { if (care.resting) AraraCare.endRest() else showsNest = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(if (care.resting) "Voltar à rotina" else "Modo descanso", fontSize = 13.sp)
                    }
                }
            }
        }

        TrainingSections(
            section = section,
            onOpenWatches = onOpenWatches,
            onJumpToNow = { onSelectSection(TrainSubTab.NOW) },
        )
    }

    if (showsNest) NestSheet(myXp) { showsNest = false; AraraNest.clearInvite() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NestSheet(myXp: Int, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = Aravia.colors
    val nest by AraraNest.state.collectAsState()
    val care by AraraCare.state.collectAsState()
    var newName by remember { mutableStateOf(nest.name) }
    var myName by remember { mutableStateOf(AraraNest.myName()) }
    var typed by remember { mutableStateOf(nest.pendingInvite ?: "") }
    LaunchedEffect(Unit) { AraraNest.syncNest(myXp) }
    ModalBottomSheet(onDismissRequest = close, containerColor = c.canvas) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(nest.name, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = c.textPrimary)
            OutlinedTextField(newName, { newName = it }, label = { Text("Nome da arara") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { AraraNest.rename(newName) } }, enabled = newName.isNotBlank() && newName != nest.name) { Text("Salvar nome") }
            Text(if (nest.inNest) "O nome muda para todos do ninho." else "Dê um nome para a sua arara.", fontSize = 13.sp, color = c.textSecondary)

            HorizontalDivider(color = c.textSecondary.copy(alpha = 0.2f))
            Text("Ritmo sem culpa", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
            Text("Contamos semanas ativas em vez de cobrar dias seguidos. A arara nunca perde progresso.", fontSize = 13.sp, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (2..6).forEach { days ->
                    FilterChip(
                        selected = care.weeklyGoal == days,
                        onClick = { AraraCare.setWeeklyGoal(days) },
                        label = { Text("${days}d") },
                    )
                }
            }
            Text("Modo descanso (doença, lesão ou viagem)", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.textPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AraraCare.RestReason.entries.forEach { reason ->
                    FilterChip(
                        selected = care.resting && care.reason == reason,
                        onClick = {
                            if (care.resting && care.reason == reason) AraraCare.endRest()
                            else AraraCare.startRest(reason)
                        },
                        label = { Text(reason.title) },
                    )
                }
            }
            if (care.resting) {
                Text(care.reason.message, fontSize = 13.sp, color = c.fair)
                TextButton(onClick = { AraraCare.endRest() }) { Text("Sair do modo descanso") }
            }

            HorizontalDivider(color = c.textSecondary.copy(alpha = 0.2f))
            OutlinedTextField(myName, { myName = it; AraraNest.setMyName(it) }, label = { Text("Como você aparece no ninho") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val code = nest.code
            if (code != null) {
                Text("Código do ninho", fontSize = 13.sp, color = c.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(code, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = c.textPrimary)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, AraraNest.invite(code, nest.name))
                        }, "Convidar"))
                    }) { Text("Convidar") }
                }
                Text("Quem entrar com esse código cuida da mesma arara: os pontos de todos se somam.", fontSize = 13.sp, color = c.textSecondary)
                Text("Quem cuida dela", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                nest.members.forEach { m ->
                    Row { Text(m.name, color = c.textPrimary); Spacer(Modifier.weight(1f)); Text("${m.xp} pts", color = c.textSecondary) }
                }
                TextButton(onClick = { scope.launch { AraraNest.leave() } }) { Text("Sair do ninho", color = c.low) }
            } else {
                Button(onClick = { scope.launch { AraraNest.create(myXp) } }, modifier = Modifier.fillMaxWidth()) { Text("Criar ninho e convidar alguém") }
                Text("Recebeu um convite?", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                OutlinedTextField(typed, { typed = it.uppercase() }, label = { Text("Código de 6 letras") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch { AraraNest.join(typed, myXp) } }, enabled = typed.length >= 6) { Text("Entrar no ninho") }
            }
            if (nest.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            nest.error?.let { Text(it, color = c.low) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Relógios tab: dedicated strictly to watch & heart-rate sensor connections, matching iOS. */
@Composable
private fun WatchesTab(onOpenProfile: () -> Unit) {
    val c = Aravia.colors
    val band by BandVault.band.collectAsState()
    val context = LocalContext.current
    val nearby by app.aravia.band.BandLink.nearby.collectAsState()
    var autoBindTarget by remember { mutableStateOf<app.aravia.band.BandLink.ScannedBand?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    var showsMonitoring by remember { mutableStateOf(Demo.screen == "monitoring") }
    var showsDiagnostics by remember { mutableStateOf(Demo.screen == "diagnostics") }
    var showsBind by remember { mutableStateOf(Demo.screen == "bind") }

    LaunchedEffect(band) {
        if (band == null && app.aravia.band.BandPermissions.granted(context)) {
            app.aravia.band.BandLink.refreshKnownDevices()
        }
    }
    val detectedWatches = remember(nearby) {
        nearby.filter {
            (it.likelyWatch || it.hasMiBeacon) &&
                (it.source == app.aravia.band.BandLink.BandSource.CONNECTED ||
                    it.source == app.aravia.band.BandLink.BandSource.BONDED)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Relógios",
                fontFamily = Barlow,
                fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            ProfileAvatarButton(onClick = onOpenProfile)
        }

        val b = band
        if (b != null) {
            SectionHeaderLabel("Relógio principal")
            Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(b.shortName, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = c.textPrimary)
                val infoLine = listOfNotNull(
                    b.hardwareModel?.takeIf { it.isNotBlank() },
                    b.firmware?.takeIf { it.isNotBlank() }?.let { "fw $it" },
                    "${b.keys.size} ${if (b.keys.size == 1) "chave" else "chaves"}",
                ).joinToString(" · ")
                Text(infoLine, color = c.textSecondary, fontSize = 14.sp)
                SyncSection(
                    band = b,
                    onOpenMonitoring = { showsMonitoring = true },
                    onOpenDiagnostics = { showsDiagnostics = true },
                )
                HorizontalDivider(color = c.textSecondary.copy(alpha = 0.2f))
                OutlinedButton(onClick = { autoBindTarget = null; showsBind = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Conectar ou trocar de relógio (Bluetooth)")
                }
                app.aravia.band.ManualKeyButton(label = "Inserir outra chave manualmente")
                KeyImportButton(label = "Trazer chave de outro celular (QR)")
                TextButton(onClick = { forgetting = true }) { Text("Esquecer este relógio", color = c.low) }
            }
        }

        SectionHeaderLabel("Qualquer relógio")
        app.aravia.band.HealthConnectCard()

        SectionHeaderLabel("Sensores ao vivo")
        app.aravia.band.StandardHrCard()

        if (b == null) {
            SectionHeaderLabel("Conexão direta Xiaomi / Redmi")
            Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Adicionar Xiaomi / Redmi por Bluetooth", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.textPrimary)
                Text(
                    "Leitura direta dos arquivos da pulseira sem depender do Mi Fitness (detecta relógios já pareados neste celular).",
                    color = c.textSecondary,
                    fontSize = 13.sp,
                )
                if (detectedWatches.isNotEmpty()) {
                    detectedWatches.take(2).forEach { dev ->
                        Row(
                            Modifier.fillMaxWidth().background(c.canvas, RoundedCornerShape(14.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(dev.name, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                                Text(
                                    dev.source.label,
                                    fontSize = 12.sp,
                                    color = if (dev.source == app.aravia.band.BandLink.BandSource.CONNECTED) c.good else c.action,
                                )
                            }
                            Button(
                                onClick = { autoBindTarget = dev; showsBind = true },
                                colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                            ) { Text("Conectar") }
                        }
                    }
                }
                Button(
                    onClick = { autoBindTarget = null; showsBind = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                ) {
                    Text(
                        if (detectedWatches.isEmpty()) "Conectar relógio por Bluetooth" else "Procurar outro relógio",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                app.aravia.band.ManualKeyButton()
                KeyImportButton()
            }
        }
    }

    if (showsMonitoring) band?.let { MonitoringSheet(it) { showsMonitoring = false } }
    if (showsDiagnostics) DiagnosticsSheet(band) { showsDiagnostics = false }
    if (showsBind) BindSheet(autoConnect = autoBindTarget, onClose = { showsBind = false; autoBindTarget = null })
    if (forgetting) AlertDialog(
        onDismissRequest = { forgetting = false },
        title = { Text("Esquecer o relógio?") },
        text = { Text("A chave sai deste celular. Para voltar, conecte ou pareie o relógio novamente pelo Bluetooth.") },
        confirmButton = {
            TextButton(onClick = {
                BandSettings.setStayConnected(false)
                BandSettings.clearMonitoring()
                BandVault.forget()
                forgetting = false
            }) { Text("Esquecer", color = c.low) }
        },
        dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Cancelar") } },
    )
}

/**
 * Unified Profile & Settings modal matching iOS ProfileHubSheet:
 * Account identity, Aravia Premium / Beta-Tester benefits, Strava, Body profile & Preferences,
 * and a collapsible Advanced / Data section keeping technical controls out of everyday flow.
 */
@Composable
private fun ProfileHubDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = Aravia.colors
    val account by Account.state.collectAsState()
    val rewarded by RewardedPremium.state.collectAsState()
    val band by BandVault.band.collectAsState()
    val profile by ProfileStore.profile.collectAsState()
    val mode by Appearance.mode.collectAsState()

    var reading by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showsProfile by remember { mutableStateOf(false) }
    var showsBeta by remember { mutableStateOf(Demo.screen == "beta") }
    var showsEarlyAccess by remember { mutableStateOf(false) }
    var showsRedeem by remember { mutableStateOf(false) }
    var showsDiagnostics by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = c.canvas, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Clean top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Perfil e Ajustes",
                        fontFamily = Barlow,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        color = c.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, "Fechar", tint = c.textPrimary)
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 1. Account identity card
                    Column(
                        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val initial = account.username?.firstOrNull()?.uppercaseChar()?.toString()
                                ?: account.email?.firstOrNull()?.uppercaseChar()?.toString()
                                ?: "A"
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(c.action.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(initial, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c.action)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        account.username?.let { "@$it" } ?: "Sua conta",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = c.textPrimary,
                                    )
                                    if (account.supporter?.founderBadge == true) {
                                        Surface(color = c.action.copy(alpha = 0.16f), shape = RoundedCornerShape(99.dp)) {
                                            Text(
                                                "Beta-Tester",
                                                color = c.action,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            )
                                        }
                                    } else if (rewarded.isAdPassActive()) {
                                        Surface(color = c.good.copy(alpha = 0.16f), shape = RoundedCornerShape(99.dp)) {
                                            Text(
                                                "Premium",
                                                color = c.good,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                Text(account.email ?: "—", fontSize = 13.sp, color = c.textSecondary)
                                Text(
                                    if (account.emailVerified) "E-mail confirmado" else "E-mail não confirmado",
                                    fontSize = 12.sp,
                                    color = if (account.emailVerified) c.good else c.low,
                                )
                            }
                        }
                    }

                    // 2. Aravia Premium & Supporter benefits
                    SectionHeaderLabel("Aravia Premium")
                    app.aravia.ui.PremiumPassCard(
                        account = account,
                        onOpenTools = { showsEarlyAccess = true },
                    )
                    if (account.hasEarlyAccess) {
                        val sup = account.supporter
                        Column(
                            Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Acesso Antecipado", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
                                Surface(color = c.good.copy(alpha = 0.16f), shape = RoundedCornerShape(99.dp)) {
                                    Text(
                                        if (sup?.lifetime == true) "Vitalício" else "Ativo",
                                        color = c.good,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            Text(
                                "${sup?.tierName ?: "Apoiador"} · salvo na conta @${account.username ?: "—"}.",
                                color = c.textSecondary,
                                fontSize = 14.sp,
                            )
                            Button(
                                onClick = { showsEarlyAccess = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                            ) { Text("Abrir ferramentas antecipadas", fontWeight = FontWeight.SemiBold) }
                        }
                    } else {
                        OutlinedButton(onClick = { showsRedeem = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Ativar código de apoiador (aravia.app)")
                        }
                    }

                    // 3. Connected apps
                    SectionHeaderLabel("Apps conectados")
                    StravaCard()

                    // 4. Body & preferences
                    SectionHeaderLabel("Seu corpo e preferências")
                    Column(
                        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Perfil físico", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.textPrimary)
                        val summary = listOfNotNull(
                            profile.age?.let { "$it anos" },
                            profile.sex.takeIf { it != app.aravia.analytics.Profile.Sex.UNKNOWN }?.labelPt,
                            "meta de sono ${profile.sleepNeed / 60} h ${"%02d".format(profile.sleepNeed % 60)} min",
                        ).joinToString(" · ")
                        Text(summary, color = c.textSecondary, fontSize = 14.sp)
                        OutlinedButton(onClick = { showsProfile = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Editar perfil físico")
                        }

                        HorizontalDivider(color = c.textSecondary.copy(alpha = 0.16f))
                        Text("Aparência", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Appearance.Mode.entries.forEach { m ->
                                FilterChip(selected = mode == m, onClick = { Appearance.set(m) }, label = { Text(m.title) })
                            }
                        }

                        HorizontalDivider(color = c.textSecondary.copy(alpha = 0.16f))
                        TextButton(
                            onClick = {
                                account.uid?.let { QuickStart.again(it) }
                                onClose()
                            },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("Refazer guia inicial") }
                    }

                    if (BuildConfig.IS_BETA) {
                        Column(
                            Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Aravia Beta (${BuildConfig.VERSION_NAME})", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.textPrimary)
                            Text("Encontrou um erro ou tem uma ideia para o app?", color = c.textSecondary, fontSize = 13.sp)
                            OutlinedButton(onClick = { showsBeta = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Sobre o Beta, feedback e apoio")
                            }
                        }
                    }

                    // 5. Collapsible Advanced, Data & Account management
                    SectionHeaderLabel("Sistema")
                    Column(
                        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Dados, exportação e avançado",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = c.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = c.textSecondary)
                        }

                        if (showAdvanced) {
                            HorizontalDivider(color = c.textSecondary.copy(alpha = 0.16f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val file = withContext(Dispatchers.IO) { DataExport.exportCsv(context, HealthHistory.load(profile)) }
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "Exportar CSV"))
                                    }
                                }, modifier = Modifier.weight(1f)) { Text("Exportar CSV") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val file = withContext(Dispatchers.IO) { DataExport.exportJson(context, HealthHistory.load(profile)) }
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "Exportar JSON"))
                                    }
                                }, modifier = Modifier.weight(1f)) { Text("Exportar JSON") }
                            }
                            TextButton(onClick = { showsDiagnostics = true }, contentPadding = PaddingValues(0.dp)) {
                                Text("Diagnósticos e logs")
                            }
                            TextButton(onClick = { reading = "terms" }, contentPadding = PaddingValues(0.dp)) {
                                Text("Termos de Uso")
                            }
                            TextButton(onClick = { reading = "privacy" }, contentPadding = PaddingValues(0.dp)) {
                                Text("Política de Privacidade")
                            }
                        }

                        HorizontalDivider(color = c.textSecondary.copy(alpha = 0.16f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {
                                onClose()
                                Account.signOut()
                            }) { Text("Sair da conta") }
                            TextButton(onClick = { deleting = true }) { Text("Excluir conta", color = c.low) }
                        }
                        error?.let { Text(it, color = c.low, fontSize = 13.sp) }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    reading?.let { LegalDialog(it) { reading = null } }
    if (showsProfile) ProfileSheet { showsProfile = false }
    if (showsDiagnostics) DiagnosticsSheet(band) { showsDiagnostics = false }
    if (showsBeta) app.aravia.ui.BetaSheet(band) { showsBeta = false }
    if (showsEarlyAccess && (account.hasEarlyAccess || rewarded.isAdPassActive())) {
        app.aravia.ui.EarlyAccessSheet(account) { showsEarlyAccess = false }
    }
    if (showsRedeem) app.aravia.ui.RedeemSupporterSheet(
        account = account,
        onUnlocked = { showsRedeem = false; showsEarlyAccess = true },
        onClose = { showsRedeem = false },
    )
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Excluir a conta?") },
        text = {
            Column {
                Text("Isso não pode ser desfeito. Digite a sua senha para confirmar.")
                OutlinedTextField(
                    password,
                    { password = it },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                scope.launch {
                    runCatching { Account.deleteAccount(password) }
                        .onSuccess { onClose() }
                        .onFailure { error = it.message }
                    password = ""
                }
            }) { Text("Excluir para sempre", color = c.low) }
        },
        dismissButton = { TextButton(onClick = { deleting = false; password = "" }) { Text("Cancelar") } },
    )
}
