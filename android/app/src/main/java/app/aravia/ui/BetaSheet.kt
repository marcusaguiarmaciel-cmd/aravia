package app.aravia.ui

import android.content.Intent
import android.os.Build
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
import app.aravia.BuildConfig
import app.aravia.band.BandLink
import app.aravia.band.BandVault

/**
 * Beta 1.0 information, feedback sharing (with optional MAC-redacted diagnostic log), and
 * community launch funding notice (controlled by BuildConfig.SHOW_BETA_SUPPORT so store builds
 * automatically hide external funding copy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BetaSheet(band: BandVault.Band?, onClose: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    var includeLog by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.canvas) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Aravia Beta", fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = c.textPrimary)
                Surface(color = c.action.copy(alpha = 0.16f), shape = RoundedCornerShape(99.dp)) {
                    Text(
                        BuildConfig.VERSION_NAME,
                        color = c.action,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                "Obrigado por testar o Aravia! Durante o beta aberto, todos os recursos estão liberados gratuitamente e seus dados de saúde ficam 100% no seu aparelho.",
                color = c.textSecondary,
                fontSize = 14.sp,
            )

            Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Enviar feedback ou relatar erro", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("O que funcionou bem ou deu problema?") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Anexar resumo técnico do relógio", fontWeight = FontWeight.Medium, color = c.textPrimary, fontSize = 14.sp)
                        Text("Endereços Bluetooth são mascarados antes de compartilhar.", color = c.textSecondary, fontSize = 12.sp)
                    }
                    Switch(checked = includeLog, onCheckedChange = { includeLog = it })
                }
                Button(
                    onClick = {
                        val report = buildFeedbackReport(note, band, includeLog)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(app.aravia.legal.LegalText.CONTACT_EMAIL))
                            putExtra(Intent.EXTRA_SUBJECT, "Feedback Aravia ${BuildConfig.VERSION_NAME}")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }, "Enviar feedback"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                ) {
                    Text("Compartilhar feedback", fontWeight = FontWeight.SemiBold)
                }
            }

            if (BuildConfig.SHOW_BETA_SUPPORT) {
                Column(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Apoie o lançamento nas lojas", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                    Text(
                        "O Aravia é um projeto independente brasileiro. Estamos juntando apoio da comunidade para pagar a publicação na Google Play (US$ 25) e na Apple App Store (US$ 99/ano).",
                        color = c.textSecondary,
                        fontSize = 13.sp,
                    )
                    Text(
                        "Apoiadores Beta-Testers recebem código de acesso Premium quando o app chegar às lojas — e os dados, exportações, Strava e Google Drive continuam grátis para sempre.",
                        color = c.textSecondary,
                        fontSize = 13.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aravia.app/#apoiar")))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ver faixas de apoio em aravia.app")
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Informações deste aparelho", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.textPrimary)
                Text("Versão: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", fontSize = 13.sp, color = c.textSecondary)
                Text("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", fontSize = 13.sp, color = c.textSecondary)
                band?.let { b ->
                    val model = listOfNotNull(b.hardwareModel ?: b.model, b.firmware?.let { "fw $it" }).joinToString(" · ")
                    if (model.isNotBlank()) Text("Relógio: $model", fontSize = 13.sp, color = c.textSecondary)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private val macRegex = Regex("""([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}""")

private fun buildFeedbackReport(note: String, band: BandVault.Band?, includeLog: Boolean): String = buildString {
    appendLine("Feedback Aravia ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
    appendLine("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
    band?.let { b ->
        val model = listOfNotNull(b.hardwareModel ?: b.model, b.firmware?.let { "fw $it" }).joinToString(" · ")
        if (model.isNotBlank()) appendLine("Relógio: $model")
    }
    appendLine()
    appendLine(note.trim().ifEmpty { "(Escreva aqui o que aconteceu ou sua sugestão)" })
    if (includeLog) {
        val recent = BandLink.log.value.takeLast(40)
        if (recent.isNotEmpty()) {
            appendLine()
            appendLine("--- Registro técnico (últimas ${recent.size} linhas) ---")
            recent.forEach { line ->
                appendLine(macRegex.replace(line, "XX:XX:XX:XX:XX:XX"))
            }
        }
    }
}
