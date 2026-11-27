package app.aravia.band

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aravia.protocol.KeyTransfer
import app.aravia.ui.Aravia
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Trazer chave de outro celular (QR)": Google's code scanner reads the Aravia transfer QR
 * (no camera permission: the scanner runs in Play services), then the six digits shown on the
 * other device open it (docs/KEY-TRANSFER.md).
 */
@Composable
fun KeyImportButton(
    label: String = "Trazer chave de outro celular (QR)",
    modifier: Modifier = Modifier.fillMaxWidth(),
    done: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Aravia.colors
    var scanned by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column {
        OutlinedButton(modifier = modifier, onClick = {
            error = null
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { b ->
                    val text = b.rawValue ?: ""
                    if (KeyTransfer.isAravia(text)) { scanned = text; code = "" }
                    else error = "Esse não é o QR code do Aravia. No outro aparelho, abra Relógio › Sobre › Transferir chave."
                }
                .addOnFailureListener { error = "Não deu para abrir a câmera: ${it.localizedMessage}" }
        }) { Text(label) }
        error?.let { Text(it, color = c.low) }
    }

    scanned?.let { qr ->
        AlertDialog(onDismissRequest = { if (!opening) scanned = null },
            title = { Text("Código de transferência") },
            text = {
                Column {
                    Text("Digite os 6 números que aparecem embaixo do QR code no outro aparelho.")
                    OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (opening) CircularProgressIndicator()
                    error?.let { Text(it, color = c.low) }
                }
            },
            confirmButton = {
                TextButton(enabled = code.length == 6 && !opening, onClick = {
                    opening = true; error = null
                    scope.launch {
                        // 150 000 rounds: off the main thread.
                        val r = withContext(Dispatchers.Default) { KeyTransfer.open(qr, code) }
                        opening = false
                        when (r) {
                            is KeyTransfer.Result.Opened -> {
                                val band = BandVault.parse(r.json)
                                if (band == null) error = "O QR code não trouxe uma chave válida."
                                else { BandVault.save(band); scanned = null; done() }
                            }
                            KeyTransfer.Result.WrongCode -> error = "Código errado. Confira os 6 números no outro aparelho."
                            KeyTransfer.Result.NotAravia -> { error = "Esse QR code não é do Aravia."; scanned = null }
                        }
                    }
                }) { Text("Guardar a chave") }
            },
            dismissButton = { TextButton(enabled = !opening, onClick = { scanned = null }) { Text("Cancelar") } })
    }
}

/**
 * Manual key entry for users who already have their watch's 16-byte (32-hex) Xiaomi auth key.
 */
@Composable
fun ManualKeyButton(
    label: String = "Inserir chave manualmente",
    initialName: String = "Redmi Watch",
    initialMac: String = "",
    modifier: Modifier = Modifier.fillMaxWidth(),
    done: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Text(label)
    }

    if (open) {
        ManualKeyDialog(
            initialName = initialName,
            initialMac = initialMac,
            onDismiss = { open = false },
            onSaved = {
                open = false
                done()
            },
        )
    }
}

@Composable
fun ManualKeyDialog(
    initialName: String = "Redmi Watch",
    initialMac: String = "",
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val c = Aravia.colors
    var name by remember(initialName) { mutableStateOf(initialName.ifBlank { "Relógio Xiaomi" }) }
    var mac by remember(initialMac) { mutableStateOf(initialMac.uppercase()) }
    var keyHex by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chave do relógio") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(
                    "Cole a chave de autenticação de 32 caracteres (hex) do relógio Xiaomi/Redmi. Ela fica guardada só neste celular.",
                    color = c.textSecondary,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Nome do relógio") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it.uppercase().filter { ch -> ch in "0123456789ABCDEF:" }.take(17) },
                    label = { Text("Endereço Bluetooth / MAC (opcional)") },
                    placeholder = { Text("AA:BB:CC:DD:5E:6F") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keyHex,
                    onValueChange = { keyHex = it.take(64); error = null },
                    label = { Text("Chave de autenticação (32 hex)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = c.low) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleanKey = keyHex.trim().removePrefix("0x").removePrefix("0X")
                    .replace(":", "").replace("-", "").replace(" ", "").lowercase()
                if (cleanKey.length != 32 || !cleanKey.all { it in "0123456789abcdef" }) {
                    error = "A chave precisa ter exatamente 32 caracteres hexadecimais (0–9 e A–F)."
                    return@TextButton
                }
                val cleanMac = mac.trim().takeIf { it.isNotEmpty() }
                if (cleanMac != null && !Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(cleanMac)) {
                    error = "O endereço MAC deve seguir o formato AA:BB:CC:DD:EE:FF (ou fique em branco)."
                    return@TextButton
                }
                BandVault.save(
                    BandVault.Band(
                        name = name.trim().ifEmpty { "Relógio Xiaomi" },
                        mac = cleanMac,
                        model = "",
                        keys = listOf(cleanKey),
                    )
                )
                onSaved()
            }) { Text("Guardar chave") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
