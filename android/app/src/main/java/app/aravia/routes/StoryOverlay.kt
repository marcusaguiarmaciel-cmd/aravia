package app.aravia.routes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidPath
import androidx.core.content.res.ResourcesCompat
import app.aravia.R
import app.aravia.arara.AraraShapes
import app.aravia.arara.AraraStage
import app.aravia.arara.Role
import app.aravia.protocol.GpsTrack
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Instant (< 200 ms) Story photo & transparent PNG overlay generator: draws the route silhouette,
 * key workout numbers, the user's Arara and a subtle "Feito no Aravia" mark — either on an alpha
 * transparent PNG (ready to paste as a sticker on Instagram Stories) or composited over a photo
 * from the gallery.
 */
object StoryOverlay {
    const val WIDTH = 1080
    const val HEIGHT = 1920

    data class Accent(val label: String, val rgb: Int)
    val accents = listOf(
        Accent("Terracota", 0xE05A3A),
        Accent("Ouro", 0xE9B43A),
        Accent("Azul", 0x4EA3E8),
        Accent("Branco", 0xFFFFFF),
    )

    fun renderTransparent(
        context: Context,
        track: GpsTrack,
        title: String,
        dateMillis: Long,
        stage: AraraStage,
        accentRgb: Int,
        showWatermark: Boolean = true,
    ): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bold = ResourcesCompat.getFont(context, R.font.barlow_bold) ?: Typeface.DEFAULT_BOLD
        val semibold = ResourcesCompat.getFont(context, R.font.barlow_semibold) ?: Typeface.DEFAULT_BOLD
        val accent = 0xFF000000.toInt() or (accentRgb and 0x00FFFFFF)

        // Header + Arara badge.
        val margin = 76f
        val dateText = SimpleDateFormat("d 'de' MMMM · HH:mm", Locale("pt", "BR")).format(Date(dateMillis))
        drawText(c, title, margin, 175f, 68f, Color.WHITE, bold)
        drawText(c, dateText, margin, 260f, 36f, Color.argb(225, 245, 240, 232), semibold)

