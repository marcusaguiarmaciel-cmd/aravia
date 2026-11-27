package app.aravia.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import app.aravia.BuildConfig
import app.aravia.account.Account
import app.aravia.account.RewardedPremium
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Opt-in 24h Premium Pass card ("Assista 2 anúncios -> 24h Premium", zero in-app purchases).
 * Shown on the Relógio tab (and compactly inside StoryHub when watermark is visible).
 */
@Composable
fun PremiumPassCard(
    account: Account.State,
    onOpenTools: () -> Unit,
    compact: Boolean = false,
) {
    val c = Aravia.colors
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val adState by RewardedPremium.state.collectAsState()
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    // Keep countdown fresh every minute while the 24h pass is active.
    LaunchedEffect(adState.passExpiresAtMillis) {
        RewardedPremium.refreshExpiry()
        while (adState.isAdPassActive(nowMillis)) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
            RewardedPremium.refreshExpiry()
        }
    }

    // Paid supporters already have permanent / multi-month Premium with zero ads.
    if (account.hasEarlyAccess) return

    val active = adState.isAdPassActive(nowMillis)
    val watched = adState.adsWatched
    val nextIndex = (watched + 1).coerceAtMost(RewardedPremium.REQUIRED_ADS)

    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(if (compact) 16.dp else 20.dp))
            .padding(if (compact) 14.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (active) "Aravia Premium ativo" else "Premium grátis por 24h",
                fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 16.sp else 18.sp,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Surface(
                color = (if (active) c.good else c.action).copy(alpha = 0.16f),
                shape = RoundedCornerShape(99.dp),
            ) {
                Text(
                    if (active) adState.remainingLabel(nowMillis) else "$watched/${RewardedPremium.REQUIRED_ADS} anúncios",
                    color = if (active) c.good else c.action,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }

        if (active) {
            Text(
                "Todos os recursos Premium estão liberados por 24h (Stories sem marca d'água, Laboratório e prévias). Sem compras no app.",
                color = c.textSecondary,
                fontSize = 13.sp,
            )
            Button(
                onClick = onOpenTools,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
            ) {
                Text("Abrir ferramentas Premium", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(
                if (compact) "Assista 2 anúncios curtos opcionais para remover a marca d'água e liberar todo o Premium por 24h (sem compras no app)."
                else "Por enquanto não há compras no app: assista 2 anúncios curtos opcionais para liberar Stories sem marca d'água, Laboratório e recursos Premium por 24 horas. Nenhum dado de saúde sai do seu celular.",
                color = c.textSecondary,
                fontSize = 13.sp,
            )

            // Visual 2-step progress bar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(RewardedPremium.REQUIRED_ADS) { idx ->
                    val done = idx < watched
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = if (done) c.good.copy(alpha = 0.18f) else c.canvas,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            if (done) "✓ Anúncio ${idx + 1}" else "Anúncio ${idx + 1}",
                            color = if (done) c.good else c.textSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            adState.statusMessage?.let { msg ->
                Text(msg, color = c.good, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            adState.error?.let { err ->
                Text(err, color = c.low, fontSize = 13.sp)
            }

            Button(
                onClick = {
                    val act = activity ?: return@Button
                    RewardedPremium.watchNextAd(act) {
                        nowMillis = System.currentTimeMillis()
                    }
                },
                enabled = !adState.loadingAd && !adState.showingAd && activity != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
            ) {
                if (adState.loadingAd) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onAction)
                    Spacer(Modifier.width(8.dp))
                    Text("Carregando anúncio…", fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        "Assistir anúncio ($nextIndex/${RewardedPremium.REQUIRED_ADS}) · liberar 24h",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (BuildConfig.ADMOB_USING_TEST_IDS) {
                Text(
                    "Anúncio de teste oficial do Google AdMob ativo durante o beta.",
                    color = c.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
