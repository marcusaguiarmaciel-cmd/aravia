package app.aravia.routes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.asAndroidPath
import app.aravia.arara.AraraShapes
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ending of every route video, ported directly from Aravia's iOS AraraFinale.swift (MIT):
 * the arara flies in facing the camera, lands on a leafy branch, folds its left wing and keeps
 * its right wing spread open as the camera closes in to show the workout numbers on its flight
 * feathers, with the ARAVIA diamond mark below.
 */
object AraraFinale {
    const val SECONDS = 5.8

    private enum class Role { FIXED, BRANCH, TAIL, BODY, FOLDWING, HEAD, EYE, WING }
    private class Layer(val role: Role, val rgb: Int, val alpha: Float, val path: Path)

    private data class Camera(val scale: Float, val ox: Float, val oy: Float) {
        fun apply(c: Canvas) {
            c.translate(ox, oy)
            c.scale(scale, scale)
        }
        fun pointX(x: Float) = ox + x * scale
        fun pointY(y: Float) = oy + y * scale
    }

    private val wide = Camera(3f, 60f, 444f)
    private val close = Camera(5f, -550f, 170f)

    private fun progress(t: Double, a: Double, b: Double) = ((t - a) / (b - a)).coerceIn(0.0, 1.0)
    private fun smooth(x: Double) = x * x * (3.0 - 2.0 * x)

    private fun frontCamera(p: Double): Camera {
        val k = p.toFloat()
        return Camera(
            scale = wide.scale + (close.scale - wide.scale) * k,
            ox = wide.ox + (close.ox - wide.ox) * k,
            oy = wide.oy + (close.oy - wide.oy) * k,
        )
    }

