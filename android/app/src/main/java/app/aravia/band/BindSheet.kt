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
import app.aravia.account.Account
import app.aravia.protocol.MiBind
import app.aravia.ui.Aravia
import app.aravia.ui.Barlow

/**
 * Standalone Bluetooth watch discovery and local pairing (docs/BIND.md):
 * - Immediately lists watches already connected to Android (GATT) or paired in system Bluetooth,
 *   plus runs a low-latency BLE scan for nearby Xiaomi/Redmi watches advertising FE95.
 * - Runs MiBind.Machine (P-256 ECDH + HKDF "miwear-bind") independently of Mi Fitness.
 * - When a watch is still locked to Mi Fitness (AlreadyBound) or requires a pre-existing auth key
 *   (ServerBindRequired), gives clear independent recovery steps and a 1-tap manual key dialog
 *   with the detected watch's name and MAC pre-filled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindSheet(
    autoConnect: BandLink.ScannedBand? = null,
    onPaired: () -> Unit = {},
    onClose: () -> Unit,
) {
    val c = Aravia.colors
    val context = LocalContext.current
    val phase by BandLink.phase.collectAsState()
    val bindStatus by BandLink.bindStatus.collectAsState()
    val lastFailure by BandLink.lastBindFailure.collectAsState()
    val lastTarget by BandLink.lastBindTarget.collectAsState()
    val nearby by BandLink.nearby.collectAsState()
    val discovering by BandLink.discovering.collectAsState()
    val account by Account.state.collectAsState()
    var dynamicCode by remember { mutableStateOf("") }
    var showCodeField by remember { mutableStateOf(false) }
    var showOtherDevices by remember { mutableStateOf(false) }
    var manualTarget by remember { mutableStateOf<BandLink.ScannedBand?>(null) }
    var hadBindInFlight by remember { mutableStateOf(false) }
    var btGranted by remember { mutableStateOf(BandPermissions.granted(context)) }
    val userId = account.uid ?: "aravia-user"

    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        btGranted = res.values.all { it }
        if (btGranted) {
            if (autoConnect != null) {
                hadBindInFlight = true
                BandLink.startBind(autoConnect.address, autoConnect.name, userId, dynamicCode.trim())
            } else {
                BandLink.startDiscovery()
            }
        }
    }

    LaunchedEffect(Unit) {
        BandLink.clearBindState()
        if (btGranted) {
            if (autoConnect != null) {
                hadBindInFlight = true
                BandLink.startBind(autoConnect.address, autoConnect.name, userId, dynamicCode.trim())
            } else {
                BandLink.startDiscovery()
            }
        }
    }

    LaunchedEffect(phase) {
        if (phase.isBusy && BandLink.purpose is BandLink.Purpose.Bind) {
            hadBindInFlight = true
        } else if (hadBindInFlight && phase is BandLink.Phase.Done && BandVault.band.value != null) {
            hadBindInFlight = false
            onPaired()
            onClose()
        }
    }

    DisposableEffect(Unit) {
        onDispose { BandLink.stopDiscovery() }
    }

    val watches = remember(nearby) { nearby.filter { it.likelyWatch || it.hasMiBeacon } }
    val others = remember(nearby) { nearby.filterNot { it.likelyWatch || it.hasMiBeacon } }
    val btOn = remember(discovering, nearby, phase) { BandLink.bluetoothEnabled() }

    ModalBottomSheet(onDismissRequest = { if (!phase.isBusy) onClose() }, containerColor = c.canvas) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Conectar relógio", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = c.textPrimary)
            Text(
                "O Aravia detecta automaticamente relógios já conectados ou pareados no Bluetooth deste celular, além de relógios próximos — sem depender do Mi Fitness.",
                color = c.textSecondary,
                fontSize = 14.sp,
            )

            if (!btGranted) {
                Button(
                    onClick = { askPerm.launch(BandPermissions.needed) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                ) {
                    Text("Permitir Bluetooth e buscar relógio", fontWeight = FontWeight.SemiBold)
                }
            } else if (!btOn) {
                Text(
                    "O Bluetooth do celular está desligado. Ligue o Bluetooth nos ajustes rápidos e toque em Buscar novamente.",
                    color = c.low,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Button(
                    onClick = { BandLink.startDiscovery() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                ) {
                    Text("Buscar novamente", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (discovering) BandLink.stopDiscovery() else BandLink.startDiscovery()
                        },
                        enabled = !phase.isBusy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                    ) {
                        Text(if (discovering) "Parar busca" else "Atualizar lista", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { showCodeField = !showCodeField },
                        enabled = !phase.isBusy,
                    ) {
                        Text(if (showCodeField) "Ocultar código" else "Código")
                    }
                }
            }

            if (showCodeField) {
                OutlinedTextField(
                    value = dynamicCode,
                    onValueChange = { dynamicCode = it.take(16) },
                    label = { Text("Código na tela do relógio (opcional)") },
                    singleLine = true,
                    enabled = !phase.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (discovering || phase.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.action)
                    Text(
                        when {
                            phase.isBusy -> bindStatus ?: phase.label
                            else -> "Procurando relógios conectados e próximos…"
                        },
                        color = c.textSecondary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                bindStatus?.let { st ->
                    val failed = phase is BandLink.Phase.Failed
                    Text(st, color = if (failed) c.low else c.good, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Guided independent recovery when the watch is still bound to Mi Fitness or needs an existing key
            if (!phase.isBusy && (lastFailure is MiBind.Failure.AlreadyBound || lastFailure is MiBind.Failure.ServerBindRequired)) {
                val target = lastTarget
                Column(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (lastFailure is MiBind.Failure.AlreadyBound) {
                        Text(
                            "Como desvincular do Mi Fitness",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = c.textPrimary,
                        )
                        Text(
                            "Por segurança do próprio relógio, a chave atual pertence ao Mi Fitness e o Android não permite que outro app leia dados privados dele.\n\n" +
                                "• Para usar o Aravia 100% independente: no relógio, abra Ajustes › Sistema › Desconectar ou Redefinir para liberar um novo pareamento direto, depois toque em Tentar novamente.\n" +
                                "• Para manter o vínculo atual sem redefinir: informe a chave de 32 caracteres do relógio abaixo.",
                            color = c.textSecondary,
                            fontSize = 13.sp,
                        )
                    } else {
                        Text(
                            "Chave exigida pelo firmware",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = c.textPrimary,
                        )
                        Text(
                            "Este firmware da Xiaomi exige uma chave de autenticação prévia no primeiro pareamento. Você pode colar a chave de 32 caracteres diretamente para este relógio (o nome e o endereço Bluetooth já vêm preenchidos).",
                            color = c.textSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (target != null && lastFailure is MiBind.Failure.AlreadyBound) {
                            OutlinedButton(
                                onClick = {
                                    hadBindInFlight = true
                                    BandLink.startBind(target.address, target.name, userId, dynamicCode.trim())
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Tentar de novo") }
                        }
                        Button(
                            onClick = {
                                manualTarget = target ?: BandLink.ScannedBand("Redmi Watch", "", null, false)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                        ) {
                            Text("Usar chave (32 hex)", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (watches.isEmpty() && others.isEmpty() && !discovering && btGranted && btOn) {
                Text(
                    "Nenhum relógio encontrado ainda. Deixe o relógio ligado e próximo do celular (ou conectado no Bluetooth do Android) e toque em \"Atualizar lista\".",
                    color = c.textSecondary,
                    fontSize = 13.sp,
                )
            } else {
                if (watches.isNotEmpty()) {
                    Text(
                        "Relógios encontrados (${watches.size})",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = c.textPrimary,
                    )
                    watches.forEach { dev ->
                        DeviceRow(
                            dev = dev,
                            busy = phase.isBusy,
                            onConnect = {
                                hadBindInFlight = true
                                BandLink.startBind(dev.address, dev.name, userId, dynamicCode.trim())
                            },
                            onManualKey = { manualTarget = dev },
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    TextButton(onClick = { showOtherDevices = !showOtherDevices }) {
                        Text(
                            if (showOtherDevices) "Ocultar outros dispositivos Bluetooth (${others.size})"
                            else "Mostrar outros dispositivos Bluetooth (${others.size})"
                        )
                    }
                    if (showOtherDevices) {
                        others.forEach { dev ->
                            DeviceRow(
                                dev = dev,
                                busy = phase.isBusy,
                                onConnect = {
                                    hadBindInFlight = true
                                    BandLink.startBind(dev.address, dev.name, userId, dynamicCode.trim())
                                },
                                onManualKey = { manualTarget = dev },
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (phase.isBusy) {
                    TextButton(onClick = { BandLink.stop() }) { Text("Cancelar", color = c.low) }
                } else {
                    TextButton(onClick = onClose) { Text("Fechar") }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    manualTarget?.let { dev ->
        ManualKeyDialog(
            initialName = dev.name,
            initialMac = dev.address,
            onDismiss = { manualTarget = null },
            onSaved = {
                manualTarget = null
                onPaired()
                onClose()
            },
        )
    }
}

@Composable
private fun DeviceRow(
    dev: BandLink.ScannedBand,
    busy: Boolean,
    onConnect: () -> Unit,
    onManualKey: () -> Unit,
) {
    val c = Aravia.colors
    val badgeColor = when (dev.source) {
        BandLink.BandSource.CONNECTED -> c.good
        BandLink.BandSource.BONDED -> c.action
        BandLink.BandSource.NEARBY -> c.textSecondary
    }
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(dev.name, color = c.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(color = badgeColor.copy(alpha = 0.14f), shape = RoundedCornerShape(99.dp)) {
                        Text(
                            dev.source.label,
                            color = badgeColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    val detail = listOfNotNull(
                        if (dev.hasMiBeacon) "Xiaomi FE95" else null,
                        dev.rssi?.let { "$it dBm" },
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(detail, color = c.textSecondary, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !busy,
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
            ) { Text("Conectar") }
        }
        if (dev.source == BandLink.BandSource.CONNECTED || dev.source == BandLink.BandSource.BONDED) {
            TextButton(
                onClick = onManualKey,
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text("Já tem a chave de 32 caracteres deste relógio?", fontSize = 12.sp)
            }
        }
    }
}
