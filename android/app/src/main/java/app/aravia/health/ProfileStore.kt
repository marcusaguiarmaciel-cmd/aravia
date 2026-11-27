package app.aravia.health

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aravia.analytics.Profile
import app.aravia.ui.Aravia
import app.aravia.ui.Barlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * User's body profile for Recovery, Sleep and Strain formulas (age, sex, height, weight, sleep
 * need and optional max HR override). Stays on the device in SharedPreferences.
 */
object ProfileStore {
    private lateinit var prefs: SharedPreferences
    private val _profile = MutableStateFlow(Profile.UNKNOWN)
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("aravia.profile", Context.MODE_PRIVATE)
        _profile.value = prefs.getString("v1", null)?.let { parse(it) } ?: Profile.UNKNOWN
    }

    fun save(p: Profile) {
        _profile.value = p
        if (!::prefs.isInitialized) return
        val o = JSONObject().put("sex", p.sex.raw)
        p.age?.let { o.put("age", it) }
        p.heightCm?.let { o.put("heightCm", it) }
        p.weightKg?.let { o.put("weightKg", it) }
        p.maxHROverride?.let { o.put("maxHROverride", it) }
        p.sleepNeedMinutes?.let { o.put("sleepNeedMinutes", it) }
        prefs.edit().putString("v1", o.toString()).apply()
    }

    private fun parse(raw: String): Profile = runCatching {
        val o = JSONObject(raw)
        Profile(
            age = if (o.has("age")) o.getInt("age") else null,
            sex = Profile.Sex.of(o.optString("sex", "unknown")),
            heightCm = if (o.has("heightCm")) o.getDouble("heightCm") else null,
            weightKg = if (o.has("weightKg")) o.getDouble("weightKg") else null,
            maxHROverride = if (o.has("maxHROverride")) o.getInt("maxHROverride") else null,
            sleepNeedMinutes = if (o.has("sleepNeedMinutes")) o.getInt("sleepNeedMinutes") else null,
        )
    }.getOrDefault(Profile.UNKNOWN)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileFields(
    initial: Profile,
    saveLabel: String = "Salvar perfil",
    onSave: (Profile) -> Unit,
) {
    val c = Aravia.colors
    var age by remember(initial) { mutableStateOf(initial.age?.toString() ?: "") }
    var sex by remember(initial) { mutableStateOf(initial.sex) }
    var height by remember(initial) { mutableStateOf(initial.heightCm?.toInt()?.toString() ?: "") }
    var weight by remember(initial) { mutableStateOf(initial.weightKg?.let { if (it == it.toInt().toDouble()) it.toInt().toString() else "%.1f".format(it) } ?: "") }
    var maxHr by remember(initial) { mutableStateOf(initial.maxHROverride?.toString() ?: "") }
    var sleepNeed by remember(initial) { mutableStateOf(initial.sleepNeedMinutes ?: Profile.DEFAULT_SLEEP_NEED_MINUTES) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Esses números calibram a frequência cardíaca máxima, a meta de sono e as pontuações de recuperação e esforço. Tudo é opcional.",
            fontSize = 14.sp, color = c.textSecondary)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = age,
                onValueChange = { age = it.filter(Char::isDigit).take(3) },
                label = { Text("Idade") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = maxHr,
                onValueChange = { maxHr = it.filter(Char::isDigit).take(3) },
                label = { Text("FC máx (opc.)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sexo (fator TRIMP do esforço)", fontSize = 13.sp, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Profile.Sex.entries.forEach { s ->
                    FilterChip(selected = sex == s, onClick = { sex = s }, label = { Text(s.labelPt) })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = height,
                onValueChange = { height = it.filter(Char::isDigit).take(3) },
                label = { Text("Altura (cm)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = weight,
                onValueChange = { weight = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(5) },
                label = { Text("Peso (kg)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }

        Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val h = sleepNeed / 60
            val m = sleepNeed % 60
            Text("Meta de sono: ${h} h ${"%02d".format(m)} min", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            Slider(
                value = sleepNeed.toFloat(),
                onValueChange = { sleepNeed = (it / 15f).toInt() * 15 },
                valueRange = 360f..600f,
                steps = 15,
            )
        }

        Button(
            onClick = {
                val p = Profile(
                    age = age.toIntOrNull()?.takeIf { it in 10..110 },
                    sex = sex,
                    heightCm = height.toDoubleOrNull()?.takeIf { it in 80.0..250.0 },
                    weightKg = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..300.0 },
                    maxHROverride = maxHr.toIntOrNull()?.takeIf { it in 110..240 },
                    sleepNeedMinutes = sleepNeed,
                )
                ProfileStore.save(p)
                onSave(p)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
        ) { Text(saveLabel, fontWeight = FontWeight.SemiBold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(onClose: () -> Unit) {
    val c = Aravia.colors
    val profile by ProfileStore.profile.collectAsState()
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.canvas) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Seu perfil", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = c.textPrimary)
            ProfileFields(initial = profile) { onClose() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Fechar") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