    private fun flyingCamera(p: Double): Camera {
        val k = p.toFloat()
        val scale = 0.7f + (wide.scale - 0.7f) * k
        val endX = wide.pointX(110f)
        val endY = wide.pointY(150f)
        val cx = 760f + (endX - 760f) * k
        val cy = 420f + (endY - 420f) * k - 40f * sin(p * PI).toFloat()
        return Camera(scale, cx - 110f * scale, cy - 150f * scale)
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        t: Double,
        map: Bitmap,
        ink: Int,
        stats: List<Pair<String, String>>,
        showBrand: Boolean,
        semibold: Typeface,
    ) {
        // Map push-in while the arara approaches, then darkens for the numbers.
        val zoom = (1.0 + 0.12 * smooth(progress(t, 0.0, 2.0))).toFloat()
        canvas.save()
        canvas.scale(zoom, zoom, width / 2f, height / 2f)
        canvas.drawBitmap(map, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        val dim = 0.12 + 0.6 * smooth(progress(t, 1.6, 3.4))
        canvas.drawColor(((dim * 255).toInt().coerceIn(0, 255) shl 24) or (ink and 0x00FFFFFF))

        val perch = frontCamera(smooth(progress(t, 2.6, 3.5)))
        val branchAlpha = smooth(progress(t, 1.2, 1.9)).toFloat()
        if (branchAlpha > 0f) {
            canvas.save()
            perch.apply(canvas)
            for (layer in frontLayers) {
                if (layer.role == Role.BRANCH) paint(canvas, layer.path, layer.rgb, layer.alpha * branchAlpha)
            }
            canvas.restore()
        }

        val birdCam = if (t < 2.0) flyingCamera(smooth(progress(t, 0.0, 2.0))) else perch
        val fold = smooth(progress(t, 2.0, 2.6))
        val flap = 0.5 * sin(t * 2.0 * PI * 4.0) * (1.0 - fold)

        canvas.save()
        birdCam.apply(canvas)
        drawFront(canvas, flap, fold)
        val statsAlpha = smooth(progress(t, 3.3, 3.9)).toFloat()
        if (statsAlpha > 0f) drawStats(canvas, stats, statsAlpha, semibold)
        canvas.restore()

        if (showBrand) {
            val brandAlpha = smooth(progress(t, 3.7, 4.3)).toFloat()
            if (brandAlpha > 0f) drawBrand(canvas, width.toFloat(), height.toFloat(), brandAlpha, semibold)
        }
    }

    private fun drawFront(canvas: Canvas, flap: Double, fold: Double) {
        val sx = 142f
        val sy = 136f
        val rightMatrix = Matrix().apply {
            setRotate((-flap * 180.0 / PI).toFloat(), sx, sy)
        }
        val leftMatrix = Matrix().apply {
            postTranslate(-sx, -sy)
            val s = (1.0 - 0.65 * fold).toFloat()
            postScale(s, s)
            postRotate(((-flap + fold * 100.0 * PI / 180.0) * 180.0 / PI).toFloat())
            postTranslate(sx, sy)
            // Mirror across the body's centre line x = 110.
            postScale(-1f, 1f)
            postTranslate(220f, 0f)
        }
        val tmp = Path()
        for (layer in frontLayers) {
            when (layer.role) {
                Role.BRANCH -> Unit
                Role.WING -> {
                    if (fold < 1.0) {
                        layer.path.transform(leftMatrix, tmp)
                        paint(canvas, tmp, layer.rgb, (layer.alpha * (1.0 - fold * 0.6)).toFloat())
                    }
                    layer.path.transform(rightMatrix, tmp)
                    paint(canvas, tmp, layer.rgb, layer.alpha)
                }
                Role.FOLDWING -> {
                    if (fold > 0.6) {
                        paint(canvas, layer.path, layer.rgb, (layer.alpha * min(1.0, (fold - 0.6) / 0.4)).toFloat())
                    }
                }
                else -> paint(canvas, layer.path, layer.rgb, layer.alpha)
            }
        }
    }

    private fun drawStats(canvas: Canvas, stats: List<Pair<String, String>>, alpha: Float, semibold: Typeface) {
        val shown = stats.take(3)
        if (shown.isEmpty()) return
        val sx = 142f
        val sy = 136f
        val inner = 74f
        val outer = 132f
        val top = 92f
        val bottom = 206f
        val row = (bottom - top) / shown.size
        val wanted = when (shown.size) {
            1 -> listOf(34f)
            2 -> listOf(30f, 24f)
            else -> listOf(27f, 22f, 19f)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = semibold
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f, 0f, 0.6f, Color.argb((0.3f * alpha * 255).toInt(), 0, 0, 0))
        }
        shown.forEachIndexed { i, (value, label) ->
            val lineTop = top + i * row + (1f - alpha) * 6f
            val middle = lineTop + row * 0.45f
            val dy = middle - sy
            val left = sx + sqrt(max(0f, inner * inner - min(dy * dy, inner * inner)))
            val right = sx + sqrt(max(0f, outer * outer - min(dy * dy, outer * outer)))
            val width = max(20f, right - left)
            val cx = left + width / 2f

            val vSize = fittedSize(value, wanted[i], width, 0f, semibold)
            p.textSize = vSize
            p.letterSpacing = 0f
            p.color = Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawText(value, cx, lineTop + vSize * 0.92f, p)

            val upperLabel = label.uppercase()
            val lSize = fittedSize(upperLabel, 8f, width, 0.14f, semibold)
            p.textSize = lSize
            p.letterSpacing = 0.14f
            p.color = Color.argb((0.85f * alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawText(upperLabel, cx, lineTop + vSize * 1.08f + lSize * 0.95f, p)
        }
    }

    private fun fittedSize(text: String, maxSize: Float, maxWidth: Float, spacing: Float, face: Typeface): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; letterSpacing = spacing }
        var sz = maxSize
        while (sz > 6f) {
            p.textSize = sz
            if (p.measureText(text) <= maxWidth) return sz
            sz -= 0.5f
        }
        return 6f
    }

