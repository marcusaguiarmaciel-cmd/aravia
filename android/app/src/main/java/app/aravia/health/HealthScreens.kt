package app.aravia.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aravia.analytics.ConfidenceLevel
import app.aravia.analytics.RecoveryContributor
import app.aravia.analytics.SleepContributor
import app.aravia.arara.AraraCare
import app.aravia.arara.AraraNest
import app.aravia.arara.AraraProgress
import app.aravia.arara.AraraView
import app.aravia.band.ActivityStore
import app.aravia.band.StandardHrLink
import app.aravia.protocol.SleepStage
import app.aravia.routes.RouteStore
import app.aravia.training.TrainingStore
import app.aravia.ui.Aravia
import app.aravia.ui.AraviaColors
import app.aravia.ui.Barlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ptBR = Locale("pt", "BR")
private val dayTitle = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", ptBR)
private val hm = DateTimeFormatter.ofPattern("HH:mm", ptBR)

private fun number(n: Int) = String.format(ptBR, "%,d", n)
private fun hours(minutes: Int) = "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
private fun time(seconds: Long) = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).format(hm)

enum class ProgressSubTab(val title: String) {
    TRENDS("Tendências"),
    SLEEP("Sono"),
}

/** The stored files + Health Connect snapshots, scored again whenever data or profile changes. */
@Composable
private fun rememberHistory(): HealthHistory? {
    val version by ActivityStore.version.collectAsState()
    val hcVersion by HealthConnectStore.version.collectAsState()
    val profile by ProfileStore.profile.collectAsState()
    var history by remember { mutableStateOf<HealthHistory?>(null) }
    LaunchedEffect(version, hcVersion, profile) {
        history = withContext(Dispatchers.IO) { HealthHistory.load(profile) }
    }
    return history
}

@Composable
private fun Screen(
    title: String,
    headerTrailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontFamily = Barlow,
                fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp,
                color = Aravia.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            headerTrailing()
        }
        content()
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().background(Aravia.colors.surface, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

@Composable
private fun Empty(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) = Panel {
    Text(text, color = Aravia.colors.textSecondary)
    if (actionLabel != null && onAction != null) {
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = Aravia.colors.action, contentColor = Aravia.colors.onAction),
        ) {
            Text(actionLabel, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Stat(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = Aravia.colors.textSecondary)
        Text(value ?: "—", fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Aravia.colors.textPrimary)
    }
}

@Composable
private fun DayPicker(day: LocalDate, first: LocalDate?, last: LocalDate?, set: (LocalDate) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { set(day.minusDays(1)) }, enabled = first == null || day > first) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Dia anterior")
        }
        Text(day.format(dayTitle).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold,
            color = Aravia.colors.textPrimary, modifier = Modifier.weight(1f))
        IconButton(onClick = { set(day.plusDays(1)) }, enabled = last == null || day < last) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Dia seguinte")
        }
    }
}

private fun scoreColor(score: Int?, c: AraviaColors): Color = when {
    score == null -> c.textSecondary
    score >= 70 -> c.good
    score >= 45 -> c.fair
    else -> c.low
}

@Composable
private fun ScoreRing(label: String, valueText: String, fraction: Float?, color: Color, subtitle: String?, modifier: Modifier = Modifier) {
    val c = Aravia.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(82.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = Stroke(width = 10f, cap = StrokeCap.Round)
                drawArc(c.textSecondary.copy(alpha = 0.16f), 135f, 270f, false, style = stroke)
                if (fraction != null) {
                    drawArc(color, 135f, 270f * fraction.coerceIn(0f, 1f), false, style = stroke)
                }
            }
            Text(valueText, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.textPrimary)
        }
        Text(label, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.textPrimary)
        subtitle?.let { Text(it, fontSize = 11.sp, color = c.textSecondary, textAlign = TextAlign.Center) }
    }
}

