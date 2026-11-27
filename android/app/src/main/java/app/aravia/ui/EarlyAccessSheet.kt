package app.aravia.ui

import android.content.Intent
import android.net.Uri
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
import app.aravia.account.Account
import app.aravia.account.SupporterAccess
import kotlinx.coroutines.launch

/**
 * Dedicated Early Access / Laboratório panel unlocked exclusively for accounts with an active
 * supporter entitlement (Filhote R$ 10+ or higher), persisted in Firestore `users/{uid}.supporter`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarlyAccessSheet(account: Account.State, onClose: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sup = account.supporter
    val adPass by app.aravia.account.RewardedPremium.state.collectAsState()
    val adActive = adPass.isAdPassActive()
    if (sup == null && !adActive) return
    val lab by SupporterAccess.lab.collectAsState()
    var syncing by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.canvas) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (sup != null) "Acesso Antecipado" else "Aravia Premium (24h)",
                    fontFamily = Barlow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    color = c.textPrimary,
                )
                Surface(color = c.good.copy(alpha = 0.16f), shape = RoundedCornerShape(99.dp)) {
                    Text(
                        when {
                            sup?.lifetime == true -> "Vitalício"
                            sup != null -> "Ativo"
                            else -> adPass.remainingLabel()
                        },
                        color = c.good,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            if (sup != null) {
                Column(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(sup.tierName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                    Text(
                        "Salvo na conta @${account.username ?: "—"} · funciona em qualquer celular em que você fizer login.",
                        color = c.textSecondary,
                        fontSize = 13.sp,
                    )
                    val validity = if (sup.lifetime) {
                        "Acesso vitalício garantido na sua conta."
                    } else {
                        sup.validUntil?.take(10)?.let { "Válido até $it (${sup.premiumMonths} ${if (sup.premiumMonths == 1) "mês" else "meses"})." }
                            ?: "Benefício vinculado permanentemente à sua conta."
                    }
                    Text(validity, color = c.textSecondary, fontSize = 13.sp)
                    sup.codeMasked?.let {
                        Text("Código vinculado: $it", color = c.textSecondary, fontSize = 12.sp)
                    }
                    if (sup.founderBadge) {
                        Surface(color = c.action.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                "Selo de Beta-Tester ativo · seu @usuario entra nos créditos do Aravia",
                                color = c.action,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Passe Premium de 24 horas", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                    Text(
                        "Liberado gratuitamente assistindo 2 anúncios opcionais · ${adPass.remainingLabel()}. Sem compras no app.",
                        color = c.textSecondary,
                        fontSize = 13.sp,
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Ferramentas Premium (Laboratório)", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)

                LabSwitchRow(
                    title = "Stories sem marca d'água",
                    subtitle = "Remove o rodapé \"Feito no Aravia\" nos overlays transparentes e fotos dos Stories.",
                    checked = lab.hideStoryWatermark,
                    onCheckedChange = { SupporterAccess.setLab(hideStoryWatermark = it) },
                )
                HorizontalDivider(color = c.canvas)
                LabSwitchRow(
                    title = "Canal de recursos em prévia",
                    subtitle = "Ativa automaticamente novas ferramentas experimentais assim que chegam em cada atualização.",
                    checked = lab.earlyChannel,
                    onCheckedChange = { SupporterAccess.setLab(earlyChannel = it) },
                )
                HorizontalDivider(color = c.canvas)
                LabSwitchRow(
                    title = "Métricas experimentais de treino",
                    subtitle = "Habilita prévias das próximas métricas avançadas de corrida e carga de treino.",
                    checked = lab.experimentalMetrics,
                    onCheckedChange = { SupporterAccess.setLab(experimentalMetrics = it) },
                )
            }

            sup?.earlyUrl?.let { url ->
                Button(
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
                ) {
                    Text("Abrir canal de builds antecipadas", fontWeight = FontWeight.SemiBold)
                }
            }

            if (sup != null) {
                OutlinedButton(
                    onClick = {
                        if (syncing) return@OutlinedButton
                        syncing = true
                        statusMsg = null
                        scope.launch {
                            runCatching { Account.syncSupporterNow() }
                            .onSuccess { updated ->
                                statusMsg = if (updated != null) "Conta sincronizada: ${updated.tierName}." else "Sua faixa atual continua ativa."
                            }
                            .onFailure { statusMsg = it.message ?: "Não deu para sincronizar agora." }
                        syncing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !syncing,
            ) {
                Text(if (syncing) "Sincronizando…" else "Verificar upgrade de faixa")
            }
            }

            statusMsg?.let { Text(it, color = c.textSecondary, fontSize = 13.sp) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = Aravia.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.textPrimary)
            Text(subtitle, fontSize = 12.sp, color = c.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Lets a signed-in user redeem their unique supporter code (`ARV1`/`ARV3`/`ARV12`/`ARVV-...`) or
 * auto-sync an approved support made with their `@usuario`, saving Early Access to their account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedeemSupporterSheet(account: Account.State, onUnlocked: () -> Unit, onClose: () -> Unit) {
    val c = Aravia.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.canvas) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ativar Acesso Antecipado", fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.textPrimary)
            Text(
                "Cole o código gerado após o seu apoio em aravia.app (faixas Filhote, Jovem, Adulta ou Arara-real). Ele fica salvo na sua conta @${account.username ?: "—"} para sempre.",
                color = c.textSecondary,
                fontSize = 14.sp,
            )

            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(32); error = null; info = null },
                label = { Text("Código de apoiador") },
                placeholder = { Text("ARV3-XXXX-XXXX-XXXX-XXXX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val trimmed = code.trim()
                    if (trimmed.isEmpty()) {
                        error = "Cole o seu código de apoiador acima."
                        return@Button
                    }
                    busy = true; error = null; info = null
                    scope.launch {
                        runCatching { Account.redeemSupporterCode(trimmed) }
                            .onSuccess {
                                busy = false
                                onUnlocked()
                            }
                            .onFailure {
                                busy = false
                                error = it.message ?: "Não foi possível ativar esse código."
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
            ) {
                Text(if (busy) "Validando…" else "Ativar na minha conta", fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = {
                    busy = true; error = null; info = null
                    scope.launch {
                        runCatching { Account.syncSupporterNow() }
                            .onSuccess { ent ->
                                busy = false
                                if (ent != null && ent.earlyAccess) {
                                    onUnlocked()
                                } else {
                                    info = "Nenhum apoio aprovado encontrado automaticamente para @${account.username ?: "—"}. Se você não digitou seu @usuario na hora do pagamento, cole o código acima."
                                }
                            }
                            .onFailure {
                                busy = false
                                error = it.message ?: "Não deu para verificar agora."
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text("Já apoiei com @${account.username ?: "minha conta"} · verificar")
            }

            if (BuildConfig.SHOW_BETA_SUPPORT) {
                TextButton(
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aravia.app/#apoiar"))) }
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Ainda não tem código? Ver faixas em aravia.app")
                }
            }

            error?.let { Text(it, color = c.low, fontSize = 13.sp) }
            info?.let { Text(it, color = c.textSecondary, fontSize = 13.sp) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
