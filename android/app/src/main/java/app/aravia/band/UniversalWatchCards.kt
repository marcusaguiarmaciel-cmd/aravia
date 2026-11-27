package app.aravia.band

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import app.aravia.health.HealthConnectBridge
import app.aravia.health.HealthConnectStore
import app.aravia.ui.Aravia
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Universal watch connection card via Android Health Connect (Galaxy Watch, Garmin, Amazfit/Zepp,
 * Xiaomi/Mi Fitness, Polar, Fitbit, Pixel Watch, Wear OS, Huawei, Oura, etc.).
 * 100% on-device read-only; no proprietary pairing keys or watch resets needed.
 */
@Composable
fun HealthConnectCard(
    compact: Boolean = false,
    onConnected: () -> Unit = {},
) {
    val c = Aravia.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by HealthConnectBridge.state.collectAsState()
    val sources by HealthConnectStore.detectedSources.collectAsState()
    val lastSyncSec by HealthConnectStore.lastSyncSec.collectAsState()
    val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")) }

    val requestHcPerms = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        scope.launch {
            HealthConnectBridge.refreshStatus(context)
            if (granted.isNotEmpty()) {
                HealthConnectBridge.sync(context)
                onConnected()
            }
        }
    }

    LaunchedEffect(Unit) {
        HealthConnectBridge.refreshStatus(context)
    }

    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Qualquer relógio (Health Connect)",
                fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 17.sp else 19.sp,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (state.connected) {
                Surface(color = c.good.copy(alpha = 0.16f), shape = RoundedCornerShape(99.dp)) {
                    Text(
                        "Conectado",
                        color = c.good,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }

        Text(
            "Funciona com Galaxy Watch, Garmin, Amazfit, Xiaomi/Redmi (mesmo já sincronizado com o Mi Fitness), Polar, Fitbit, Pixel Watch e Wear OS — sem chave e sem redefinir o relógio.",
            color = c.textSecondary,
            fontSize = 13.sp,
        )

        if (sources.isNotEmpty()) {
            Text(
                "Fontes detectadas: ${sources.joinToString(" · ")}",
                color = c.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        lastSyncSec?.let { ts ->
            Text(
                "Última leitura: ${dateFmt.format(Date(ts * 1000L))}" +
                    (state.lastMessage?.let { " · $it" } ?: ""),
                color = c.textSecondary,
                fontSize = 12.sp,
            )
        }

        if (state.syncing) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.action)
        }

        state.error?.let { err ->
            Text(err, color = c.low, fontSize = 13.sp)
        }

        when (state.availability) {
            HealthConnectBridge.Availability.AVAILABLE -> {
                if (!state.connected) {
                    Button(
                        onClick = { requestHcPerms.launch(HealthConnectBridge.READ_PERMISSIONS) },
                        enabled = !state.syncing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                    ) {
                        Text("Conectar via Health Connect", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { HealthConnectBridge.sync(context) } },
                            enabled = !state.syncing,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                        ) {
                            Text(if (state.syncing) "Sincronizando…" else "Sincronizar agora", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { HealthConnectBridge.openHealthConnect(context) },
                            enabled = !state.syncing,
                        ) {
                            Text("Permissões")
                        }
                    }
                }
            }
            HealthConnectBridge.Availability.NEEDS_INSTALL -> {
                Button(
                    onClick = { HealthConnectBridge.openHealthConnect(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                ) {
                    Text("Instalar Health Connect (Google Play)", fontWeight = FontWeight.SemiBold)
                }
            }
            HealthConnectBridge.Availability.UNAVAILABLE -> {
                Text(
                    "O Health Connect não está disponível nesta versão do Android. Você ainda pode usar cintas cardíacas Bluetooth, conexão direta Xiaomi/Redmi ou importar rotas na aba Treinar.",
                    color = c.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Universal Bluetooth SIG Heart Rate sensor card (0x180D / 0x2A37):
 * Connects in 1 tap to any BLE chest strap (Polar H10/H9, Garmin HRM, Coospo, Magene, Wahoo) or
 * smartwatch broadcasting HR over BLE — feeds WorkoutController live & supports 60s Spot HRV.
 */
@Composable
fun StandardHrCard() {
    val c = Aravia.colors
    val context = LocalContext.current
    val state by StandardHrLink.state.collectAsState()
    val devices by StandardHrLink.devices.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            expanded = true
            StandardHrLink.startScan()
        }
    }

    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Cinta cardíaca ou sensor Bluetooth",
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (state.status == StandardHrLink.Status.CONNECTED) {
                Surface(color = c.good.copy(alpha = 0.16f), shape = RoundedCornerShape(99.dp)) {
                    Text(
                        state.bpm?.let { "$it bpm" } ?: "Ao vivo",
                        color = c.good,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Text(
            "Polar, Garmin HRM, Coospo, Magene, Wahoo ou qualquer relógio que transmita batimentos por Bluetooth ao vivo na academia ou corrida.",
            color = c.textSecondary,
            fontSize = 13.sp,
        )

        if (state.status == StandardHrLink.Status.CONNECTED) {
            val meta = listOfNotNull(
                state.deviceName,
                state.battery?.let { "bateria $it%" },
                if (state.hasRr) "R-R (HRV) ativo" else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)

            if (state.spotHrvRunning) {
                Text(
                    "Medindo HRV em repouso… ${state.spotHrvElapsedSec}s / 60s (fique parado e respirando normalmente)",
                    color = c.action,
                    fontSize = 13.sp,
                )
                LinearProgressIndicator(
                    progress = { (state.spotHrvElapsedSec / 60f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = c.action,
                )
            } else {
                state.spotHrvRmssdMs?.let { ms ->
                    Text(
                        "HRV registrado hoje: ${ms.toInt()} ms (RMSSD) — já salvo para sua Recuperação.",
                        color = c.good,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.hasRr) {
                    Button(
                        onClick = { if (state.spotHrvRunning) StandardHrLink.cancelSpotHrv() else StandardHrLink.startSpotHrv() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                    ) {
                        Text(if (state.spotHrvRunning) "Parar HRV" else "Medir HRV (60 s)", fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedButton(
                    onClick = { StandardHrLink.disconnect() },
                    modifier = if (state.hasRr) Modifier else Modifier.fillMaxWidth(),
                ) { Text("Desconectar") }
            }
        } else {
            OutlinedButton(
                onClick = {
                    if (BandPermissions.granted(context)) {
                        expanded = true
                        if (state.status == StandardHrLink.Status.SCANNING) StandardHrLink.stopScan()
                        else StandardHrLink.startScan()
                    } else {
                        askPerm.launch(BandPermissions.needed)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.status == StandardHrLink.Status.SCANNING) "Parar busca de sensores" else "Procurar cinta ou sensor cardíaco")
            }

            if (state.status == StandardHrLink.Status.SCANNING || state.status == StandardHrLink.Status.CONNECTING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.action)
                    Text(state.status.label, color = c.textSecondary, fontSize = 13.sp)
                }
            }
            state.error?.let { Text(it, color = c.low, fontSize = 13.sp) }

            if (expanded && devices.isNotEmpty()) {
                devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().background(c.canvas, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, color = c.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            val sub = listOfNotNull(
                                if (d.connected) "Conectado" else null,
                                d.rssi?.let { "$it dBm" },
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, color = c.textSecondary, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { StandardHrLink.connect(d.address, d.name) },
                            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                        ) { Text("Usar") }
                    }
                }
            }
        }
    }
}
