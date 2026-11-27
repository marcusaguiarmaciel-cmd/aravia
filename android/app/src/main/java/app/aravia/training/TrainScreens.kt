package app.aravia.training

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aravia.integrations.Strava
import app.aravia.routes.RouteMap
import app.aravia.routes.RouteStore
import app.aravia.ui.Aravia
import app.aravia.ui.Barlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ptBR = Locale("pt", "BR")

private fun TrainingSession.Kind.icon(): ImageVector = when (this) {
    TrainingSession.Kind.TREADMILL -> Icons.AutoMirrored.Filled.DirectionsRun
    TrainingSession.Kind.GYM -> Icons.Filled.FitnessCenter
    TrainingSession.Kind.OTHER -> Icons.Filled.SportsGymnastics
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(ptBR, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(ptBR, "%d:%02d", s / 60, s % 60)
}

fun km(meters: Double): String = String.format(ptBR, "%.2f km", meters / 1000)

private fun day(ms: Long): String = SimpleDateFormat("EEE, d MMM · HH:mm", ptBR).format(Date(ms))

@Composable
private fun SectionTitle(text: String) =
    Text(text, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Aravia.colors.textPrimary)

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().background(Aravia.colors.surface, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)

enum class TrainSubTab(val title: String) {
    NOW("Agora"),
    ROUTES("Rotas"),
    HISTORY("Histórico"),
}

/**
 * The Treinar tab sections matching iOS:
 * - NOW: start/resume workout + live Bluetooth HR strap bar
 * - ROUTES: home-privacy zone + direct GPX import + GPS routes list for 3D map & Stories
 * - HISTORY: all gym & treadmill workouts logged in Aravia
 */
@Composable
fun TrainingSections(
    section: TrainSubTab = TrainSubTab.NOW,
    onOpenWatches: () -> Unit = {},
    onJumpToNow: () -> Unit = {},
) {
    val c = Aravia.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by WorkoutController.session.collectAsState()
    val sessions by TrainingStore.sessions.collectAsState()
    val routes by RouteStore.routes.collectAsState()
    val hrState by app.aravia.band.StandardHrLink.state.collectAsState()
    var showsWorkout by remember { mutableStateOf(running != null) }
    var openSession by remember { mutableStateOf<TrainingSession?>(null) }
    var openRoute by remember { mutableStateOf<RouteStore.Route?>(null) }
    var gpxMessage by remember { mutableStateOf<String?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickGpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            gpxMessage = null
            scope.launch {
                val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val xml = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: return@runCatching null
                        val fileName = uri.lastPathSegment?.substringAfterLast('/')
                        RouteStore.importGpx(xml, fallbackName = fileName)
                    }.getOrNull()
                }
                if (imported != null) {
                    openRoute = imported
                } else {
                    gpxMessage = "Esse arquivo não parece um GPX válido com pontos de rota."
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        when (app.aravia.Demo.screen) {
            "session" -> openSession = sessions.firstOrNull { it.id == "demo-gym" }
            "route", "story", "overlay", "video" -> openRoute = routes.firstOrNull()
        }
    }

    when (section) {
        TrainSubTab.NOW -> {
            val r = running
            if (r != null) {
                SectionTitle("Treino em andamento")
                Button(
                    onClick = { showsWorkout = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                ) {
                    Icon(Icons.Filled.Favorite, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Voltar ao treino de ${r.kind.title.lowercase()}", fontWeight = FontWeight.SemiBold)
                }
            } else {
                SectionTitle("Começar treino")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrainingSession.Kind.entries.forEach { kind ->
                        Column(
                            Modifier.weight(1f).height(88.dp).background(c.surface, RoundedCornerShape(16.dp))
                                .clickable {
                                    if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    WorkoutController.start(kind)
                                    showsWorkout = true
                                },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(kind.icon(), null, tint = c.action)
                            Spacer(Modifier.height(6.dp))
                            Text(kind.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.textPrimary)
                        }
                    }
                }
            }

            // Live HR strap status or quick shortcut to Relógios
            val hrConnected = hrState.status == app.aravia.band.StandardHrLink.Status.CONNECTED
            Panel(Modifier.clickable { onOpenWatches() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Favorite, null, tint = if (hrConnected) c.good else c.action)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (hrConnected) (hrState.deviceName ?: "Cinta cardíaca conectada") else "Conectar cinta cardíaca ao vivo",
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                        Text(
                            if (hrConnected) "Batimentos em tempo real ativos para o treino" else "Polar, Garmin HRM, Coospo, Wahoo e outras",
                            fontSize = 13.sp,
                            color = c.textSecondary,
                        )
                    }
                    if (hrConnected) {
                        Text(
                            hrState.bpm?.let { "$it bpm" } ?: "Ao vivo",
                            fontWeight = FontWeight.Bold,
                            color = c.good,
                        )
                    }
                }
            }
        }

        TrainSubTab.ROUTES -> {
            Panel {
                app.aravia.routes.PrivacyZoneRow()
            }
            OutlinedButton(
                onClick = { pickGpx.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Map, null)
                Spacer(Modifier.width(8.dp))
                Text("Importar rota (.gpx)", fontWeight = FontWeight.SemiBold)
            }
            gpxMessage?.let { Text(it, color = c.low, fontSize = 13.sp) }

            if (routes.isEmpty()) {
                Panel {
                    Text("Nenhuma corrida ou rota ainda", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    Text(
                        "Grave uma corrida ou pedalada com GPS no relógio, sincronize pelo Health Connect ou importe um arquivo .gpx acima para gerar mapa 3D e Stories.",
                        fontSize = 14.sp,
                        color = c.textSecondary,
                    )
                }
            } else {
                SectionTitle("Suas rotas (${routes.size})")
                routes.take(30).forEach { route ->
                    Panel(Modifier.clickable { openRoute = route }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (route.subtype == 0x01) Icons.AutoMirrored.Filled.DirectionsRun else Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = c.action)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(route.title, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                                val t = route.track
                                Text(
                                    listOfNotNull(
                                        day(route.ts * 1000),
                                        t?.let { km(it.distanceMeters) },
                                        t?.let { clock(it.durationSeconds * 1000) },
                                        if (route.isImported) "GPX" else null,
                                    ).joinToString(" · "),
                                    fontSize = 13.sp,
                                    color = c.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        TrainSubTab.HISTORY -> {
            if (sessions.isEmpty()) {
                Panel {
                    Text("Nenhum treino registrado ainda", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    Text(
                        "Seus treinos de musculação e esteira feitos no Aravia aparecem organizados aqui.",
                        fontSize = 14.sp,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onJumpToNow,
                        colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                    ) {
                        Text("Começar agora", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                SectionTitle("Treinos no Aravia (${sessions.size})")
                sessions.take(40).forEach { s ->
                    Panel(Modifier.clickable { openSession = s }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(s.kind.icon(), null, tint = c.action)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.kind.title, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                                Text(
                                    listOfNotNull(
                                        day(s.start),
                                        clock(s.durationMs),
                                        if (s.kind == TrainingSession.Kind.GYM && s.setCount > 0) "${s.setCount} séries" else null,
                                        s.avgBpm?.let { "$it bpm" },
                                    ).joinToString(" · "),
                                    fontSize = 13.sp,
                                    color = c.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showsWorkout && running != null) ActiveWorkoutScreen { showsWorkout = false }
    openSession?.let { s -> SessionScreen(s) { openSession = null } }
    openRoute?.let { route -> RouteScreen(route) { openRoute = null } }
}

/** The workout in progress, full screen: time, heart rate, the gym's exercises and sets, rest. */
@Composable
fun ActiveWorkoutScreen(close: () -> Unit) {
    val c = Aravia.colors
    val session by WorkoutController.session.collectAsState()
    val bpm by WorkoutController.bpm.collectAsState()
    val restEndsAt by WorkoutController.restEndsAt.collectAsState()
    val restSeconds by WorkoutController.restSeconds.collectAsState()
    val custom by TrainingStore.customExercises.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(500) } }
    var picking by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val s = session ?: return

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = c.canvas) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(s.kind.icon(), null, tint = c.action)
                    Spacer(Modifier.width(8.dp))
                    Text(s.kind.title, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.textPrimary, modifier = Modifier.weight(1f))
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, "Minimizar", tint = c.textSecondary) }
                }
                Text(clock(now - s.start), fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 56.sp, color = c.textPrimary)
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Favorite, null, tint = c.heart)
                        Spacer(Modifier.width(8.dp))
                        Text(bpm?.let { "$it bpm" } ?: "— bpm", fontWeight = FontWeight.Bold, fontSize = 28.sp, color = c.textPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text(bpm?.let { WorkoutController.zoneNames[WorkoutController.zone(it)] } ?: "", color = c.textSecondary)
                    }
                    if (bpm == null) Text("Os batimentos do relógio aparecem aqui quando o Android se conectar a ele (próxima etapa).",
                        fontSize = 13.sp, color = c.textSecondary)
                }

                restEndsAt?.let { ends ->
                    Panel {
                        Text("Descanso", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                        Text(clock(ends - now + 999), fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = c.action)
                        TextButton(onClick = { WorkoutController.skipRest() }) { Text("Pular descanso") }
                    }
                }

                when (s.kind) {
                    TrainingSession.Kind.GYM -> {
                        s.entries.forEach { e -> EntryCard(e) }
                        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("Adicionar exercício") }
                        Text("Descanso entre séries", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(60, 90, 120, 180).forEach { sec ->
                                FilterChip(selected = restSeconds == sec, onClick = { WorkoutController.setRestSeconds(sec) },
                                    label = { Text(if (sec % 60 == 0) "${sec / 60} min" else "${sec / 60}:${sec % 60}") })
                            }
                        }
                    }
                    else -> {
                        Panel {
                            Text(WorkoutController.detail(s), fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
                            if (s.typedDistanceMeters == null) Text("Distância estimada pelos passos.", fontSize = 13.sp, color = c.textSecondary)
                        }
                        if (s.kind == TrainingSession.Kind.TREADMILL) {
                            var typed by remember { mutableStateOf(s.typedDistanceMeters?.let { String.format(ptBR, "%.2f", it / 1000) } ?: "") }
                            OutlinedTextField(typed, { v ->
                                typed = v
                                WorkoutController.setTypedDistance(v.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.times(1000))
                            }, label = { Text("Distância no visor da esteira (km)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(onClick = { ending = true }, modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction)) {
                    Text("Encerrar treino", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (picking) ExercisePicker(custom, close = { picking = false }) { WorkoutController.addEntry(it); picking = false }
        if (ending) AlertDialog(onDismissRequest = { ending = false },
            title = { Text("Encerrar o treino?") },
            text = { Text(if (Strava.state.value.connected && Strava.state.value.automatic) "Ele fica salvo aqui e vai para o Strava." else "Ele fica salvo na lista de treinos.") },
            confirmButton = { TextButton(onClick = { ending = false; WorkoutController.finish(); close() }) { Text("Encerrar e salvar") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { ending = false; WorkoutController.discard(); close() }) { Text("Descartar", color = c.low) }
                    TextButton(onClick = { ending = false }) { Text("Continuar") }
                }
            })
    }
}

/** One exercise of the gym workout: its sets, and the next set's reps and load. */
@Composable
private fun EntryCard(e: TrainingSession.Entry) {
    val c = Aravia.colors
    val previous = remember(e.exercise) { TrainingStore.lastSet(e.exercise) }
    val last = e.sets.lastOrNull() ?: previous
    var reps by remember(e.id) { mutableStateOf((last?.reps ?: 10).toString()) }
    var kg by remember(e.id) { mutableStateOf(last?.kg?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.exercise, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.textPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = { WorkoutController.removeEntry(e.id) }) { Icon(Icons.Filled.Delete, "Tirar exercício", tint = c.textSecondary) }
        }
        e.sets.forEachIndexed { i, set ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Série ${i + 1}: ${set.reps} × ${formatKg(set.kg)} kg", color = c.textPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = { WorkoutController.removeSet(e.id, set.id) }) { Text("Apagar", color = c.textSecondary) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(reps, { reps = it.filter(Char::isDigit) }, label = { Text("Reps") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(kg, { kg = it }, label = { Text("Kg") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        }
        val r = reps.toIntOrNull()
        Button(onClick = { WorkoutController.logSet(e.id, r ?: 0, kg.replace(',', '.').toDoubleOrNull() ?: 0.0) },
            enabled = r != null && r > 0, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction)) { Text("Registrar série ${e.sets.size + 1}") }
    }
}

private fun formatKg(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(ptBR, "%.1f", v)

@Composable
private fun ExercisePicker(custom: List<String>, close: () -> Unit, pick: (String) -> Unit) {
    val c = Aravia.colors
    var typed by remember { mutableStateOf("") }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = c.canvas) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Exercício", fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.textPrimary, modifier = Modifier.weight(1f))
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, "Fechar", tint = c.textSecondary) }
                }
                OutlinedTextField(typed, { typed = it }, label = { Text("Outro exercício") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (typed.isNotBlank()) Button(onClick = { TrainingStore.addExercise(typed); pick(typed.trim()) }) { Text("Usar \"${typed.trim()}\"") }
                val groups = (if (custom.isEmpty()) emptyList() else listOf("Seus exercícios" to custom)) + ExerciseCatalog.groups
                groups.forEach { (group, names) ->
                    Text(group, fontWeight = FontWeight.Bold, color = c.action, modifier = Modifier.padding(top = 8.dp))
                    names.forEach { name ->
                        Text(name, fontSize = 17.sp, color = c.textPrimary,
                            modifier = Modifier.fillMaxWidth().clickable { pick(name) }.padding(vertical = 10.dp))
                    }
                }
            }
        }
    }
}

/** A finished workout: its numbers, its sets, Strava and delete. */
@Composable
private fun SessionScreen(s: TrainingSession, close: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strava by Strava.state.collectAsState()
    var deleting by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = c.canvas) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.kind.title, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = c.textPrimary, modifier = Modifier.weight(1f))
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, "Fechar", tint = c.textSecondary) }
                }
                Text(day(s.start), color = c.textSecondary)
                Panel {
                    Fact("Duração", clock(s.durationMs))
                    s.avgBpm?.let { Fact("FC média", "$it bpm") }
                    s.maxBpm?.let { Fact("FC máxima", "$it bpm") }
                    if (s.kind != TrainingSession.Kind.GYM) Fact(if (s.typedDistanceMeters != null) "Distância" else "Distância estimada", km(s.distanceMeters()))
                    if (s.steps > 0) Fact("Passos", s.steps.toString())
                    if (s.setCount > 0) { Fact("Séries", s.setCount.toString()); Fact("Volume", "${formatKg(s.volumeKg)} kg") }
                }
                s.entries.filter { it.sets.isNotEmpty() }.forEach { e ->
                    Panel {
                        Text(e.exercise, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                        Text(e.sets.joinToString("  ·  ") { "${it.reps} × ${formatKg(it.kg)} kg" }, color = c.textSecondary)
                    }
                }
                if (strava.connected) {
                    val id = Strava.activityId(s.start / 1000)
                    if (id != null) {
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/$id"))) },
                            modifier = Modifier.fillMaxWidth()) { Text("Ver no Strava") }
                    } else {
                        OutlinedButton(onClick = { scope.launch { runCatching { Strava.sendSessionNow(s) } } }, enabled = !strava.busy,
                            modifier = Modifier.fillMaxWidth()) { Text(if (strava.busy) "Enviando…" else "Enviar para o Strava") }
                    }
                    strava.error?.let { Text(it, color = c.low, fontSize = 13.sp) }
                }
                TextButton(onClick = { deleting = true }) { Text("Apagar treino", color = c.low) }
            }
        }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false },
            title = { Text("Apagar este treino?") },
            text = { Text("Ele sai deste celular. Se já foi para o Strava, continua lá.") },
            confirmButton = { TextButton(onClick = { TrainingStore.delete(s.id); deleting = false; close() }) { Text("Apagar", color = c.low) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } })
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, color = Aravia.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, color = Aravia.colors.textPrimary)
    }
}

