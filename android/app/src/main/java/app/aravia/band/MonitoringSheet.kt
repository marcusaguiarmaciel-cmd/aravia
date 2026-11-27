package app.aravia.band

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import app.aravia.Demo
import app.aravia.protocol.MiMonitoring
import app.aravia.ui.Aravia
import app.aravia.ui.Barlow

/**
 * Health monitoring settings (docs/CAPABILITIES.md §9.1-9.3): reads from the watch on open;
 * every change writes back and re-reads, so the screen reflects the watch's own answer.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MonitoringSheet(band: BandVault.Band, onClose: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    val phase by BandLink.phase.collectAsState()
    val mon by BandSettings.monitoring.collectAsState()
    val busy = phase.isBusy
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) BandSettings.readMonitoring(band)
    }

    LaunchedEffect(band) {
        if (!Demo.active && !busy && BandPermissions.granted(context)) BandSettings.readMonitoring(band)
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.canvas) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Monitoramento de saúde", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = c.textPrimary)
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.action)
                    Text(phase.label.ifEmpty { "Lendo do relógio…" }, color = c.textSecondary, fontSize = 14.sp)
                }
            }

            val hrBytes = mon[MiMonitoring.Setting.HEART_RATE]
            val spo2Bytes = mon[MiMonitoring.Setting.SPO2]
            val stressBytes = mon[MiMonitoring.Setting.STRESS]

            if (hrBytes == null && spo2Bytes == null && stressBytes == null) {
                Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (busy) "Lendo os ajustes diretamente do relógio…"
                        else "Não foi possível ler os ajustes do relógio. Feche o Mi Fitness, deixe o relógio perto e tente de novo.",
                        color = c.textSecondary, fontSize = 14.sp,
                    )
                    if (!busy) {
                        Button(onClick = {
                            if (BandPermissions.granted(context)) BandSettings.readMonitoring(band)
                            else ask.launch(BandPermissions.needed)
                        }, colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction)) {
                            Text("Ler do relógio")
                        }
                    }
                }
            }

            hrBytes?.let { raw ->
                val mode = MiMonitoring.heartRateMode(raw)
                val sleep = MiMonitoring.sleepHeartRate(raw)
                if (mode != null) {
                    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Batimento cardíaco", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
                        val intervals = buildList {
                            addAll(listOf(1, 10, 30))
                            if (mode is MiMonitoring.HeartRateMode.Every && mode.minutes !in this) add(mode.minutes)
                        }.sorted()
                        val options = listOf(
                            MiMonitoring.HeartRateMode.Off to "Desligado",
                            MiMonitoring.HeartRateMode.Smart to "Inteligente",
                        ) + intervals.map { MiMonitoring.HeartRateMode.Every(it) to if (it == 1) "1 min" else "$it min" }

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { (opt, label) ->
                                FilterChip(
                                    selected = mode == opt,
                                    enabled = !busy,
                                    onClick = { BandSettings.writeMonitoring(band, MiMonitoring.Setting.HEART_RATE, MiMonitoring.heartRate(raw, mode = opt)) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        if (sleep != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Batimentos durante o sono", color = c.textPrimary, fontWeight = FontWeight.Medium)
                                    Text("Medição avançada durante a noite.", color = c.textSecondary, fontSize = 13.sp)
                                }
                                Switch(
                                    checked = sleep,
                                    enabled = !busy,
                                    onCheckedChange = { on ->
                                        BandSettings.writeMonitoring(band, MiMonitoring.Setting.HEART_RATE, MiMonitoring.heartRate(raw, sleep = on))
                                    },
                                )
                            }
                        }
                        Text("A cada 1 minuto dá mais precisão para esforço e FC de repouso, mas consome mais bateria.",
                            fontSize = 12.sp, color = c.textSecondary)
                    }
                }
            }

            spo2Bytes?.let { raw ->
                val mode = MiMonitoring.spo2Mode(raw)
                if (mode != null) {
                    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Oxigênio no sangue (SpO2)", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
                        val options = buildList {
                            add(0 to "Desligado")
                            if (mode == 1) add(1 to "Durante o sono")
                            add(2 to "O dia todo")
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { (opt, label) ->
                                FilterChip(
                                    selected = mode == opt,
                                    enabled = !busy,
                                    onClick = { BandSettings.writeMonitoring(band, MiMonitoring.Setting.SPO2, MiMonitoring.spo2(raw, opt)) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }

            stressBytes?.let { raw ->
                val allDay = MiMonitoring.stressAllDay(raw)
                if (allDay != null) {
                    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Estresse o dia todo", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.textPrimary)
                                Text("Mede o estresse continuamente pelo relógio.", color = c.textSecondary, fontSize = 13.sp)
                            }
                            Switch(
                                checked = allDay,
                                enabled = !busy,
                                onCheckedChange = { on ->
                                    BandSettings.writeMonitoring(band, MiMonitoring.Setting.STRESS, MiMonitoring.stress(raw, on))
                                },
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !busy, onClick = {
                    if (BandPermissions.granted(context)) BandSettings.readMonitoring(band) else ask.launch(BandPermissions.needed)
                }) { Text("Ler de novo") }
                TextButton(onClick = onClose) { Text("Fechar") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
