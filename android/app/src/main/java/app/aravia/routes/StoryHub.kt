package app.aravia.routes

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.aravia.arara.AraraNest
import app.aravia.arara.AraraProgress
import app.aravia.ui.Aravia
import app.aravia.ui.Barlow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class StoryMode(val label: String) { OVERLAY("Foto / Overlay"), VIDEO("Vídeo 3D") }

/**
 * Story creator for a GPS route:
 * - **Foto / Overlay** (instant, < 1 s): transparent PNG sticker or composited over a gallery photo,
 *   with the route silhouette, numbers, Arara and home-privacy zone.
 * - **Vídeo 3D**: animated tilted map replay + the Arara finale, saved to Movies/Aravia, SAF folder
 *   or shared directly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoryHub(route: RouteStore.Route, close: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rawTrack = route.track ?: return
    LaunchedEffect(Unit) { StoryTemplateStore.init(context) }

    val customTemplates by StoryTemplateStore.custom.collectAsState()
    val folderUri by StoryTemplateStore.folderUri.collectAsState()
    val privacyMeters by RoutePrivacy.meters.collectAsState()
    val account by app.aravia.account.Account.state.collectAsState()
    val adPass by app.aravia.account.RewardedPremium.state.collectAsState()
    val lab by app.aravia.account.SupporterAccess.lab.collectAsState()
    val hasPremium = account.hasEarlyAccess || adPass.isAdPassActive()
    val hideWatermark = hasPremium && lab.hideStoryWatermark
    val nest by AraraNest.state.collectAsState()
    val stage = AraraProgress(AraraNest.totalXp(nest.accountXp ?: 0)).stage

    var mode by remember {
        mutableStateOf(if (app.aravia.Demo.screen == "overlay") StoryMode.OVERLAY else StoryMode.VIDEO)
    }

    // Apply home-privacy trim reactively.
    val privacyOutcome = remember(rawTrack, privacyMeters) { RoutePrivacy.apply(rawTrack, privacyMeters) }
    val shareTrack = (privacyOutcome as? RoutePrivacy.Outcome.Ready)?.track

    // --- Overlay state ---
    var overlayAccent by remember { mutableStateOf(StoryOverlay.accents.first()) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var overlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var overlaySaved by remember { mutableStateOf<String?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            photoUri = uri
            overlaySaved = null
            scope.launch {
                photoBitmap = withContext(Dispatchers.IO) { StoryOverlay.decodePhoto(context, uri) }
            }
        }
    }

    LaunchedEffect(mode, shareTrack, overlayAccent, photoBitmap, stage, hideWatermark) {
        if (mode != StoryMode.OVERLAY || shareTrack == null) return@LaunchedEffect
        overlayBitmap = withContext(Dispatchers.Default) {
            val trans = StoryOverlay.renderTransparent(
                context, shareTrack, route.title, route.ts * 1000, stage, overlayAccent.rgb,
                showWatermark = !hideWatermark,
            )
            val bg = photoBitmap
            if (bg != null) StoryOverlay.renderOverPhoto(bg, trans) else trans
        }
    }

    // --- Video state ---
    var template by remember { mutableStateOf(StoryTemplate.builtIn.first()) }
    var editing by remember { mutableStateOf(false) }
    var savingTemplate by remember { mutableStateOf(false) }
    var newTemplateName by remember { mutableStateOf("") }
    var showEnding by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var video by remember { mutableStateOf<File?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var renderer by remember { mutableStateOf<RouteVideo?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            StoryTemplateStore.setFolder(uri)
            video?.let { f -> saved = saveToFolder(context, uri, f) }
        }
    }

    fun makeVideo() = shareTrack?.let { RouteVideo(context, it, route.title, route.ts * 1000, template, stage) }

    LaunchedEffect(mode, template, showEnding, shareTrack) {
        if (mode != StoryMode.VIDEO) return@LaunchedEffect
        val r = makeVideo() ?: run { preview = null; return@LaunchedEffect }
        preview = null; error = null
        preview = runCatching { r.preview(if (showEnding) 1.0 else 0.45) }.onFailure { error = it.message }.getOrNull()
    }

    // The emulator run makes the whole video and says in the log how it went (android/tools/screens.sh).
    LaunchedEffect(Unit) {
        if (app.aravia.Demo.screen != "video") return@LaunchedEffect
        val t = shareTrack ?: rawTrack
        mode = StoryMode.VIDEO
        showEnding = true
        progress = 0f
        runCatching { RouteVideo(context, t, route.title, route.ts * 1000, template.copy(seconds = 1.0), stage).render { progress = it } }
            .onSuccess { video = it; android.util.Log.i("AraviaVideo", "done ${it.length()} bytes") }
            .onFailure { error = it.message; android.util.Log.e("AraviaVideo", "failed: $it", it) }
        progress = null
    }

    val allTemplates = StoryTemplate.builtIn + customTemplates

    Dialog(onDismissRequest = { job?.cancel(); renderer?.cancel(); close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = c.canvas) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Story da rota", fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.textPrimary, modifier = Modifier.weight(1f))
                    if (mode == StoryMode.VIDEO) {
                        TextButton(onClick = { editing = !editing }, enabled = progress == null) {
                            Text(if (editing) "Pronto" else "Personalizar")
                        }
                    }
                    IconButton(onClick = { job?.cancel(); renderer?.cancel(); close() }) { Icon(Icons.Filled.Close, "Fechar", tint = c.textSecondary) }
                }

                // Mode switcher: instant Overlay vs animated 3D Video.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StoryMode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { if (progress == null) mode = m },
                            label = { Text(m.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Automatic home-privacy zone control.
                Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    PrivacyZoneRow(compact = true)
                }

                if (shareTrack == null && privacyOutcome is RoutePrivacy.Outcome.TooShort) {
                    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Esta rota é mais curta que o raio de privacidade (${RoutePrivacy.label(privacyOutcome.radiusMeters)}). Para não expor sua casa por acidente, ela ficou oculta.",
                            color = c.low, fontSize = 14.sp,
                        )
                        OutlinedButton(onClick = { RoutePrivacy.set(0) }) { Text("Desligar privacidade para esta rota") }
                    }
                } else if (mode == StoryMode.OVERLAY) {
                    // Instant transparent PNG / photo overlay mode.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StoryOverlay.accents.forEach { acc ->
                            FilterChip(
                                selected = overlayAccent == acc,
                                onClick = { overlayAccent = acc; overlaySaved = null },
                                label = { Text(acc.label) },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickPhoto.launch("image/*") }, modifier = Modifier.weight(1f)) {
                            Text(if (photoBitmap == null) "Escolher foto de fundo" else "Trocar foto")
                        }
                        if (photoBitmap != null) {
                            TextButton(onClick = { photoUri = null; photoBitmap = null; overlaySaved = null }) { Text("Fundo transparente") }
                        }
                    }

                    Box(
                        Modifier.fillMaxWidth(0.62f).aspectRatio(9f / 16f).align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (photoBitmap == null) Checkerboard(Modifier.fillMaxSize())
                        val bmp = overlayBitmap
                        if (bmp != null) Image(bmp.asImageBitmap(), "Prévia do overlay", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else CircularProgressIndicator(color = c.action)
                    }

                    Text(
                        if (photoBitmap == null) "PNG transparente pronto na hora: salve ou compartilhe e cole em cima de qualquer foto nos Stories."
                        else "Foto pronta para postar direto nos Stories.",
                        fontSize = 13.sp, color = c.textSecondary,
                    )

                    val bmp = overlayBitmap
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = bmp != null,
                            onClick = {
                                bmp?.let {
                                    overlaySaved = StoryOverlay.savePngToGallery(context, it, "aravia-story-${route.fileId}")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                        ) { Text("Salvar PNG", fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            enabled = bmp != null,
                            onClick = {
                                bmp?.let {
                                    val file = StoryOverlay.writeTempPng(context, it, route.fileId)
                                    shareImage(context, file)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Compartilhar") }
                    }
                    overlaySaved?.let { Text(it, fontSize = 13.sp, color = c.textSecondary) }
                } else {
                    // Animated 3D video mode.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allTemplates.forEach { t ->
                            FilterChip(
                                selected = t == template,
                                onClick = { template = t; video = null; saved = null },
                                label = { Text(t.name) },
                                enabled = progress == null,
                            )
                        }
                    }
                    Text("${template.map.title} · ${template.camera.title} · ${template.layout.title} · ${template.seconds.toInt()} s de rota + o final da arara",
                        fontSize = 13.sp, color = c.textSecondary)

                    if (editing) {
                        Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Mapa", fontWeight = FontWeight.SemiBold, color = c.textPrimary, fontSize = 14.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StoryTemplate.MapLook.entries.forEach { m ->
                                    FilterChip(selected = template.map == m, onClick = { template = template.copy(map = m); video = null }, label = { Text(m.title) })
                                }
                            }
                            Text("Câmera", fontWeight = FontWeight.SemiBold, color = c.textPrimary, fontSize = 14.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StoryTemplate.Camera.entries.forEach { cam ->
                                    FilterChip(selected = template.camera == cam, onClick = { template = template.copy(camera = cam); video = null }, label = { Text(cam.title) })
                                }
                            }
                            Text("Estilo", fontWeight = FontWeight.SemiBold, color = c.textPrimary, fontSize = 14.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StoryTemplate.Layout.entries.forEach { lay ->
                                    FilterChip(selected = template.layout == lay, onClick = { template = template.copy(layout = lay); video = null }, label = { Text(lay.title) })
                                }
                            }
                            Text("Duração da rota", fontWeight = FontWeight.SemiBold, color = c.textPrimary, fontSize = 14.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(8.0, 10.0, 12.0, 15.0, 20.0).forEach { sec ->
                                    FilterChip(selected = template.seconds == sec, onClick = { template = template.copy(seconds = sec); video = null }, label = { Text("${sec.toInt()} s") })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(selected = template.speedColors, onClick = { template = template.copy(speedColors = !template.speedColors) }, label = { Text("Cor por ritmo") })
                                FilterChip(selected = template.shades, onClick = { template = template.copy(shades = !template.shades) }, label = { Text("Sombras") })
                                FilterChip(selected = template.showDate, onClick = { template = template.copy(showDate = !template.showDate) }, label = { Text("Data") })
                                FilterChip(selected = template.showDistance, onClick = { template = template.copy(showDistance = !template.showDistance) }, label = { Text("Distância") })
                                FilterChip(selected = template.showTime, onClick = { template = template.copy(showTime = !template.showTime) }, label = { Text("Tempo") })
                                FilterChip(selected = template.showSpeed, onClick = { template = template.copy(showSpeed = !template.showSpeed) }, label = { Text("Ritmo") })
                                FilterChip(selected = template.showProgress, onClick = { template = template.copy(showProgress = !template.showProgress) }, label = { Text("Progresso") })
                                FilterChip(selected = template.showBrand, onClick = { template = template.copy(showBrand = !template.showBrand) }, label = { Text("Marca") })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                OutlinedButton(onClick = { newTemplateName = if (template.custom) template.name else "${template.name} meu"; savingTemplate = true }) {
                                    Text("Salvar modelo")
                                }
                                if (template.custom) {
                                    TextButton(onClick = {
                                        StoryTemplateStore.delete(template.name)
                                        template = StoryTemplate.builtIn.first()
                                    }) { Text("Apagar modelo", color = c.low) }
                                }
                            }
                        }
                    }

                    Box(Modifier.fillMaxWidth(0.62f).aspectRatio(9f / 16f).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(18.dp)).background(c.surface),
                        contentAlignment = Alignment.Center) {
                        val p = preview
                        if (p != null) Image(p.asImageBitmap(), "Prévia", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else if (error == null) CircularProgressIndicator(color = c.action)
                        else Text(error ?: "", color = c.low, modifier = Modifier.padding(16.dp))
                    }
                    Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showEnding, onClick = { showEnding = false }, label = { Text("Rota") })
                        FilterChip(selected = showEnding, onClick = { showEnding = true }, label = { Text("Final") })
                    }
                    val pr = progress
                    if (pr != null) {
                        LinearProgressIndicator(progress = { pr }, color = c.action, modifier = Modifier.fillMaxWidth())
                        Text("Gerando o vídeo… ${(pr * 100).toInt()}%. Leva um ou dois minutos; deixe o app aberto.", fontSize = 13.sp, color = c.textSecondary)
                        TextButton(onClick = { job?.cancel(); renderer?.cancel(); progress = null }) { Text("Cancelar") }
                    } else {
                        Button(onClick = {
                            val r = makeVideo() ?: return@Button
                            video = null; saved = null; error = null
                            renderer = r
                            job = scope.launch {
                                progress = 0f
                                runCatching { r.render { progress = it } }
                                    .onSuccess { video = it }
                                    .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: "Não deu para gerar o vídeo." }
                                progress = null
                            }
                        }, modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction)) {
                            Text(if (video == null) "Gerar vídeo" else "Gerar de novo", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    video?.let { f ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { saved = saveToGallery(context, f) }, modifier = Modifier.weight(1f)) { Text("Salvar na galeria") }
                            OutlinedButton(onClick = { shareVideo(context, f) }, modifier = Modifier.weight(1f)) { Text("Compartilhar") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val existing = folderUri?.let { Uri.parse(it) }
                                    if (existing != null) saved = saveToFolder(context, existing, f)
                                    else pickFolder.launch(null)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (folderUri != null) "Salvar na pasta escolhida" else "Escolher pasta…") }
                            if (folderUri != null) {
                                TextButton(onClick = { pickFolder.launch(null) }) { Text("Trocar pasta") }
                            }
                        }
                        saved?.let { Text(it, fontSize = 13.sp, color = c.textSecondary) }
                    }
                    error?.takeIf { preview != null || progress == null }?.let { Text(it, color = c.low, fontSize = 13.sp) }
                }
            }
        }
    }

    if (savingTemplate) {
        AlertDialog(
            onDismissRequest = { savingTemplate = false },
            title = { Text("Salvar modelo neste celular") },
            text = {
                OutlinedTextField(newTemplateName, { newTemplateName = it.take(24) }, label = { Text("Nome do modelo") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = newTemplateName.isNotBlank(),
                    onClick = {
                        val savedCopy = template.copy(name = newTemplateName.trim(), custom = true)
                        StoryTemplateStore.save(savedCopy)
                        template = savedCopy
                        savingTemplate = false
                    },
                ) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { savingTemplate = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Checkerboard(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = 24f
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        val a = Color(0xFF1F262B)
        val b = Color(0xFF2B343B)
        for (r in 0 until rows) {
            for (col in 0 until cols) {
                drawRect(
                    color = if ((r + col) % 2 == 0) a else b,
                    topLeft = Offset(col * cell, r * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

private fun shareVideo(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Compartilhar o vídeo"))
}

private fun shareImage(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Compartilhar Story"))
}

/** Into Movies/Aravia, where the gallery shows it; no permission needed from Android 10 on. */
private fun saveToGallery(context: Context, file: File): String = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Aravia")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
    resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    "Salvo na galeria, em Filmes › Aravia."
}.getOrElse { "Não deu para salvar: ${it.message}" }

/** Saves the video into a user-chosen SAF folder tree. */
private fun saveToFolder(context: Context, treeUri: Uri, file: File): String = runCatching {
    val resolver = context.contentResolver
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    val created = DocumentsContract.createDocument(resolver, parent, "video/mp4", file.name)
        ?: error("Pasta sem permissão de escrita.")
    resolver.openOutputStream(created)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    "Salvo na pasta escolhida."
}.getOrElse { "Não deu para salvar na pasta: ${it.message}" }