/** Hoje: Recovery, Sleep and Strain scores, Arara rhythm strip, sleep shortcut, and day metrics. */
@Composable
fun TodayScreen(
    headerTrailing: @Composable () -> Unit = {},
    onOpenTrain: () -> Unit = {},
    onOpenRoutes: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
    onOpenWatches: () -> Unit = {},
) {
    val c = Aravia.colors
    val history = rememberHistory()
    val nest by AraraNest.state.collectAsState()
    val care by AraraCare.state.collectAsState()
    val sessions by TrainingStore.sessions.collectAsState()
    val routes by RouteStore.routes.collectAsState()
    val hrState by StandardHrLink.state.collectAsState()
    var day by remember { mutableStateOf<LocalDate?>(null) }

    Screen("Hoje", headerTrailing = headerTrailing) {
        val h = history ?: return@Screen
        val last = h.lastDay
        val totalSteps = h.totalSteps
        val activeDays = remember(h, sessions, routes) { AraraCare.activeDaysThisWeek(h, sessions, routes) }
        val ownXp = AraraProgress.xp(totalSteps, routes.size, sessions.size)
        val progress = AraraProgress(AraraNest.totalXp(nest.accountXp ?: ownXp))

        if (last == null) {
            // Welcoming watch-free Home matching iOS NoBandView
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AraraView(progress.stage, Modifier.size(76.dp), celebratedAt = nest.celebratedAt)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(nest.name, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c.textPrimary)
                        Text(
                            if (care.resting) "${progress.stage.title} · em descanso"
                            else "${progress.stage.title} · $activeDays de ${care.weeklyGoal} dias na semana",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = if (care.resting) c.textSecondary else c.action,
                        )
                        Text(
                            if (sessions.isEmpty()) "Comece um treino pelo celular ou conecte seu relógio para ver Recuperação, Sono e Esforço."
                            else "${sessions.size} treino(s) registrados no Aravia.",
                            fontSize = 13.sp,
                            color = c.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenTrain,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                    ) { Text("Começar treino", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(onClick = onOpenRoutes) { Text("Rotas") }
                }
            }

            Panel {
                Text("Conecte seu relógio ou cinta", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
                Text(
                    if (hrState.status == StandardHrLink.Status.CONNECTED)
                        "Cinta conectada (${hrState.bpm?.let { "$it bpm" } ?: "ao vivo"}). Conecte também um relógio para sono e recuperação."
                    else
                        "Funciona com Galaxy Watch, Garmin, Amazfit, Xiaomi/Redmi, Polar, Fitbit, Pixel Watch e qualquer cinta cardíaca Bluetooth.",
                    fontSize = 13.sp,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(2.dp))
                OutlinedButton(onClick = onOpenWatches, modifier = Modifier.fillMaxWidth()) {
                    Text("Conectar relógio", fontWeight = FontWeight.SemiBold)
                }
            }
            return@Screen
        }

        val d = day ?: last
        DayPicker(d, h.days.keys.minOrNull(), last) { day = it }
        val today = h.day(d)
        val s = today?.summary
        val sc = today?.score

        // Scores card (Recovery, Sleep, Strain)
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val rec = sc?.recovery?.score
                val slp = sc?.sleep?.score
                val str = sc?.strain?.strain
                ScoreRing(
                    label = "Recuperação",
                    valueText = rec?.toString() ?: "—",
                    fraction = rec?.div(100f),
                    color = scoreColor(rec, c),
                    subtitle = when {
                        rec != null -> sc?.recovery?.confidence?.level?.labelPt?.lowercase()?.let { "Conf. $it" }
                        sc?.recovery?.confidence?.level == ConfidenceLevel.NONE && h.night(d) != null -> "Calibrando"
                        else -> null
                    },
                    modifier = Modifier.weight(1f),
                )
                ScoreRing(
                    label = "Sono",
                    valueText = slp?.toString() ?: "—",
                    fraction = slp?.div(100f),
                    color = scoreColor(slp, c),
                    subtitle = if (sc?.sleep?.estimated == true) "Estimativa"
                        else slp?.let { sc?.sleep?.confidence?.level?.labelPt?.lowercase()?.let { l -> "Conf. $l" } },
                    modifier = Modifier.weight(1f),
                )
                ScoreRing(
                    label = "Esforço",
                    valueText = str?.let { String.format(ptBR, "%.1f", it) } ?: "—",
                    fraction = str?.let { (it / 21.0).toFloat() },
                    color = c.activity,
                    subtitle = str?.let { "0–21" },
                    modifier = Modifier.weight(1f),
                )
            }
            sc?.recovery?.contributors?.filter { it.weight > 0 && it.today != null }?.takeIf { it.isNotEmpty() }?.let { list ->
                HorizontalDivider(color = c.textSecondary.copy(alpha = 0.16f))
                list.forEach { row ->
                    val tv = row.today ?: return@forEach
                    val valTxt = when (row.name) {
                        RecoveryContributor.Name.HRV -> "${tv.toInt()} ms"
                        RecoveryContributor.Name.RESTING_HR -> "${tv.toInt()} bpm"
                        RecoveryContributor.Name.SLEEP -> "${tv.toInt()}"
                        RecoveryContributor.Name.STRAIN -> String.format(ptBR, "%.1f", tv)
                    }
                    val baseTxt = row.baseline?.median?.let { m ->
                        " (normal ${if (row.name == RecoveryContributor.Name.STRAIN) String.format(ptBR, "%.1f", m) else m.toInt().toString()})"
                    } ?: ""
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.name.labelPt, fontSize = 13.sp, color = c.textSecondary)
                        Text("$valTxt$baseTxt", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
                    }
                }
            }
        }

        Panel {
            Text("Passos", fontSize = 13.sp, color = c.textSecondary)
            Text(today?.steps?.let { number(it) } ?: "—", fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 44.sp, color = c.textPrimary)
            Row { Stat("Calorias ativas", s?.activeCalories?.let { "$it kcal" }, Modifier.weight(1f)); Stat("Calorias no dia", s?.calories?.let { "$it kcal" }, Modifier.weight(1f)) }
            s?.standingHours?.let { Text("Ficou de pé em ${it.size} horas", fontSize = 14.sp, color = c.textSecondary) }
        }
        Panel {
            Text("Batimentos", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            Row {
                Stat("Repouso", (sc?.restingHR?.value?.toInt() ?: s?.hrResting)?.let { "$it bpm" }, Modifier.weight(1f))
                Stat("Média", s?.hrAvg?.let { "$it bpm" }, Modifier.weight(1f))
                Stat("Máxima", s?.hrMax?.let { "$it bpm" }, Modifier.weight(1f))
            }
            val heart = today?.heart.orEmpty()
            if (heart.size > 2) LineChart(heart.map { it.first to it.second.toFloat() }, c.heart, Modifier.fillMaxWidth().height(120.dp))
        }
        sc?.strain?.let { st ->
            val zones = st.zoneMinutes.drop(1) // Z1..Z5
            if (zones.sum() > 0) {
                Panel {
                    Text("Zonas de esforço", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    val zColors = listOf(Color(0xFF4DC0CC), c.good, c.fair, Color(0xFFD9875F), c.action)
                    zones.forEachIndexed { idx, min ->
                        if (min > 0) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(zColors[idx], RoundedCornerShape(3.dp)))
                                Spacer(Modifier.width(8.dp))
                                Text("Z${idx + 1}", fontSize = 13.sp, color = c.textPrimary, modifier = Modifier.weight(1f))
                                Text("$min min", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                            }
                        }
                    }
                }
            }
        }
        Panel {
            Row {
                Stat("Estresse médio", (s?.stressAvg ?: sc?.stress?.average?.toInt())?.let { "$it" }, Modifier.weight(1f))
                Stat("SpO₂ médio", (s?.spo2Avg ?: sc?.spo2?.average?.toInt())?.let { "$it%" }, Modifier.weight(1f))
                Stat("Vitalidade", s?.vitalityCurrent?.let { "$it" }, Modifier.weight(1f))
            }
        }
        h.night(d)?.let { n ->
            Panel(Modifier.clickable { onOpenSleep() }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Detalhes da noite", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                        val scorePart = sc?.sleep?.score?.let { " · nota $it" } ?: ""
                        Text("${hours(n.asleepMinutes)} · ${time(n.session.bedTime)}–${time(n.session.wakeTime)}$scorePart",
                            fontSize = 13.sp, color = c.textSecondary)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textSecondary)
                }
            }
        }
        Panel(Modifier.clickable { onOpenTrain() }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AraraView(progress.stage, Modifier.size(50.dp), celebratedAt = nest.celebratedAt)
                Column(Modifier.weight(1f)) {
                    Text("${nest.name} · ${progress.stage.title}", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    Text(
                        if (care.resting) "Modo descanso ativo · sem perder progresso"
                        else "Semana: $activeDays de ${care.weeklyGoal} dias ativos · toque para treinar",
                        fontSize = 12.sp,
                        color = if (care.resting) c.textSecondary else c.action,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textSecondary)
            }
        }
    }
}