/** One GPS route on the 3D map, with replay, privacy zone, GPX export, Story (photo/video) and Strava. */
@Composable
private fun RouteScreen(route: RouteStore.Route, close: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strava by Strava.state.collectAsState()
    val privacyMeters by app.aravia.routes.RoutePrivacy.meters.collectAsState()
    val track = route.track
    var story by remember { mutableStateOf(app.aravia.Demo.screen in setOf("story", "overlay", "video")) }
    var tilted by remember { mutableStateOf(true) }
    var replaying by remember { mutableStateOf(false) }
    var replayFraction by remember { mutableStateOf<Float?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(replaying) {
        if (!replaying) return@LaunchedEffect
        var f = replayFraction ?: 0f
        if (f >= 1f) f = 0f
        while (replaying && f < 1f) {
            replayFraction = f
            kotlinx.coroutines.delay(50)
            f = (f + 0.05f / 12f).coerceAtMost(1f)
        }
        replayFraction = if (replaying) null else replayFraction
        replaying = false
    }

    if (story) { app.aravia.routes.StoryHub(route) { story = false }; return }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = c.canvas) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Map, null, tint = c.action)
                    Spacer(Modifier.width(8.dp))
                    Text(route.title, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.textPrimary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { tilted = !tilted }) { Text(if (tilted) "3D" else "2D", fontWeight = FontWeight.Bold) }
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, "Fechar", tint = c.textSecondary) }
                }
                if (track == null) {
                    Text("Este arquivo de GPS (versão ${route.version}) ainda não é suportado.", color = c.textSecondary, modifier = Modifier.padding(20.dp))
                } else {
                    RouteMap(track, c.action, Modifier.fillMaxWidth().weight(1f), tilted = tilted, replayFraction = replayFraction)
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(day(route.ts * 1000), color = c.textSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text(km(track.distanceMeters), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.textPrimary)
                            Text(clock(track.durationSeconds * 1000), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.textPrimary)
                            if (track.distanceMeters > 100) {
                                val pace = track.durationSeconds / (track.distanceMeters / 1000)
                                Text(String.format(ptBR, "%d:%02d /km", pace.toLong() / 60, pace.toLong() % 60), fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp, color = c.textPrimary)
                            }
                        }
                        replayFraction?.let { frac ->
                            Slider(
                                value = frac,
                                onValueChange = { replaying = false; replayFraction = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        app.aravia.routes.PrivacyZoneRow(compact = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (replaying) { replaying = false }
                                    else { if (replayFraction == null || replayFraction!! >= 0.99f) replayFraction = 0f; replaying = true }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (replaying) "Pausar replay" else "Reproduzir rota") }
                            OutlinedButton(
                                onClick = {
                                    exportError = null
                                    when (val res = app.aravia.routes.RoutePrivacy.apply(track, privacyMeters)) {
                                        is app.aravia.routes.RoutePrivacy.Outcome.TooShort -> {
                                            exportError = "Rota menor que ${app.aravia.routes.RoutePrivacy.label(res.radiusMeters)}. Reduza o raio acima para exportar."
                                        }
                                        is app.aravia.routes.RoutePrivacy.Outcome.Ready -> runCatching {
                                            val dir = java.io.File(context.cacheDir, "raw").apply { mkdirs() }
                                            val file = java.io.File(dir, "aravia-rota-${route.fileId}.gpx")
                                            file.writeText(res.track.gpx(route.title), Charsets.UTF_8)
                                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                                type = "application/gpx+xml"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }, "Exportar GPX"))
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Exportar GPX") }
                        }
                        exportError?.let { Text(it, color = c.low, fontSize = 13.sp) }
                        Button(
                            onClick = { replaying = false; story = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                        ) { Text("Story (Foto / Vídeo)", fontWeight = FontWeight.SemiBold) }
                        if (strava.connected) {
                            val id = Strava.activityId(track.start ?: route.ts)
                            if (id != null) {
                                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/$id"))) },
                                    modifier = Modifier.fillMaxWidth()) { Text("Ver no Strava") }
                            } else {
                                OutlinedButton(onClick = { scope.launch { runCatching { Strava.sendRouteNow(route) } } }, enabled = !strava.busy,
                                    modifier = Modifier.fillMaxWidth()) { Text(if (strava.busy) "Enviando…" else "Enviar para o Strava") }
                            }
                            strava.error?.let { Text(it, color = c.low, fontSize = 13.sp) }
                        }
                        if (route.isImported) {
                            TextButton(onClick = { confirmingDelete = true }) { Text("Apagar rota importada", color = c.low) }
                        }
                    }
                }
            }
        }
        if (confirmingDelete) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("Apagar esta rota importada?") },
                text = { Text("O arquivo GPX sai deste celular. Se já foi enviado ao Strava, continua lá.") },
                confirmButton = {
                    TextButton(onClick = {
                        RouteStore.deleteImported(route.fileId)
                        confirmingDelete = false
                        close()
                    }) { Text("Apagar", color = c.low) }
                },
                dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancelar") } },
            )
        }
    }
}
