package app.aravia.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Seguir o sistema, claro ou escuro: kept on this phone (it is not part of the account). */
object Appearance {
    enum class Mode(val title: String) { SYSTEM("Seguir o sistema"), LIGHT("Claro"), DARK("Escuro") }

    private val _mode = MutableStateFlow(Mode.SYSTEM)
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("aravia", Context.MODE_PRIVATE)
        _mode.value = runCatching { Mode.valueOf(prefs!!.getString("appearance", "SYSTEM")!!) }.getOrDefault(Mode.SYSTEM)
    }

    fun set(m: Mode) {
        _mode.value = m
        prefs?.edit()?.putString("appearance", m.name)?.apply()
    }
}