    private fun drawBrand(canvas: Canvas, width: Float, height: Float, alpha: Float, semibold: Typeface) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val y = height - 300f
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = semibold
            textSize = 80f
            letterSpacing = 0.28f
            textAlign = Paint.Align.CENTER
            color = (a shl 24) or 0xE9D9B8
            setShadowLayer(6f, 0f, 2f, Color.argb((0.35f * a).toInt(), 0, 0, 0))
        }
        canvas.drawText("ARAVIA", width / 2f, y + 76f, tp)

        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = (a shl 24) or 0xE05A3A
        }
        val count = 9
        val step = 40f
        val startX = width / 2f - step * (count - 1) / 2f
        val cy = y + 124f
        val diamonds = Path()
        for (i in 0 until count) {
            val cx = startX + i * step
            diamonds.moveTo(cx, cy - 10f)
            diamonds.lineTo(cx + 10f, cy)
            diamonds.lineTo(cx, cy + 10f)
            diamonds.lineTo(cx - 10f, cy)
            diamonds.close()
        }
        canvas.drawPath(diamonds, dp)
    }

    private fun paint(canvas: Canvas, path: Path, rgb: Int, alpha: Float) {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        if (a <= 0) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = (a shl 24) or (rgb and 0x00FFFFFF)
        }
        canvas.drawPath(path, p)
    }

    private fun f(role: Role, rgb: Int, alpha: Float, d: String): Layer {
        val (composePath, _) = AraraShapes.parse(d)
        return Layer(role, rgb, alpha, Path(composePath.asAndroidPath()))
    }

    private val frontLayers: List<Layer> by lazy {
        listOf(
            f(Role.BRANCH, 0x7A452B, 1.0f, "M-60 242 C80 234 230 234 380 240 L380 262 C230 258 80 258 -60 266 Z"),
            f(Role.BRANCH, 0x5E3420, 1.0f, "M-20 248 C20 246 60 246 100 247 L100 249 C60 248 20 248 -20 250 Z"),
            f(Role.BRANCH, 0x5E3420, 1.0f, "M180 245 C220 244 260 245 320 246 L320 248 C260 247 220 246 180 247 Z"),
            f(Role.BRANCH, 0x7A452B, 1.0f, "M250 240 C262 226 274 216 290 208 L293 212 C278 220 266 230 258 242 Z"),
            f(Role.BRANCH, 0x3F7D3A, 1.0f, "M290 208 C300 192 318 188 330 192 C322 204 306 212 290 208 Z"),
            f(Role.BRANCH, 0x2F6B32, 1.0f, "M278 216 C280 198 292 186 304 182 C304 198 294 210 278 216 Z"),
            f(Role.BRANCH, 0x3F7D3A, 1.0f, "M30 244 C24 228 30 214 42 206 C46 222 42 236 30 244 Z"),
            f(Role.TAIL, 0xB92D25, 1.0f, "M99 206 C96 246 99 278 104 300 L116 300 C118 276 118 246 116 206 Z"),
            f(Role.TAIL, 0xD2362B, 1.0f, "M104 206 C103 246 106 278 110 300 L126 300 C128 278 127 246 122 206 Z"),
            f(Role.TAIL, 0x2F6DB5, 1.0f, "M106 282 L110 300 L126 300 L127 282 Z"),
            f(Role.WING, 0x2F6DB5, 1.0f, "M174.6 160.9 L213.7 198.1 L240.6 232.5 L240.5 241.7 L236.7 248.8 L229.0 251.4 L219.9 249.9 L190.7 217.4 L160.8 172.4 Z"),
            f(Role.WING, 0x2A5FA0, 1.0f, "M176.3 158.5 L218.5 193.6 L248.3 226.7 L248.9 235.9 L245.6 243.3 L238.1 246.3 L228.9 245.4 L196.9 214.4 L163.3 171.0 Z"),
            f(Role.WING, 0x2F6DB5, 1.0f, "M180.2 150.9 L231.0 177.9 L269.3 205.6 L271.8 214.5 L270.1 222.4 L263.4 226.9 L254.2 228.0 L214.2 202.8 L170.1 165.8 Z"),
            f(Role.WING, 0x3A7CC8, 1.0f, "M182.5 142.6 L240.3 159.5 L286.0 179.6 L290.3 187.8 L290.3 195.9 L284.7 201.7 L275.9 204.7 L229.1 187.3 L175.7 159.3 Z"),
            f(Role.WING, 0x2A5FA0, 1.0f, "M183.0 134.1 L245.7 139.1 L297.3 149.7 L303.1 156.8 L304.8 164.7 L300.5 171.6 L292.6 176.3 L240.5 168.6 L179.8 151.8 Z"),
            f(Role.WING, 0x2F6DB5, 1.0f, "M181.7 125.6 L244.1 117.4 L296.7 117.1 L303.9 122.8 L307.2 130.2 L304.5 137.8 L297.7 144.1 L245.1 147.4 L182.3 143.6 Z"),
            f(Role.WING, 0x3A7CC8, 1.0f, "M178.6 117.6 L235.4 97.3 L284.2 86.6 L292.4 90.8 L297.2 97.3 L296.1 105.3 L290.7 112.8 L242.6 126.4 L183.0 135.1 Z"),
            f(Role.WING, 0x2A5FA0, 1.0f, "M174.0 110.4 L222.8 79.9 L266.0 60.5 L274.9 62.8 L280.9 68.3 L281.5 76.3 L277.8 84.8 L236.0 106.9 L181.9 126.6 Z"),
            f(Role.WING, 0x2F6DB5, 1.0f, "M168.0 104.3 L207.3 66.0 L243.3 39.7 L252.5 40.1 L259.5 44.2 L261.7 52.0 L259.9 61.0 L225.7 89.6 L179.1 118.5 Z"),
            f(Role.WING, 0x3A7CC8, 1.0f, "M160.8 99.6 L189.5 56.0 L217.6 24.9 L226.7 23.4 L234.4 25.9 L238.2 33.0 L238.3 42.3 L212.5 75.3 L174.6 111.1 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M187.8 80.5 L230.2 30.3 L230.8 30.8 L188.7 81.2 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M198.4 91.2 L254.5 47.6 L255.0 48.2 L199.1 92.1 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M206.5 103.9 L275.3 70.5 L275.6 71.3 L207.0 105.0 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M211.7 118.0 L291.2 98.4 L291.4 99.2 L212.0 119.2 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M213.9 132.9 L301.2 130.0 L301.2 130.8 L214.0 134.1 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M213.0 147.9 L298.9 163.3 L298.8 164.1 L212.8 149.1 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M209.0 162.4 L284.8 193.3 L284.5 194.0 L208.5 163.5 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M202.0 175.8 L265.3 218.7 L264.9 219.4 L201.4 176.8 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M192.4 187.4 L241.7 238.7 L241.1 239.2 L191.6 188.2 Z"),
            f(Role.WING, 0x1E3F78, 0.6f, "M182.8 195.4 L219.8 250.7 L219.2 251.1 L181.8 196.0 Z"),
            f(Role.WING, 0x2F8C9A, 1.0f, "M178.9 81.3 L186.6 80.8 L187.7 88.4 L195.4 89.2 L195.2 96.9 L202.6 99.0 L201.1 106.6 L208.0 109.9 L205.2 117.1 L211.5 121.5 L207.5 128.1 L213.0 133.6 L207.9 139.5 L212.3 145.8 L206.4 150.7 L209.6 157.6 L202.9 161.4 L204.9 168.9 L197.7 171.5 L198.4 179.1 L190.8 180.4 L190.2 188.1 L182.5 188.1 L180.6 195.6 L173.0 194.3 L142.0 136.0 Z"),
            f(Role.WING, 0xE9B43A, 1.0f, "M171.1 92.9 L177.2 92.4 L178.0 98.5 L184.1 99.1 L183.9 105.2 L189.8 106.8 L188.5 112.8 L194.1 115.4 L191.8 121.1 L196.8 124.6 L193.6 129.8 L198.0 134.1 L193.9 138.7 L197.5 143.7 L192.7 147.6 L195.3 153.1 L190.0 156.0 L191.6 161.9 L185.9 163.9 L186.5 170.0 L180.4 171.0 L180.0 177.1 L173.9 177.1 L172.4 183.0 L166.4 181.9 L142.0 136.0 Z"),
            f(Role.WING, 0x8FA83A, 1.0f, "M171.4 96.2 Q175.4 100.2 179.4 96.2 Q175.4 98.2 171.4 96.2 Z"),
            f(Role.WING, 0x8FA83A, 1.0f, "M179.5 104.7 Q183.5 108.7 187.5 104.7 Q183.5 106.7 179.5 104.7 Z"),
            f(Role.WING, 0x8FA83A, 1.0f, "M185.5 114.8 Q189.5 118.8 193.5 114.8 Q189.5 116.8 185.5 114.8 Z"),
            f(Role.WING, 0x8FA83A, 1.0f, "M189.0 126.1 Q193.0 130.1 197.0 126.1 Q193.0 128.1 189.0 126.1 Z"),
            f(Role.WING, 0x8FA83A, 1.0f, "M190.0 137.8 Q194.0 141.8 198.0 137.8 Q194.0 139.8 190.0 137.8 Z"),
            f(Role.WING, 0x8FA83A, 1.0f, "M188.2 149.5 Q192.2 153.5 196.2 149.5 Q192.2 151.5 188.2 149.5 Z"),
            f(Role.WING, 0x8FA83A, 1.0f, "M183.9 160.4 Q187.9 164.4 191.9 160.4 Q187.9 162.4 183.9 160.4 Z"),
            f(Role.WING, 0x8FA83A, 1.0f, "M177.2 170.1 Q181.2 174.1 185.2 170.1 Q181.2 172.1 177.2 170.1 Z"),
            f(Role.WING, 0x8FA83A, 1.0f, "M168.6 178.1 Q172.6 182.1 176.6 178.1 Q172.6 180.1 168.6 178.1 Z"),
            f(Role.WING, 0xD2362B, 1.0f, "M163.2 102.1 L167.9 101.7 L168.9 106.4 L173.6 106.8 L173.7 111.6 L178.2 112.8 L177.5 117.5 L181.8 119.6 L180.2 124.1 L184.0 126.9 L181.7 131.0 L185.0 134.4 L181.9 138.1 L184.6 142.0 L180.9 145.1 L182.8 149.5 L178.7 151.8 L179.8 156.5 L175.4 158.1 L175.6 162.8 L170.9 163.6 L170.4 168.3 L165.6 168.3 L164.2 172.8 L159.5 172.0 L142.0 136.0 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M150.8 122.6 Q154.0 126.5 157.2 122.6 Q154.0 124.5 150.8 122.6 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M154.2 126.7 Q157.4 130.6 160.6 126.7 Q157.4 128.6 154.2 126.7 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M156.3 131.6 Q159.5 135.5 162.7 131.6 Q159.5 133.6 156.3 131.6 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M156.8 136.9 Q160.0 140.8 163.2 136.9 Q160.0 138.9 156.8 136.9 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M155.7 142.2 Q158.9 146.0 162.1 142.2 Q158.9 144.1 155.7 142.2 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M153.2 146.8 Q156.4 150.7 159.6 146.8 Q156.4 148.8 153.2 146.8 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M149.4 150.6 Q152.6 154.4 155.8 150.6 Q152.6 152.5 149.4 150.6 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M158.9 113.7 Q162.1 117.5 165.3 113.7 Q162.1 115.6 158.9 113.7 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M164.5 120.5 Q167.7 124.4 170.9 120.5 Q167.7 122.5 164.5 120.5 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M167.9 128.7 Q171.1 132.6 174.3 128.7 Q171.1 130.7 167.9 128.7 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M168.8 137.6 Q172.0 141.4 175.2 137.6 Q172.0 139.5 168.8 137.6 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M167.0 146.3 Q170.2 150.1 173.4 146.3 Q170.2 148.2 167.0 146.3 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M162.8 154.1 Q166.0 157.9 169.2 154.1 Q166.0 156.0 162.8 154.1 Z"),
            f(Role.WING, 0xB92D25, 1.0f, "M156.4 160.3 Q159.6 164.1 162.8 160.3 Q159.6 162.2 156.4 160.3 Z"),
            f(Role.BODY, 0xD2362B, 1.0f, "M110 108 C138 108 150 140 150 172 C150 206 132 228 110 228 C88 228 70 206 70 172 C70 140 82 108 110 108 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M92.5 132.0 Q96.5 137.0 100.5 132.0 Q96.5 134.5 92.5 132.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M101.5 132.0 Q105.5 137.0 109.5 132.0 Q105.5 134.5 101.5 132.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M110.5 132.0 Q114.5 137.0 118.5 132.0 Q114.5 134.5 110.5 132.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M119.5 132.0 Q123.5 137.0 127.5 132.0 Q123.5 134.5 119.5 132.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M88.0 146.0 Q92.0 151.0 96.0 146.0 Q92.0 148.5 88.0 146.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M100.0 146.0 Q104.0 151.0 108.0 146.0 Q104.0 148.5 100.0 146.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M112.0 146.0 Q116.0 151.0 120.0 146.0 Q116.0 148.5 112.0 146.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M124.0 146.0 Q128.0 151.0 132.0 146.0 Q128.0 148.5 124.0 146.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M86.5 160.0 Q90.5 165.0 94.5 160.0 Q90.5 162.5 86.5 160.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M99.5 160.0 Q103.5 165.0 107.5 160.0 Q103.5 162.5 99.5 160.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M112.5 160.0 Q116.5 165.0 120.5 160.0 Q116.5 162.5 112.5 160.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M125.5 160.0 Q129.5 165.0 133.5 160.0 Q129.5 162.5 125.5 160.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M86.5 174.0 Q90.5 179.0 94.5 174.0 Q90.5 176.5 86.5 174.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M99.5 174.0 Q103.5 179.0 107.5 174.0 Q103.5 176.5 99.5 174.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M112.5 174.0 Q116.5 179.0 120.5 174.0 Q116.5 176.5 112.5 174.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M125.5 174.0 Q129.5 179.0 133.5 174.0 Q129.5 176.5 125.5 174.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M88.0 188.0 Q92.0 193.0 96.0 188.0 Q92.0 190.5 88.0 188.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M100.0 188.0 Q104.0 193.0 108.0 188.0 Q104.0 190.5 100.0 188.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M112.0 188.0 Q116.0 193.0 120.0 188.0 Q116.0 190.5 112.0 188.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M124.0 188.0 Q128.0 193.0 132.0 188.0 Q128.0 190.5 124.0 188.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M92.5 202.0 Q96.5 207.0 100.5 202.0 Q96.5 204.5 92.5 202.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M101.5 202.0 Q105.5 207.0 109.5 202.0 Q105.5 204.5 101.5 202.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M110.5 202.0 Q114.5 207.0 118.5 202.0 Q114.5 204.5 110.5 202.0 Z"),
            f(Role.BODY, 0xB92D25, 1.0f, "M119.5 202.0 Q123.5 207.0 127.5 202.0 Q123.5 204.5 119.5 202.0 Z"),
            f(Role.FOLDWING, 0x2A5FA0, 1.0f, "M78 150 C66 168 64 204 76 234 C84 238 90 234 92 226 C90 198 90 172 92 154 Z"),
            f(Role.FOLDWING, 0x2F6DB5, 1.0f, "M84 156 C78 176 78 204 84 230 L86 230 C82 204 82 178 88 158 Z"),
            f(Role.FOLDWING, 0x2F8C9A, 1.0f, "M76 146 C70 154 68 162 68 170 C76 168 86 168 92 168 L92 150 Z"),
            f(Role.FOLDWING, 0xE9B43A, 1.0f, "M78 136 C72 146 70 154 70 160 C78 156 86 154 92 154 L92 138 Z"),
            f(Role.FOLDWING, 0xD2362B, 1.0f, "M82 122 C76 130 74 138 74 146 C80 142 86 140 92 140 L92 124 Z"),
            f(Role.HEAD, 0xD2362B, 1.0f, "E 110 88 32 31"),
            f(Role.HEAD, 0xF3ECE1, 1.0f, "E 95 90 11.5 13"),
            f(Role.HEAD, 0xF3ECE1, 1.0f, "E 125 90 11.5 13"),
            f(Role.HEAD, 0xB8473C, 1.0f, "M88 94 Q95 96 102 94 L102 95 Q95 97 88 95 Z"),
            f(Role.HEAD, 0xB8473C, 1.0f, "M88 98 Q95 100 102 98 L102 99 Q95 101 88 99 Z"),
            f(Role.HEAD, 0xB8473C, 1.0f, "M118 94 Q125 96 132 94 L132 95 Q125 97 118 95 Z"),
            f(Role.HEAD, 0xB8473C, 1.0f, "M118 98 Q125 100 132 98 L132 99 Q125 101 118 99 Z"),
            f(Role.EYE, 0xF2D98A, 1.0f, "E 96 86 4.6 4.6"),
            f(Role.EYE, 0xF2D98A, 1.0f, "E 124 86 4.6 4.6"),
            f(Role.EYE, 0x111111, 1.0f, "E 96 86 2.7 2.7"),
            f(Role.EYE, 0x111111, 1.0f, "E 124 86 2.7 2.7"),
            f(Role.EYE, 0xFFFFFF, 1.0f, "E 97 85 1 1"),
            f(Role.EYE, 0xFFFFFF, 1.0f, "E 125 85 1 1"),
            f(Role.HEAD, 0xEDE3D1, 1.0f, "M100 96 C100 86 120 86 120 96 C120 110 114 120 110 126 C106 120 100 110 100 96 Z"),
            f(Role.HEAD, 0x3A302B, 1.0f, "M107 120 C108 123 109 125 110 126 C111 125 112 123 113 120 C111 121 109 121 107 120 Z"),
            f(Role.HEAD, 0x2B2522, 1.0f, "M104 108 C106 116 108 120 110 122 C112 120 114 116 116 108 C112 112 108 112 104 108 Z"),
            f(Role.FIXED, 0x6F6E74, 1.0f, "M92 246 C95 242 104 242 106 246 L106 251 C103 249 95 249 92 251 Z"),
            f(Role.FIXED, 0x6F6E74, 1.0f, "M114 246 C117 242 126 242 128 246 L128 251 C125 249 117 249 114 251 Z"),
            f(Role.FIXED, 0x3A393E, 1.0f, "M93 251 L92 255 L95 253 Z"),
            f(Role.FIXED, 0x3A393E, 1.0f, "M105 251 L104 255 L107 253 Z"),
            f(Role.FIXED, 0x3A393E, 1.0f, "M115 251 L114 255 L117 253 Z"),
            f(Role.FIXED, 0x3A393E, 1.0f, "M127 251 L126 255 L129 253 Z"),
        )
    }
}
