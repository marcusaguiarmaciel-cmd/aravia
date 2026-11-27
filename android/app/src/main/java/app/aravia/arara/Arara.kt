package app.aravia.arara

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import org.json.JSONObject

/** The arara's growth stages, the same names, points and lines as on iOS. */
enum class AraraStage(val key: String, val title: String, val threshold: Int, val line: String) {
    EGG("egg", "Ovo", 0, "Está chocando. Cada passo seu esquenta o ovo."),
    CHICK("chick", "Filhote", 30, "Nasceu! Ainda sem penas de voo, mas já com fome de caminhada."),
    YOUNG("young", "Jovem", 150, "As cores estão chegando e a cauda começou a crescer."),
    ADULT("adult", "Adulta", 500, "Plumagem completa: vermelho de urucum, ouro e azul de rio."),
    ROYAL("royal", "Arara-real", 1200, "Cauda longa e grafismos no galho. Poucas chegam aqui."),
    LEGEND("legend", "Lendária", 3000, "Lendária. A mata inteira conhece o seu voo.");

    val next: AraraStage? get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun of(xp: Int) = entries.last { xp >= it.threshold }
    }
}

/** Points: 1 per 1,000 steps, 10 per workout, 10 per GPS route (as on iOS). */
data class AraraProgress(val xp: Int) {
    val stage get() = AraraStage.of(xp)
    val fraction: Float get() {
        val next = stage.next ?: return 1f
        return (xp - stage.threshold).toFloat() / (next.threshold - stage.threshold)
    }

    companion object {
        fun xp(steps: Int, routes: Int, workouts: Int) = steps / 1000 + routes * 10 + workouts * 10
    }
}

enum class Role { FIXED, EGG, BODY, TAIL, WING, HEAD, EYE }

class Layer(val role: Role, val rgb: Int, val alpha: Float, val path: Path, val box: Rect) {
    val color get() = Color(0xFF000000.toInt() or rgb).copy(alpha = alpha)
}

/**
 * The drawings, read from assets/arara.json, which scripts/gen_android_shared.py copies from the
 * iOS app's Arara.swift, so both apps draw the same shapes on the same 200 × 200 grid.
 */
object AraraShapes {
    private var stages: Map<AraraStage, List<Layer>> = emptyMap()

    fun load(context: Context) {
        if (stages.isNotEmpty()) return
        val json = JSONObject(context.assets.open("arara.json").bufferedReader().readText())
        stages = AraraStage.entries.associateWith { stage ->
            val list = json.getJSONArray(stage.key)
            (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                val (path, box) = parse(o.getString("d"))
                Layer(Role.valueOf(o.getString("role").uppercase()), o.getInt("rgb"), o.getDouble("alpha").toFloat(), path, box)
            }
        }
    }

    fun layers(stage: AraraStage) = stages[stage] ?: emptyList()

    /** Absolute M, L, C, Q and Z, plus "E cx cy rx ry" for an ellipse; a command letter may
     *  touch its number ("M92 66"). Returns the path and its bounding box. */
    fun parse(d: String): Pair<Path, Rect> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { tokens += cur.toString(); cur.clear() } }
        for (ch in d) {
            when {
                ch.isLetter() -> { flush(); tokens += ch.toString() }
                ch == ' ' || ch == ',' -> flush()
                ch == '-' && cur.isNotEmpty() -> { flush(); cur.append(ch) }
                else -> cur.append(ch)
            }
        }
        flush()
        val path = Path()
        var i = 0
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        fun n(): Float = tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f
        fun p(): Offset {
            val o = Offset(n(), n())
            minX = minOf(minX, o.x); minY = minOf(minY, o.y); maxX = maxOf(maxX, o.x); maxY = maxOf(maxY, o.y)
            return o
        }
        while (i < tokens.size) {
            when (tokens[i++]) {
                "M" -> p().let { path.moveTo(it.x, it.y) }
                "L" -> p().let { path.lineTo(it.x, it.y) }
                "C" -> { val a = p(); val b = p(); val c = p(); path.cubicTo(a.x, a.y, b.x, b.y, c.x, c.y) }
                "Q" -> { val a = p(); val b = p(); path.quadraticTo(a.x, a.y, b.x, b.y) }
                "Z" -> path.close()
                "E" -> {
                    val cx = n(); val cy = n(); val rx = n(); val ry = n()
                    val r = Rect(cx - rx, cy - ry, cx + rx, cy + ry)
                    path.addOval(r)
                    minX = minOf(minX, r.left); minY = minOf(minY, r.top); maxX = maxOf(maxX, r.right); maxY = maxOf(maxY, r.bottom)
                }
            }
        }
        return path to Rect(minX, minY, maxX, maxY)
    }
}