private fun stageColor(s: SleepStage, c: AraviaColors): Color = when (s) {
    SleepStage.DEEP -> Color(0xFF2E2F6E)
    SleepStage.LIGHT -> Color(0xFF8C86D2)
    SleepStage.REM -> Color(0xFF4DC0CC)
    SleepStage.AWAKE -> c.fair
    else -> c.textSecondary
}

/** Evolução: unified Tendências and Sono under a clean segmented toggle matching iOS. */
@Composable
fun ProgressScreen(
    section: ProgressSubTab,
    onSelectSection: (ProgressSubTab) -> Unit,
    headerTrailing: @Composable () -> Unit = {},
    onOpenWatches: () -> Unit = {},
) {
    val c = Aravia.colors
    val history = rememberHistory()
    var sleepIndex by remember { mutableStateOf<Int?>(null) }

    Screen("Evolução", headerTrailing = headerTrailing) {
        Row(
            Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProgressSubTab.entries.forEach { tab ->
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

        val h = history ?: return@Screen
        when (section) {
            ProgressSubTab.SLEEP -> SleepContent(h, sleepIndex, { sleepIndex = it }, onOpenWatches)
            ProgressSubTab.TRENDS -> TrendsContent(h, onOpenWatches)
        }
    }
}

@Composable
private fun SleepContent(
    h: HealthHistory,
    index: Int?,
    setIndex: (Int) -> Unit,
    onOpenWatches: () -> Unit,
) {
    val c = Aravia.colors
    if (h.nights.isEmpty()) {
        Empty(
            "Conecte seu relógio e durma com ele para ver estágios do sono, batimentos noturnos e HRV.",
            actionLabel = "Conectar relógio",
            onAction = onOpenWatches,
        )
        return
    }
    val i = (index ?: h.nights.lastIndex).coerceIn(0, h.nights.lastIndex)
    val n = h.nights[i]
    val sc = h.score(n.day)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { setIndex(i - 1) }, enabled = i > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Noite anterior") }
        Text("Noite até ${n.day.format(dayTitle)}", fontWeight = FontWeight.SemiBold, color = c.textPrimary, modifier = Modifier.weight(1f))
        IconButton(onClick = { setIndex(i + 1) }, enabled = i < h.nights.lastIndex) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Noite seguinte") }
    }
    val s = n.session
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dormiu", fontSize = 13.sp, color = c.textSecondary)
                Text(hours(n.asleepMinutes), fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 38.sp, color = c.textPrimary)
                Text("Deitou ${time(s.bedTime)} · acordou ${time(s.wakeTime)}", color = c.textSecondary, fontSize = 14.sp)
            }
            sc?.sleep?.score?.let { slp ->
                ScoreRing("Nota", slp.toString(), slp / 100f, scoreColor(slp, c), if (sc.sleep.estimated) "Estimativa" else null)
            }
        }
        if (s.stages.isNotEmpty()) Hypnogram(s, Modifier.fillMaxWidth().height(110.dp))
    }
    Panel {
        listOf("Profundo" to (s.deepMinutes to SleepStage.DEEP), "Leve" to (s.lightMinutes to SleepStage.LIGHT),
            "REM" to (s.remMinutes to SleepStage.REM), "Acordado" to (s.awakeMinutes to SleepStage.AWAKE)).forEach { (label, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(stageColor(v.second, c), RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(8.dp))
                Text(label, color = c.textPrimary, modifier = Modifier.weight(1f))
                Text(v.first?.let { hours(it) } ?: "—", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            }
        }
    }
    sc?.sleep?.contributors?.filter { it.subscore != null }?.takeIf { it.isNotEmpty() }?.let { list ->
        Panel {
            Text("Fatores da nota do sono", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            list.forEach { row ->
                val measured = when (row.name) {
                    SleepContributor.Name.DURATION -> row.value?.let { hours(it.toInt()) }
                    SleepContributor.Name.EFFICIENCY, SleepContributor.Name.DEEP, SleepContributor.Name.REM, SleepContributor.Name.HR_DIP ->
                        row.value?.let { "${(it * 100).toInt()}%" }
                    SleepContributor.Name.RESTFULNESS -> row.value?.let { "${it.toInt()} min acordado" }
                    SleepContributor.Name.REGULARITY -> row.value?.let { "±${it.toInt()} min" }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name.labelPt, color = c.textPrimary, fontSize = 14.sp)
                        measured?.let { Text(it, color = c.textSecondary, fontSize = 12.sp) }
                    }
                    val sub = row.subscore!!.toInt()
                    Text("$sub/100", fontWeight = FontWeight.SemiBold, color = scoreColor(sub, c), fontSize = 14.sp)
                }
            }
        }
    }
    sc?.hrv?.let { hrv ->
        Panel {
            Text("HRV noturna (aproximada)", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            Text("${hrv.rmssd.toInt()} ms · ${hrv.secondHalfWindows} janelas na 2ª metade da noite", color = c.textSecondary)
        }
    }
}

