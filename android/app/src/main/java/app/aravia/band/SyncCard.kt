package app.aravia.band

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aravia.ui.Aravia

/**
 * Inside the watch card: sync now, Stay connected ("Fica conectado"), Find band (vibrate),
 * Health monitoring, Diagnostics and live session feedback.
 */
@Composable
fun SyncSection(
    band: BandVault.Band,
    onOpenMonitoring: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val c = Aravia.colors
    val context = LocalContext.current
    val phase by BandLink.phase.collectAsState()
    val holdState by BandLink.holdState.collectAsState()
    val stayConnected by BandSettings.stayConnected.collectAsState()
    val battery by BandLink.battery.collectAsState()
    val live by BandLink.live.collectAsState()
    val deviceState by BandLink.deviceState.collectAsState()
    val progress by BandLink.progress.collectAsState()
    val stored by ActivityStore.version.collectAsState()
    var vibrating by remember { mutableStateOf(false) }
    var pendingBle by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) pendingBle?.invoke()
        pendingBle = null
    }
    fun withBle(action: () -> Unit) {
        if (BandPermissions.granted(context)) action() else { pendingBle = action; ask.launch(BandPermissions.needed) }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { withBle { BandLink.start(band, BandLink.Purpose.SYNC) } },
            enabled = !phase.isBusy,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
        ) {
            Text(if (phase.isBusy) phase.label else "Sincronizar agora", fontWeight = FontWeight.SemiBold)
        }
        if (phase.isBusy) {
            OutlinedButton(onClick = { BandLink.stop() }) { Text("Parar") }
        }
    }

    if (phase.isBusy) {
        LinearProgressIndicator(
            progress = { progress?.let { (d, t) -> if (t > 0) d.toFloat() / t else 0f } ?: 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val status = when (val p = phase) {
        is BandLink.Phase.Done -> "Sincronizado."
        is BandLink.Phase.Partial, is BandLink.Phase.Failed -> p.label
        else -> null
    }
    status?.let { Text(it, color = if (phase is BandLink.Phase.Failed) c.low else c.textSecondary, fontSize = 14.sp) }
    progress?.let { (d, t) -> if (t > 0) Text("Arquivos: $d de $t", fontSize = 13.sp, color = c.textSecondary) }

    val facts = listOfNotNull(
        battery?.let { "Bateria $it%" + if (deviceState?.charging == true) " (carregando)" else "" },
        live?.let { "${it.steps} passos hoje" },
        live?.heartRate?.takeIf { it > 0 }?.let { "$it bpm" },
    )
    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = c.textPrimary)
    key(stored) { Text("${ActivityStore.count} arquivos do relógio guardados neste celular.", fontSize = 13.sp, color = c.textSecondary) }

    HorizontalDivider(color = c.textSecondary.copy(alpha = 0.2f))

    // Stay connected ("Fica conectado")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Fica conectado", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            val holdText = when {
                !stayConnected -> "Permite que ${band.shortName} encontre este celular. O Mi Fitness não conecta enquanto estiver ligado."
                holdState == BandLink.HoldState.CONNECTED -> "Conectado. ${band.shortName} pode fazer este celular tocar."
                else -> "Esperando ${band.shortName}…"
            }
            Text(holdText, fontSize = 13.sp, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = stayConnected,
            onCheckedChange = { on ->
                if (on) withBle { BandSettings.setStayConnected(true) }
                else BandSettings.setStayConnected(false)
            },
        )
    }
    if (stayConnected && !BandSettings.isIgnoringBatteryOptimizations(context)) {
        TextButton(onClick = { BandSettings.openBatterySettings(context) }) {
            Text("Tirar o Aravia da economia de bateria")
        }
    }

    // Watch actions: Find band, Health monitoring, Diagnostics
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                withBle {
                    val next = !vibrating
                    vibrating = next
                    BandSettings.findBand(band, next)
                }
            },
            enabled = !phase.isBusy,
            modifier = Modifier.weight(1f),
        ) { Text(if (vibrating) "Parar vibração" else "Vibrar relógio") }
        OutlinedButton(
            onClick = onOpenMonitoring,
            modifier = Modifier.weight(1f),
        ) { Text("Monitoramento") }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onOpenDiagnostics) { Text("Diagnóstico e registro") }
        val log by BandLink.log.collectAsState()
        if (log.isNotEmpty()) {
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, log.joinToString("\n"))
                }, "Compartilhar registro"))
            }) { Text("Compartilhar log") }
        }
    }
    Text("Feche o Mi Fitness antes: o relógio só conversa com um app por vez.", fontSize = 13.sp, color = c.textSecondary)
}