        // User's Arara perched at top-right.
        val birdSize = 240f
        val birdLeft = WIDTH - margin - birdSize
        val birdTop = 135f
        val scale = birdSize / 200f
        for (layer in AraraShapes.layers(stage)) {
            if (layer.role == Role.FIXED) continue
            val p = Path(layer.path.asAndroidPath())
            p.transform(Matrix().apply { setScale(scale, scale); postTranslate(birdLeft, birdTop) })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF000000.toInt() or layer.rgb
                alpha = (layer.alpha * 255).toInt()
            }
            c.drawPath(p, paint)
        }

        // Route silhouette in the middle safe box.
        drawRoute(c, track, RectF(110f, 440f, WIDTH - 110f, 1210f), accent)

        // Big numbers at the bottom.
        val dur = max(1.0, track.durationSeconds.toDouble())
        val distKm = String.format(Locale("pt", "BR"), "%.2f", track.distanceMeters / 1000.0)
        val clock = formatClock(dur.toLong())
        val avgKmh = track.distanceMeters / dur * 3.6
        val pace = if (avgKmh >= 1.0) {
            val s = Math.round(3600.0 / avgKmh)
            "%d:%02d".format(s / 60, s % 60)
        } else "--:--"

        val baseY = 1320f
        drawText(c, "$distKm km", WIDTH / 2f, baseY, 152f, Color.WHITE, bold, Paint.Align.CENTER)
        drawText(c, "DISTÂNCIA", WIDTH / 2f, baseY + 170f, 34f, accent, bold, Paint.Align.CENTER, spacing = 0.16f)

        val rowY = baseY + 255f
        drawText(c, clock, WIDTH * 0.28f, rowY, 76f, Color.WHITE, bold, Paint.Align.CENTER)
        drawText(c, "TEMPO", WIDTH * 0.28f, rowY + 90f, 30f, Color.argb(220, 245, 240, 232), semibold, Paint.Align.CENTER, spacing = 0.14f)

        drawText(c, "$pace /km", WIDTH * 0.72f, rowY, 76f, Color.WHITE, bold, Paint.Align.CENTER)
        drawText(c, "RITMO MÉDIO", WIDTH * 0.72f, rowY + 90f, 30f, Color.argb(220, 245, 240, 232), semibold, Paint.Align.CENTER, spacing = 0.14f)

        if (showWatermark) {
            drawText(c, "FEITO NO ARAVIA", WIDTH / 2f, HEIGHT - 155f, 30f, Color.argb(230, 233, 217, 184), bold, Paint.Align.CENTER, spacing = 0.24f)
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(220, 224, 90, 58)
            }
            val cy = HEIGHT - 96f
            val diamonds = Path()
            for (i in -3..3) {
                val cx = WIDTH / 2f + i * 32f
                diamonds.moveTo(cx, cy - 7f); diamonds.lineTo(cx + 7f, cy)
                diamonds.lineTo(cx, cy + 7f); diamonds.lineTo(cx - 7f, cy); diamonds.close()
            }
            c.drawPath(diamonds, dp)
        }
        return out
    }

    fun renderOverPhoto(photo: Bitmap, overlay: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val srcRatio = photo.width.toFloat() / photo.height.toFloat()
        val dstRatio = WIDTH.toFloat() / HEIGHT.toFloat()
        val srcRect = if (srcRatio > dstRatio) {
            val w = (photo.height * dstRatio).toInt()
            val x = (photo.width - w) / 2
            android.graphics.Rect(x, 0, x + w, photo.height)
        } else {
            val h = (photo.width / dstRatio).toInt()
            val y = (photo.height - h) / 2
            android.graphics.Rect(0, y, photo.width, y + h)
        }
        c.drawBitmap(photo, srcRect, RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))

        // Gentle top & bottom gradient scrims so white text stays readable on bright photos.
        val scrim = Paint()
        scrim.shader = LinearGradient(0f, 0f, 0f, 440f, Color.argb(150, 12, 16, 20), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, WIDTH.toFloat(), 440f, scrim)
        scrim.shader = LinearGradient(0f, HEIGHT - 760f, 0f, HEIGHT.toFloat(), Color.TRANSPARENT, Color.argb(200, 12, 16, 20), Shader.TileMode.CLAMP)
        c.drawRect(0f, HEIGHT - 760f, WIDTH.toFloat(), HEIGHT.toFloat(), scrim)

        c.drawBitmap(overlay, 0f, 0f, null)
        return out
    }

    fun decodePhoto(context: Context, uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, bounds)
            var sample = 1
            while (bounds.outWidth / sample > WIDTH * 2 || bounds.outHeight / sample > HEIGHT * 2) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { s2 ->
                BitmapFactory.decodeStream(s2, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    fun writeTempPng(context: Context, bitmap: Bitmap, fileId: String): File {
        val dir = File(context.cacheDir, "raw").apply { mkdirs() }
        val file = File(dir, "aravia-story-$fileId.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun savePngToGallery(context: Context, bitmap: Bitmap, name: String): String = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Aravia")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        "Salvo na galeria, em Imagens › Aravia."
    }.getOrElse { "Não deu para salvar: ${it.message}" }

    private fun drawRoute(c: Canvas, track: GpsTrack, box: RectF, accent: Int) {
        if (track.points.size < 2) return
        val midLat = (track.points.minOf { it.latitude } + track.points.maxOf { it.latitude }) / 2.0
        val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
        val xs = track.points.map { it.longitude * cosLat }
        val ys = track.points.map { -it.latitude }
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val spanX = max(1e-6, maxX - minX)
        val spanY = max(1e-6, maxY - minY)
        val scale = min(box.width() / spanX, box.height() / spanY)
        val ox = box.left + (box.width() - (spanX * scale).toFloat()) / 2f
        val oy = box.top + (box.height() - (spanY * scale).toFloat()) / 2f

        val path = Path()
        for (i in track.points.indices) {
            val px = ox + ((xs[i] - minX) * scale).toFloat()
            val py = oy + ((ys[i] - minY) * scale).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }

        // Soft dark halo so the line reads cleanly on any background.
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = 22f; color = Color.argb(115, 0, 0, 0)
        }
        c.drawPath(path, halo)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = 13f; color = accent
        }
        c.drawPath(path, line)

        // Start & finish markers.
        val sx = ox + ((xs.first() - minX) * scale).toFloat()
        val sy = oy + ((ys.first() - minY) * scale).toFloat()
        val ex = ox + ((xs.last() - minX) * scale).toFloat()
        val ey = oy + ((ys.last() - minY) * scale).toFloat()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        dot.color = Color.WHITE
        c.drawCircle(sx, sy, 14f, dot)
        c.drawCircle(ex, ey, 16f, dot)
        dot.color = accent
        c.drawCircle(ex, ey, 10f, dot)
    }

    private fun drawText(
        c: Canvas,
        s: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        face: Typeface,
        align: Paint.Align = Paint.Align.LEFT,
        spacing: Float = 0f,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = face
            textAlign = align
            letterSpacing = spacing
            setShadowLayer(12f, 0f, 3f, Color.argb(150, 0, 0, 0))
        }
        c.drawText(s, x, y + size, p)
    }

    private fun formatClock(s: Long): String =
        if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
