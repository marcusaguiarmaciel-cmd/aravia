package app.aravia.band

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.aravia.ui.Aravia
import app.aravia.ui.Barlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full Diagnostics sheet (Android twin of DiagnosticsView.swift): session state, device info,
 * read-only watch probe, bind-mode probe, reparse, raw-files ZIP export and session log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(band: BandVault.Band?, onClose: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    val phase by BandLink.phase.collectAsState()
    val holdState by BandLink.holdState.collectAsState()
    val lastOutcome by BandLink.lastOutcome.collectAsState()
    val deviceState by BandLink.deviceState.collectAsState()
    val log by BandLink.log.collectAsState()
    val storedVersion by ActivityStore.version.collectAsState()

    var reparseResult by remember { mutableStateOf<ActivityStore.ReparseSummary?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var askingBindProbe by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) pendingAction?.invoke()
        pendingAction = null
    }
    fun withBle(action: () -> Unit) {
        if (BandPermissions.granted(context)) action() else { pendingAction = action; askPerm.launch(BandPermissions.needed) }
    }

    val files = remember(storedVersion) { ActivityStore.inspect(60) }
    val totalFiles = remember(storedVersion) { ActivityStore.count }
    val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")) }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.canvas) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Diagnóstico", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = c.textPrimary)

            // Status card
            Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DiagRow("Estado", phase.label.ifEmpty { "Parado" })
                lastOutcome?.let { DiagRow("Última sessão", it.label) }
                DiagRow(
                    "Fica conectado",
                    when (holdState) {
                        BandLink.HoldState.OFF -> "Desligado"
                        BandLink.HoldState.CONNECTING -> "Reconectando…"
                        BandLink.HoldState.CONNECTED -> "Conectado"
                    },
                )
                band?.hardwareModel?.takeIf { it.isNotBlank() }?.let { DiagRow("Modelo", it) }
                band?.firmware?.takeIf { it.isNotBlank() }?.let { DiagRow("Firmware", it) }
                deviceState?.let { ds ->
                    val flags = listOfNotNull(
                        if (ds.charging) "carregando" else null,
                        if (ds.worn) "no pulso" else "fora do pulso",
                        if (ds.asleep) "dormindo" else null,
                    )
                    DiagRow("Estado do relógio", flags.joinToString(" · "))
                }
            }

            // Tools card
            Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ferramentas", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
                OutlinedButton(
                    onClick = { reparseResult = ActivityStore.reparseAll() },
                    enabled = !phase.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reprocessar todos os arquivos") }
                reparseResult?.let { r ->
                    Text(
                        "${r.total} arquivos: ${r.parsed} lidos · ${r.unknown} desconhecidos · ${r.failed} erros.",
                        fontSize = 13.sp, color = if (r.failed > 0) c.low else c.textSecondary,
                    )
                }
                if (band != null) {
                    OutlinedButton(
                        onClick = { withBle { BandSettings.probeBand(band) } },
                        enabled = !phase.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sondar o relógio") }
                    OutlinedButton(
                        onClick = { askingBindProbe = true },
                        enabled = !phase.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Perguntar como o relógio pareia") }
                }
                Text("A sondagem envia apenas consultas de leitura (8/6, 7, 29, 42, 44, 61, 63, 86) e grava as respostas no registro.",
                    fontSize = 12.sp, color = c.textSecondary)
            }

            // Stored raw files card
            Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Arquivos do relógio ($totalFiles)", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
                if (files.isEmpty()) {
                    Text("Nenhum arquivo ainda. Depois de uma sincronização, os registros aparecem aqui.", fontSize = 14.sp, color = c.textSecondary)
                } else {
                    Button(
                        onClick = {
                            runCatching {
                                val zip = ActivityStore.exportRawZip(context) ?: error("Sem arquivos para exportar.")
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Compartilhar arquivos brutos"))
                                exportError = null
                            }.onFailure { exportError = it.localizedMessage ?: "Não deu para exportar os arquivos." }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                    ) { Text("Compartilhar arquivos brutos ($totalFiles)") }
                    Text("Os arquivos brutos contêm seus dados de saúde. Compartilhe apenas para depuração.", fontSize = 12.sp, color = c.textSecondary)
                    exportError?.let { Text(it, color = c.low, fontSize = 13.sp) }
                    files.forEach { f ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(f.kindLabel, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val meta = listOfNotNull(
                                dateFmt.format(Date(f.id.timestamp * 1000)),
                                "${f.sizeBytes} B",
                                f.status,
                                f.note,
                            ).joinToString(" · ")
                            Text(meta, color = if (f.status == "erro") c.low else c.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Log card
            Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Registro (${log.size})", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
                    if (log.isNotEmpty()) {
                        TextButton(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, log.joinToString("\n"))
                            }, "Compartilhar registro"))
                        }) { Text("Compartilhar") }
                    }
                }
                if (log.isEmpty()) {
                    Text("Nenhuma sessão ainda.", fontSize = 14.sp, color = c.textSecondary)
                } else {
                    log.takeLast(120).forEach { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c.textSecondary)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Fechar") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (askingBindProbe && band != null) {
        AlertDialog(
            onDismissRequest = { askingBindProbe = false },
            title = { Text("Perguntar como o relógio pareia?") },
            text = {
                Text("O Aravia envia apenas o primeiro passo do pareamento local e para. A resposta aparece no registro. Num relógio já pareado ele deve apenas recusar, mas pode abrir uma tela de pareamento.")
            },
            confirmButton = {
                TextButton(onClick = {
                    askingBindProbe = false
                    withBle { BandSettings.probeBind(band) }
                }) { Text("Perguntar") }
            },
            dismissButton = { TextButton(onClick = { askingBindProbe = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    val c = Aravia.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = c.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
