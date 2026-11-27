package app.aravia.routes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.ui.graphics.asAndroidPath
import androidx.core.content.res.ResourcesCompat
import app.aravia.R
import app.aravia.arara.AraraShapes
import app.aravia.arara.AraraStage
import app.aravia.arara.Role
import app.aravia.protocol.GpsTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter

/**
 * A route replay as a vertical 1080 × 1920 MP4, as on iOS (RouteVideoRenderer.swift): every frame
 * is a MapLibre snapshot of the tilted map following the route, with the route drawn over it and
 * the title and numbers on top; then the arara's ending over the whole route. The frames go
 * through the phone's H.264 encoder, with a silent AAC track, since Instagram refuses some videos
 * without sound.
 *
 * The ending is simpler than on iOS for now: the arara flies in and lands while the map darkens,
 * then the numbers come up.
 */
class RouteVideo(
    private val context: Context,
    private val track: GpsTrack,
    private val title: String,
    private val dateMillis: Long,
    private val template: StoryTemplate,
    private val stage: AraraStage,
) {
    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val FPS = 24
        const val INTRO = 0.8
        const val OUTRO = 5.8
    }

    class RenderException(message: String) : Exception(message)

    private var snapshotter: MapSnapshotter? = null
    @Volatile private var cancelled = false
    private val duration = track.durationSeconds.toDouble().coerceAtLeast(1.0)
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.barlow_bold) ?: Typeface.DEFAULT_BOLD
    private val semibold: Typeface = ResourcesCompat.getFont(context, R.font.barlow_semibold) ?: Typeface.DEFAULT_BOLD
    private val runs = track.speedRuns()

    fun cancel() { cancelled = true; snapshotter?.cancel() }

    // Cameras

    private fun heading(elapsed: Double, current: Double?): Double =
        if (template.camera == StoryTemplate.Camera.DRONE) (track.nextHeading(0.0, null) + 160 * elapsed / duration) % 360
        else track.nextHeading(elapsed, current)

    private fun follow(elapsed: Double, heading: Double): CameraPosition {
        val p = track.position(elapsed)
        return CameraPosition.Builder().target(LatLng(p.latitude, p.longitude)).zoom(template.camera.zoom)
            .tilt(template.camera.pitch).bearing(heading).build()
    }

    /** The whole route in about 70% of the frame's width, tilted. */
    private fun overview(): CameraPosition {
        val lats = track.points.map { it.latitude }; val lons = track.points.map { it.longitude }
        val a = app.aravia.protocol.GpsPoint(0, lats.min(), lons.min(), null)
        val b = app.aravia.protocol.GpsPoint(0, lats.max(), lons.max(), null)
        val span = max(GpsTrack.meters(a, b), 150.0)
        val lat = (lats.min() + lats.max()) / 2 * Math.PI / 180
        val zoom = (ln(40_075_016.686 * cos(lat) * (WIDTH / 2 * 0.7) / (512 * span)) / ln(2.0)).coerceIn(11.0, 17.0)
        return CameraPosition.Builder().target(LatLng((lats.min() + lats.max()) / 2, (lons.min() + lons.max()) / 2))
            .zoom(zoom - 0.4).tilt(45.0).bearing(330.0).build()
    }

    // One frame

    /** One composed frame at `fraction` of the route (1 = the ending), for the screen's preview. */
    suspend fun preview(fraction: Double): Bitmap {
        if (fraction >= 1) return finale(snapshot(overview(), duration), OUTRO - 0.8, stats())
        val elapsed = duration * fraction.coerceIn(0.0, 1.0)
        return compose(snapshot(follow(elapsed, heading(elapsed, null)), elapsed), elapsed)
    }

    private suspend fun snapshot(camera: CameraPosition, elapsed: Double): Bitmap {
        MapLibre.getInstance(context)
        val s = snapshotter ?: MapSnapshotter(context, MapSnapshotter.Options(WIDTH / 2, HEIGHT / 2)
            .withStyleBuilder(Style.Builder().fromUri(template.map.styleUrl))
            .withCameraPosition(camera)
            .withPixelRatio(2f)
            .withLogo(false)).also { snapshotter = it }
        s.setCameraPosition(camera)
        val shot: MapSnapshot = suspendCancellableCoroutine { cont ->
            s.start({ cont.resume(it) }, { error -> cont.resumeWithException(RenderException("Não foi possível desenhar o mapa ($error). Confira a internet.")) })
        }
        val bitmap = shot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        drawRoute(Canvas(bitmap), bitmap.width, bitmap.height, camera.target!!, elapsed) { shot.pixelForLatLng(it) }
        return bitmap
    }

    /** The whole route faint, the part done so far in speed colours, and the walker. */
    private fun drawRoute(c: Canvas, w: Int, h: Int, center: LatLng, elapsed: Double, point: (LatLng) -> PointF) {
        // pixelForLatLng gives points or pixels depending on the version: the camera's centre,
        // which sits in the middle of the image, tells which.
        val mid = point(center)
        val scale = if (kotlin.math.abs(mid.x - w / 2f) < kotlin.math.abs(mid.x - w / 4f)) 1f else 2f
        fun at(lat: Double, lon: Double) = point(LatLng(lat, lon)).let { PointF(it.x * scale, it.y * scale) }
        val maxJump = hypot(w.toFloat(), h.toFloat())
        fun visible(p: PointF) = p.x.isFinite() && p.y.isFinite() && p.x > -w && p.x < 2 * w && p.y > -h && p.y < 2 * h
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun stroke(coords: List<Pair<Double, Double>>, color: Int, width: Float) {
            if (coords.size < 2) return
            val path = android.graphics.Path()
            var last: PointF? = null
            for ((lat, lon) in coords) {
                val p = at(lat, lon)
                if (!visible(p)) { last = null; continue }
                val l = last
                if (l != null && hypot(p.x - l.x, p.y - l.y) < maxJump) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                last = p
            }
            paint.color = color; paint.strokeWidth = width
            c.drawPath(path, paint)
        }
        val width = template.lineWidth * 2
        stroke(track.points.map { it.latitude to it.longitude }, Color.argb(90, 0, 0, 0), max(6f, width * 0.65f))
        val now = track.position(elapsed)
        for ((range, band) in runs) {
            if (range.first > now.index) break
            val upper = min(range.last, now.index)
            val coords = track.points.subList(range.first, upper + 1).map { it.latitude to it.longitude }.toMutableList()
            if (now.index in range) coords += now.latitude to now.longitude
            stroke(coords, opaque(if (template.speedColors) StoryTemplate.speedPalette[band] else template.line), width)
        }
        val p = at(now.latitude, now.longitude)
        if (!visible(p)) return
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = (opaque(template.accent) and 0x00FFFFFF) or 0x40000000
        c.drawCircle(p.x, p.y, 40f, fill)
        fill.color = Color.WHITE; c.drawCircle(p.x, p.y, 22f, fill)
        fill.color = opaque(template.accent); c.drawCircle(p.x, p.y, 15f, fill)
    }

    private fun opaque(rgb: Int) = rgb or 0xFF000000.toInt()

    private fun stats(): List<Pair<String, String>> {
        val avg = track.distanceMeters / duration * 3.6
        return listOfNotNull(
            if (template.showDistance) km(track.distanceMeters) to "distância" else null,
            if (template.showTime) clock(duration) to "tempo" else null,
            if (template.showSpeed) "${pace(avg)} /km" to "ritmo médio" else null,
        )
    }

    private fun km(m: Double) = String.format(Locale("pt", "BR"), "%.2f km", m / 1000)
    private fun clock(s: Double): String { val t = s.toLong(); return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }
    private fun pace(kmh: Double): String { if (kmh < 1) return "--:--"; val s = Math.round(3600 / kmh); return "%d:%02d".format(s / 60, s % 60) }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, face: Typeface, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = face; textAlign = align; letterSpacing = spacing
            setShadowLayer(8f, 0f, 2f, Color.argb(90, 0, 0, 0))
        }
        c.drawText(s, x, y + size, p)
    }

    /** The title, date and numbers over the map, in the template's layout. */
    private fun compose(map: Bitmap, elapsed: Double, overview: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(map, null, RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        val t = template
        if (t.mapDim > 0) c.drawColor(Color.argb((min(t.mapDim, 0.7f) * 255).toInt(), 0, 0, 0))
        val ink = opaque(t.ink)
        if (t.shades) {
            val p = Paint()
            p.shader = LinearGradient(0f, 0f, 0f, 420f, (ink and 0xFFFFFF) or (204 shl 24), ink and 0xFFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, WIDTH.toFloat(), 420f, p)
            p.shader = LinearGradient(0f, HEIGHT - 640f, 0f, HEIGHT.toFloat(), ink and 0xFFFFFF, (ink and 0xFFFFFF) or (230 shl 24), Shader.TileMode.CLAMP)
            c.drawRect(0f, HEIGHT - 640f, WIDTH.toFloat(), HEIGHT.toFloat(), p)
        }
        val fg = if (t.shades || t.mapDim > 0.25f) Color.WHITE else ink
        val soft = (fg and 0xFFFFFF) or (204 shl 24)
        val margin = 72f
        val width = WIDTH - 2 * margin
        val titleSize = if (t.layout == StoryTemplate.Layout.MINIMAL) 52f else 70f
        text(c, title, margin, 150f, titleSize, fg, bold)
        if (t.showDate) {
            val date = SimpleDateFormat("d 'de' MMMM, HH:mm", Locale("pt", "BR")).format(Date(dateMillis))
            text(c, date, margin, 150 + titleSize * 1.3f, if (t.layout == StoryTemplate.Layout.MINIMAL) 32f else 38f, soft, semibold)
        }
        val now = track.position(elapsed)
        val done = if (overview) 1.0 else min(1.0, elapsed / duration)
        val avg = track.distanceMeters / duration * 3.6
        val stats = listOfNotNull(
            if (t.showDistance) km(if (overview) track.distanceMeters else now.distance) to "distância" else null,
            if (t.showTime) clock(if (overview) duration else elapsed) to "tempo" else null,
            if (t.showSpeed) pace(if (overview) avg else now.speedKmh) to (if (overview) "ritmo médio /km" else "ritmo /km") else null,
        )
        fun bar(y: Float, h: Float) {
            if (!t.showProgress) return
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = (fg and 0xFFFFFF) or (64 shl 24)
            c.drawRoundRect(RectF(margin, y, margin + width, y + h), h / 2, h / 2, p)
            p.color = opaque(t.accent)
            c.drawRoundRect(RectF(margin, y, margin + max(h, (width * done).toFloat()), y + h), h / 2, h / 2, p)
        }
        when (t.layout) {
            StoryTemplate.Layout.CLASSIC -> {
                val barY = HEIGHT - 470f
                bar(barY, 12f)
                if (stats.isNotEmpty()) {
                    val col = width / stats.size
                    stats.forEachIndexed { i, (v, label) ->
                        val x = margin + col * i + col / 2
                        text(c, v, x, barY + 60, 66f, fg, bold, Paint.Align.CENTER)
                        text(c, label, x, barY + 150, 34f, soft, semibold, Paint.Align.CENTER)
                    }
                }
            }
            StoryTemplate.Layout.MINIMAL -> {
                text(c, stats.joinToString("  ·  ") { "${it.first} ${it.second}" }, margin, HEIGHT - 330f, 40f, fg, semibold)
                bar(HEIGHT - 260f, 6f)
            }
            StoryTemplate.Layout.BIG -> {
                stats.firstOrNull()?.let { (v, label) ->
                    text(c, v, margin, HEIGHT - 640f, 190f, fg, bold)
                    text(c, label.uppercase(), margin, HEIGHT - 410f, 40f, opaque(t.accent), bold, spacing = 0.15f)
                }
                text(c, stats.drop(1).joinToString("   ") { "${it.first} ${it.second}" }, margin, HEIGHT - 340f, 42f, soft, semibold)
                bar(HEIGHT - 260f, 10f)
            }
        }
        if (t.showBrand) text(c, "ARAVIA", WIDTH / 2f, HEIGHT - 170f, 34f, if (t.shades) 0xFFE9D9B8.toInt() else fg, bold, Paint.Align.CENTER, 0.3f)
        return out
    }

    // The ending

    /** Front-facing arara landing on its branch and revealing the workout numbers on its open wing
     *  (ported from iOS AraraFinale.swift). */
    private fun finale(map: Bitmap, t: Double, stats: List<Pair<String, String>>): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        AraraFinale.draw(
            canvas = c,
            width = WIDTH,
            height = HEIGHT,
            t = t,
            map = map,
            ink = opaque(template.ink),
            stats = stats,
            showBrand = template.showBrand,
            semibold = semibold,
        )
        return out
    }

    // The video

    /** Renders the video into the app's cache and returns the file. `progress` goes from 0 to 1. */
    suspend fun render(progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "videos").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // earlier videos never pile up
        val file = File(dir, "aravia-${System.currentTimeMillis() / 1000}.mp4")
        val introFrames = (INTRO * FPS).toInt()
        val playFrames = (template.seconds * FPS).toInt()
        val outroFrames = (OUTRO * FPS).toInt()
        val total = introFrames + playFrames + outroFrames
        val encoder = Encoder(file, total)
        try {
            var heading = heading(0.0, null)
            val first = compose(snapshot(follow(0.0, heading), 0.0), 0.0)
            repeat(introFrames) { encoder.frame(first); progress(encoder.frames / total.toFloat()) }
            val step = duration / max(playFrames - 1, 1)
            for (i in 0 until playFrames) {
                if (cancelled) throw kotlinx.coroutines.CancellationException()
                val elapsed = min(duration, i * step)
                heading = heading(elapsed, heading)
                encoder.frame(compose(snapshot(follow(elapsed, heading), elapsed), elapsed))
                progress(encoder.frames / total.toFloat())
                yield()
            }
            val overview = snapshot(overview(), duration)
            val stats = stats()
            for (i in 0 until outroFrames) {
                if (cancelled) throw kotlinx.coroutines.CancellationException()
                encoder.frame(finale(overview, i / FPS.toDouble(), stats))
                progress(encoder.frames / total.toFloat())
                yield()
            }
            encoder.finish()
            return file
        } catch (e: Throwable) {
            encoder.abort()
            file.delete()
            throw e
        } finally {
            snapshotter?.cancel()
            snapshotter = null
        }
    }

    /**
     * H.264 through the encoder's input surface, and silent AAC, into an MP4. Frames are drawn on
     * the surface as they are ready, so their presentation times are set from the frame count, not
     * from when they were drawn.
     */
    private class Encoder(file: File, private val totalFrames: Int) {
        private val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        private val surface: android.view.Surface
        private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val info = MediaCodec.BufferInfo()
        private var videoTrack = -1
        private var started = false
        private var written = 0L
        var frames = 0; private set
        private val audioSamples = mutableListOf<Pair<java.nio.ByteBuffer, MediaCodec.BufferInfo>>()
        private var audioFormat: MediaFormat? = null

        init {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            video.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = video.createInputSurface()
            video.start()
            encodeSilence(totalFrames.toDouble() / FPS)
        }

        /** The whole sound track up front: silence as long as the picture, AAC-LC stereo 44.1 kHz. */
        private fun encodeSilence(seconds: Double) {
            val rate = 44_100
            val aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aac.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aac.start()
            val totalSamples = (seconds * rate).toLong()
            var fed = 0L
            var inputDone = false
            val out = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val i = aac.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = aac.getInputBuffer(i)!!
                        buf.clear()
                        val count = min(1024L, totalSamples - fed).toInt()
                        if (count <= 0) {
                            aac.queueInputBuffer(i, 0, 0, fed * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(ByteArray(count * 4))
                            aac.queueInputBuffer(i, 0, count * 4, fed * 1_000_000 / rate, 0)
                            fed += count
                        }
                    }
                }
                val o = aac.dequeueOutputBuffer(out, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) audioFormat = aac.outputFormat
                else if (o >= 0) {
                    if (out.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && out.size > 0) {
                        val copy = java.nio.ByteBuffer.allocate(out.size)
                        copy.put(aac.getOutputBuffer(o)!!.apply { position(out.offset); limit(out.offset + out.size) })
                        copy.flip()
                        audioSamples += copy to MediaCodec.BufferInfo().apply { set(0, out.size, out.presentationTimeUs, out.flags) }
                    }
                    aac.releaseOutputBuffer(o, false)
                    if (out.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            aac.stop(); aac.release()
        }

        fun frame(bitmap: Bitmap) {
            val canvas = surface.lockHardwareCanvas()
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
            frames++
            drain(false)
        }

        private fun drain(end: Boolean) {
            while (true) {
                val o = video.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(video.outputFormat)
                        val audio = audioFormat?.let { muxer.addTrack(it) }
                        muxer.start()
                        started = true
                        if (audio != null) for ((buf, i) in audioSamples) muxer.writeSampleData(audio, buf, i)
                    }
                    o >= 0 -> {
                        val buf = video.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && started) {
                            info.presentationTimeUs = written * 1_000_000L / FPS
                            written++
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        video.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun finish() {
            video.signalEndOfInputStream()
            drain(true)
            close()
        }

        fun abort() = runCatching { close() }

        private fun close() {
            runCatching { video.stop() }; runCatching { video.release() }; surface.release()
            runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }
        }
    }
}
