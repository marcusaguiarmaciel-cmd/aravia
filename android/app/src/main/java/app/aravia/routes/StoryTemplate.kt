package app.aravia.routes

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * How a route video looks: the same six built-in templates as on iOS (StoryTemplate.swift), plus
 * user-edited templates saved locally on this device (Android plan decision: never synced).
 * Colours are 0xRRGGBB.
 */
data class StoryTemplate(
    val name: String,
    val map: MapLook = MapLook.LIBERTY,
    /** 0 to 0.7: darkens the map, for night looks. */
    val mapDim: Float = 0f,
    val camera: Camera = Camera.STREET,
    /** Seconds of route playback, before the intro and the arara's ending. */
    val seconds: Double = 12.0,
    val speedColors: Boolean = true,
    val line: Int = 0xC0412A,
    val lineWidth: Float = 9f,
    val accent: Int = 0xC0412A,
    /** Colour of the shades behind the text. */
    val ink: Int = 0x16202B,
    val shades: Boolean = true,
    val layout: Layout = Layout.CLASSIC,
    val showDate: Boolean = true,
    val showDistance: Boolean = true,
    val showTime: Boolean = true,
    val showSpeed: Boolean = true,
    val showProgress: Boolean = true,
    val showBrand: Boolean = true,
    val custom: Boolean = false,
) {
    enum class MapLook(val title: String) {
        LIBERTY("Natural"), BRIGHT("Vivo"), POSITRON("Claro");
        val styleUrl: String get() = "https://tiles.openfreemap.org/styles/${name.lowercase()}"
    }

    enum class Camera(val title: String, val zoom: Double, val pitch: Double) {
        CLOSE("Bem perto", 18.0, 65.0), STREET("Rua", 17.4, 60.0), HIGH("Do alto", 16.3, 40.0), DRONE("Drone girando", 16.8, 55.0)
    }

    enum class Layout(val title: String) { CLASSIC("Clássico"), MINIMAL("Discreto"), BIG("Número grande") }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("map", map.name)
        .put("mapDim", mapDim.toDouble())
        .put("camera", camera.name)
        .put("seconds", seconds)
        .put("speedColors", speedColors)
        .put("line", line)
        .put("lineWidth", lineWidth.toDouble())
        .put("accent", accent)
        .put("ink", ink)
        .put("shades", shades)
        .put("layout", layout.name)
        .put("showDate", showDate)
        .put("showDistance", showDistance)
        .put("showTime", showTime)
        .put("showSpeed", showSpeed)
        .put("showProgress", showProgress)
        .put("showBrand", showBrand)

    companion object {
        val builtIn = listOf(
            StoryTemplate("Aravia"),
            StoryTemplate("Noite", mapDim = 0.5f, camera = Camera.CLOSE, speedColors = false, line = 0x4DC0CC, accent = 0x4DC0CC, ink = 0x0B1016),
            StoryTemplate("Discreto", map = MapLook.POSITRON, camera = Camera.HIGH, speedColors = false, line = 0x16202B, lineWidth = 7f,
                accent = 0x16202B, shades = false, layout = Layout.MINIMAL, showDate = false, showBrand = false),
            StoryTemplate("Drone", map = MapLook.BRIGHT, camera = Camera.DRONE, seconds = 15.0, accent = 0x2C7A50, ink = 0x10241A),
            StoryTemplate("Ritmo", camera = Camera.CLOSE, seconds = 10.0, lineWidth = 12f, accent = 0xC98A1F, layout = Layout.BIG, showTime = false),
            StoryTemplate("Pôr do sol", mapDim = 0.2f, speedColors = false, line = 0xF07A50, lineWidth = 11f, accent = 0xF07A50, ink = 0x4A1E12),
        )

        /** Slow to fast: river, forest, ochre, annatto, deep annatto. */
        val speedPalette = intArrayOf(0x1D727E, 0x2C7A50, 0xC98A1F, 0xC0412A, 0x8E2A1E)

        fun fromJson(o: JSONObject): StoryTemplate? = runCatching {
            StoryTemplate(
                name = o.getString("name"),
                map = runCatching { MapLook.valueOf(o.optString("map", "LIBERTY")) }.getOrDefault(MapLook.LIBERTY),
                mapDim = o.optDouble("mapDim", 0.0).toFloat(),
                camera = runCatching { Camera.valueOf(o.optString("camera", "STREET")) }.getOrDefault(Camera.STREET),
                seconds = o.optDouble("seconds", 12.0),
                speedColors = o.optBoolean("speedColors", true),
                line = o.optInt("line", 0xC0412A),
                lineWidth = o.optDouble("lineWidth", 9.0).toFloat(),
                accent = o.optInt("accent", 0xC0412A),
                ink = o.optInt("ink", 0x16202B),
                shades = o.optBoolean("shades", true),
                layout = runCatching { Layout.valueOf(o.optString("layout", "CLASSIC")) }.getOrDefault(Layout.CLASSIC),
                showDate = o.optBoolean("showDate", true),
                showDistance = o.optBoolean("showDistance", true),
                showTime = o.optBoolean("showTime", true),
                showSpeed = o.optBoolean("showSpeed", true),
                showProgress = o.optBoolean("showProgress", true),
                showBrand = o.optBoolean("showBrand", true),
                custom = true,
            )
        }.getOrNull()
    }
}

/** Device-local storage for saved Story templates and the user's chosen SAF video folder. */
object StoryTemplateStore {
    private lateinit var prefs: android.content.SharedPreferences
    private val _custom = MutableStateFlow<List<StoryTemplate>>(emptyList())
    val custom: StateFlow<List<StoryTemplate>> = _custom.asStateFlow()
    private val _folderUri = MutableStateFlow<String?>(null)
    val folderUri: StateFlow<String?> = _folderUri.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("aravia.story.templates", Context.MODE_PRIVATE)
        _custom.value = runCatching {
            val arr = JSONArray(prefs.getString("list", "[]"))
            (0 until arr.length()).mapNotNull { StoryTemplate.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        _folderUri.value = prefs.getString("folderUri", null)
    }

    fun save(template: StoryTemplate) {
        val clean = template.copy(name = template.name.trim().ifBlank { "Meu modelo" }, custom = true)
        val next = _custom.value.filterNot { it.name.equals(clean.name, ignoreCase = true) } + clean
        persist(next)
    }

    fun delete(name: String) {
        persist(_custom.value.filterNot { it.name.equals(name, ignoreCase = true) })
    }

    fun setFolder(uri: Uri?) {
        _folderUri.value = uri?.toString()
        if (::prefs.isInitialized) prefs.edit().putString("folderUri", uri?.toString()).apply()
    }

    private fun persist(list: List<StoryTemplate>) {
        _custom.value = list
        if (!::prefs.isInitialized) return
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
