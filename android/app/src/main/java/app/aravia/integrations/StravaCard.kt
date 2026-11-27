package app.aravia.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aravia.ui.Aravia
import kotlinx.coroutines.launch

/** Strava's orange, from its brand guidelines. */
val StravaOrange = Color(0xFFFC4C02)

/** The Strava card (Relógio tab and Quick Start): connect, send by itself, send now, disconnect. */
@Composable
fun StravaCard() {
    val c = Aravia.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Strava.state.collectAsState()
    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Strava", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = c.textPrimary)
        when {
            !Strava.isConfigured -> Text("Este build não tem as chaves do Strava.", color = c.low)
            s.connected -> {
                Text("Conectado como ${s.athlete}.", color = c.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enviar treinos novos sozinho", color = c.textPrimary, modifier = Modifier.weight(1f))
                    Switch(checked = s.automatic, onCheckedChange = { Strava.setAutomatic(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = StravaOrange))
                }
                if (s.automatic) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = s.sendRoutes, onClick = { Strava.setSendRoutes(!s.sendRoutes) }, label = { Text("Rotas GPS") })
                        FilterChip(selected = s.sendWorkouts, onClick = { Strava.setSendWorkouts(!s.sendWorkouts) }, label = { Text("Treinos no app") })
                    }
                }
                Text("Vão os treinos feitos no Aravia e as rotas de GPS do relógio, a partir de quando você conectou. " +
                    "Um mais antigo pode ser enviado pela tela dele.", fontSize = 13.sp, color = c.textSecondary)
                OutlinedButton(onClick = { scope.launch { Strava.sendNew() } }, enabled = !s.busy && s.automatic,
                    modifier = Modifier.fillMaxWidth()) { Text(if (s.busy) "Enviando…" else "Enviar o que falta agora") }
                s.status?.let { Text(it, fontSize = 13.sp, color = c.textSecondary) }
                TextButton(onClick = { Strava.signOut() }) { Text("Desconectar", color = c.low) }
            }
            else -> {
                Text("Os treinos vão sozinhos para o seu Strava, com o mapa das rotas de GPS. O Aravia só pede permissão para enviar atividades.",
                    color = c.textSecondary)
                Button(onClick = { Strava.signIn(context) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StravaOrange, contentColor = Color.White)) {
                    Text(if (s.busy) "Conectando…" else "Conectar com o Strava", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        s.error?.let { Text(it, fontSize = 13.sp, color = c.low) }
    }
}