@Composable
private fun TrendsContent(h: HealthHistory, onOpenWatches: () -> Unit) {
    val c = Aravia.colors
    val last = h.lastDay ?: run {
        Empty(
            "Assim que seu relógio sincronizar por alguns dias, seus gráficos de recuperação, sono, esforço e saúde aparecem aqui.",
            actionLabel = "Conectar relógio",
            onAction = onOpenWatches,
        )
        return
    }
    val days = (13 downTo 0).map { last.minusDays(it.toLong()) }
    val labels = days.map { it.dayOfMonth.toString() }

    val recVals = days.map { h.score(it)?.recovery?.score?.toFloat() }
    if (recVals.any { it != null }) {
        Panel {
            Text("Recuperação", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            val avg = recVals.filterNotNull().average().toInt()
            Text("Média $avg nos últimos dias", color = c.textSecondary)
            BarChart(recVals, labels, c.good, Modifier.fillMaxWidth().height(130.dp), fixedMax = 100f)
        }
    }
    val slpScoreVals = days.map { h.score(it)?.sleep?.score?.toFloat() }
    if (slpScoreVals.any { it != null }) {
        Panel {
            Text("Nota do sono", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            val avg = slpScoreVals.filterNotNull().average().toInt()
            Text("Média $avg nos últimos dias", color = c.textSecondary)
            BarChart(slpScoreVals, labels, Color(0xFF8C86D2), Modifier.fillMaxWidth().height(130.dp), fixedMax = 100f)
        }
    }
    val strainVals = days.map { h.score(it)?.strain?.strain?.toFloat() }
    if (strainVals.any { it != null }) {
        Panel {
            Text("Esforço", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            val avg = String.format(ptBR, "%.1f", strainVals.filterNotNull().average())
            Text("Média $avg por dia (0–21)", color = c.textSecondary)
            BarChart(strainVals, labels, c.action, Modifier.fillMaxWidth().height(130.dp), fixedMax = 21f)
        }
    }
    Panel {
        Text("Passos", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        val v = days.map { h.day(it)?.steps?.toFloat() }
        Text("Média ${v.filterNotNull().takeIf { it.isNotEmpty() }?.let { number(it.average().toInt()) } ?: "—"} por dia", color = c.textSecondary)
        BarChart(v, labels, c.activity, Modifier.fillMaxWidth().height(140.dp))
    }
    Panel {
        Text("Sono", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        val v = days.map { d -> h.night(d)?.asleepMinutes?.let { it / 60f } }
        Text("Média ${v.filterNotNull().takeIf { it.isNotEmpty() }?.let { hours((it.average() * 60).toInt()) } ?: "—"} por noite", color = c.textSecondary)
        BarChart(v, labels, Color(0xFF8C86D2), Modifier.fillMaxWidth().height(140.dp))
    }
    Panel {
        Text("Batimentos em repouso", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        val v = days.map { (h.score(it)?.restingHR?.value ?: h.day(it)?.summary?.hrResting?.toDouble())?.toFloat() }
        Text("Média ${v.filterNotNull().takeIf { it.isNotEmpty() }?.let { "${it.average().toInt()} bpm" } ?: "—"}", color = c.textSecondary)
        BarChart(v, labels, c.heart, Modifier.fillMaxWidth().height(140.dp))
    }
}

@Composable
private fun BarChart(values: List<Float?>, labels: List<String>, color: Color, modifier: Modifier, fixedMax: Float? = null) {
    val c = Aravia.colors
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val max = fixedMax ?: values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
            val slot = size.width / values.size
            values.forEachIndexed { i, v ->
                v ?: return@forEachIndexed
                val h = size.height * (v / max).coerceIn(0f, 1f)
                drawRoundRect(color, Offset(i * slot + slot * 0.18f, size.height - h), Size(slot * 0.64f, h), CornerRadius(4f, 4f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, fontSize = 10.sp, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1,
                textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun LineChart(points: List<Pair<Long, Float>>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val t0 = points.first().first.toFloat(); val t1 = points.last().first.toFloat()
        val lo = points.minOf { it.second } - 5; val hi = points.maxOf { it.second } + 5
        if (t1 <= t0 || hi <= lo) return@Canvas
        val path = Path()
        var prev = 0L
        points.forEachIndexed { i, (t, v) ->
            val x = (t - t0) / (t1 - t0) * size.width
            val y = size.height - (v - lo) / (hi - lo) * size.height
            if (i == 0 || t - prev > 600) path.moveTo(x, y) else path.lineTo(x, y)
            prev = t
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}

@Composable
private fun Hypnogram(s: app.aravia.protocol.SleepSession, modifier: Modifier) {
    val c = Aravia.colors
    Canvas(modifier) {
        val span = (s.wakeTime - s.bedTime).toFloat().takeIf { it > 0 } ?: return@Canvas
        val rows = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
        val rowH = size.height / rows.size
        s.stages.forEachIndexed { i, st ->
            val start = st.ts
            val end = st.durationMinutes?.let { start + it * 60L } ?: s.stages.getOrNull(i + 1)?.ts ?: s.wakeTime
            val row = rows.indexOf(st.stage).takeIf { it >= 0 } ?: return@forEachIndexed
            val x0 = ((start - s.bedTime) / span * size.width).coerceIn(0f, size.width)
            val x1 = ((end - s.bedTime) / span * size.width).coerceIn(0f, size.width)
            if (x1 > x0) drawRoundRect(stageColor(st.stage, c), Offset(x0, row * rowH + rowH * 0.15f), Size(x1 - x0, rowH * 0.7f), CornerRadius(3f, 3f))
        }
    }
}
