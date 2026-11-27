package app.aravia.arara

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import app.aravia.ui.Aravia
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Living 60 fps spring-physics rigged Arara (matching aravia.app's AraraEngine):
 * - Hierarchical bone kinematics (feet -> body -> tail/wing/head -> hinged lower jaw + eye -> pupil)
 * - 2nd-order damped springs for volume-preserving squash & stretch, hop, and tail follow-through
 * - Organic non-periodic double-blinks, Duolingo-style idle micro-saccades, and tap gaze-tracking
 * - Anticipation crouch -> joyful hop + asymmetric wing flap + open-beak chirp + curved macaw feathers
 * - Peaceful resting mode (synced automatically with AraraCare): twilight aura, curved sleep eyelids,
 *   slow breathing, and floating `z` motes
 * - Warm Aravia sun-disc halo & ambient forest motes tailored to each growth stage
 */
@Composable
fun AraraView(
    stage: AraraStage,
    modifier: Modifier = Modifier,
    celebratedAt: Long? = null,
    resting: Boolean? = null,
) {
    val careState by AraraCare.state.collectAsState()
    val isResting = resting ?: careState.resting
    val lightCard = Aravia.colors.surface.luminance() > 0.5f
    val haptics = LocalHapticFeedback.current

    val sim = remember { AraraRigSim(stage) }
    var frameNanos by remember { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(stage) { sim.onStageChanged(stage) }
    LaunchedEffect(isResting) { sim.setResting(isResting) }
    LaunchedEffect(celebratedAt) {
        if (celebratedAt != null) sim.onExternalCelebrate(celebratedAt)
    }

    LaunchedEffect(Unit) {
        var prev = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - prev) / 1e9f).coerceIn(0.004f, 0.045f)
            prev = now
            sim.step(dt, now / 1e9f)
            frameNanos = now
        }
    }

    Canvas(
        modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val side = minOf(size.width, size.height).coerceAtLeast(1).toFloat()
                        val gx = ((offset.x - (size.width - side) / 2f) / side) * 200f
                        val gy = ((offset.y - (size.height - side) / 2f) / side) * 200f
                        sim.lookAt(gx, gy)
                    },
                    onTap = { offset ->
                        val side = minOf(size.width, size.height).coerceAtLeast(1).toFloat()
                        val gx = ((offset.x - (size.width - side) / 2f) / side) * 200f
                        val gy = ((offset.y - (size.height - side) / 2f) / side) * 200f
                        sim.lookAt(gx, gy)
                        sim.celebrate(isEvolution = false)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                )
            },
    ) {
        // Read frameNanos so Compose redraws every frame cleanly.
        val t = (frameNanos / 1e9).toFloat()
        val side = minOf(size.width, size.height)
        translate((size.width - side) / 2f, (size.height - side) / 2f) {
            scale(side / 200f, side / 200f, pivot = Offset.Zero) {
                drawAraraScene(sim, stage, lightCard, t)
            }
        }
    }
}

private data class StagePalette(
    val sun: Color,
    val rim: Color,
    val sunRadius: Float,
    val motes: List<Color>,
)

private fun stagePalette(stage: AraraStage): StagePalette = when (stage) {
    AraraStage.EGG -> StagePalette(
        sun = Color(0xFFE3B86A), rim = Color(0xFFF5D99B), sunRadius = 54f,
        motes = listOf(Color(0xFFE9D9B8), Color(0xFFE9B43A)),
    )
    AraraStage.CHICK -> StagePalette(
        sun = Color(0xFFE8A644), rim = Color(0xFFF8D98C), sunRadius = 58f,
        motes = listOf(Color(0xFFE9B43A), Color(0xFFE05A3A)),
    )
    AraraStage.YOUNG -> StagePalette(
        sun = Color(0xFFDE952B), rim = Color(0xFFF5C26B), sunRadius = 62f,
        motes = listOf(Color(0xFFE05A3A), Color(0xFFE9B43A), Color(0xFF4EA3E8)),
    )
    AraraStage.ADULT -> StagePalette(
        sun = Color(0xFFD98E1F), rim = Color(0xFFFAD078), sunRadius = 65f,
        motes = listOf(Color(0xFFD44D30), Color(0xFFE9B43A), Color(0xFF3A7CC8)),
    )
    AraraStage.ROYAL -> StagePalette(
        sun = Color(0xFFE5A024), rim = Color(0xFFFFE091), sunRadius = 69f,
        motes = listOf(Color(0xFFE9B43A), Color(0xFF3CA06B), Color(0xFF4EA3E8), Color(0xFFD44D30)),
    )
    AraraStage.LEGEND -> StagePalette(
        sun = Color(0xFFF2B632), rim = Color(0xFFFFF0B3), sunRadius = 73f,
        motes = listOf(Color(0xFFFFE082), Color(0xFFE9B43A), Color(0xFF4EA3E8), Color(0xFF3CA06B), Color(0xFFFF6B4A)),
    )
}

private class Spring(
    var v: Float = 0f,
    val k: Float = 220f,
    val d: Float = 18f,
) {
    var vel: Float = 0f
    var target: Float = v

    fun step(dt: Float): Float {
        val force = (target - v) * k
        val damp = -vel * d
        vel += (force + damp) * dt
        v += vel * dt
        return v
    }
}

private class FeatherParticle(
    var kind: Int, // 0 = feather, 1 = diamond spark, 2 = sleep Z
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var rot: Float,
    var vrot: Float,
    var flutter: Float,
    var flutterSpeed: Float,
    var size: Float,
    var color: Color,
    var life: Float = 1f,
    var decay: Float = 0.014f,
)

private class WaveRing(
    val isHalo: Boolean,
    val x: Float,
    val y: Float,
    var r: Float,
    val maxR: Float,
    var delay: Float,
    val color: Color,
    var life: Float = 1f,
)

private class AmbientMote(
    var x: Float,
    var y: Float,
    val r: Float,
    val phase: Float,
    val speed: Float,
    val vy: Float,
    val alpha: Float,
)

private class AraraRigSim(initialStage: AraraStage) {
    var stage: AraraStage = initialStage
        private set
    var resting: Boolean = false
        private set

    val pop = Spring(1f, 240f, 15f)
    val squash = Spring(1f, 310f, 16f)
    val hopY = Spring(0f, 270f, 16f)
    val tilt = Spring(0f, 130f, 14f)
    val headRot = Spring(0f, 115f, 13f)
    val wingRot = Spring(0f, 260f, 14f)
    val tailRot = Spring(0f, 75f, 9f)
    val jawRot = Spring(0f, 320f, 18f)
    val eggRot = Spring(0f, 210f, 11f)
    val pupilX = Spring(0f, 190f, 18f)
    val pupilY = Spring(0f, 190f, 18f)
    val sunX = Spring(0f, 60f, 12f)
    val sunY = Spring(0f, 60f, 12f)

    var eyeScaleY: Float = 1f
        private set
    var flapQueue: Int = 0
        private set

    private var flapClock = 0f
    private var chirpClock = 0f
    private var tapStreak = 0
    private var lastTapSec = -10f
    private var clockSec = 0f
    private var lastCelebratedAt: Long? = null

    private var blinkTimer = 1.6f + Random.nextFloat() * 2.2f
    private var blinkActive = false
    private var blinkPhase = 0f
    private var blinkDouble = false

    private var idleLookTimer = 1.8f + Random.nextFloat() * 2.4f
    private var lookTx = 0f
    private var lookTy = 0f
    private var touchLookUntil = -1f
    private var zTimer = 0.6f

    val particles = mutableListOf<FeatherParticle>()
    val waves = mutableListOf<WaveRing>()
    val motes = List(12) {
        AmbientMote(
            x = 18f + Random.nextFloat() * 164f,
            y = 18f + Random.nextFloat() * 164f,
            r = 1.0f + Random.nextFloat() * 1.8f,
            phase = Random.nextFloat() * (2f * PI.toFloat()),
            speed = 0.4f + Random.nextFloat() * 0.6f,
            vy = -(0.08f + Random.nextFloat() * 0.14f),
            alpha = 0.2f + Random.nextFloat() * 0.4f,
        )
    }

    fun onStageChanged(next: AraraStage) {
        if (next == stage) return
        stage = next
        pop.v = 0.76f
        pop.vel = 3.6f
        pop.target = 1f
        celebrate(isEvolution = true)
    }

    fun onExternalCelebrate(timestamp: Long) {
        if (timestamp == lastCelebratedAt) return
        lastCelebratedAt = timestamp
        pop.v = 0.80f
        pop.vel = 3.2f
        pop.target = 1f
        celebrate(isEvolution = true)
    }

    fun setResting(value: Boolean) {
        if (value == resting) return
        resting = value
        if (resting) {
            flapQueue = 0
            chirpClock = 0f
        } else {
            squash.vel += 1.6f
            wingRot.vel -= 55f
            blinkTimer = 0.25f
        }
    }

    fun lookAt(gx: Float, gy: Float) {
        if (resting) return
        val dx = (gx - 112f) / 68f
        val dy = (gy - 60f) / 68f
        val mag = hypot(dx, dy)
        val scale = if (mag > 1f) 1f / mag else 1f
        lookTx = dx * scale
        lookTy = dy * scale
        touchLookUntil = clockSec + 2.8f
    }

    fun celebrate(isEvolution: Boolean) {
        tapStreak = if (clockSec - lastTapSec < 1.25f) min(4, tapStreak + 1) else 1
        lastTapSec = clockSec

        if (stage == AraraStage.EGG) {
            squash.vel -= 2.3f
            hopY.vel -= 92f
            eggRot.vel += (if (Random.nextBoolean()) 1f else -1f) * 160f
            burstFeathers(0.8f, isEvolution)
            return
        }

        squash.v = 0.88f
        squash.vel = if (isEvolution) 3.1f else 2.3f
        hopY.vel = if (isEvolution) -148f else -108f
        tailRot.vel += if (isEvolution) 90f else 62f
        headRot.vel += if (tapStreak % 2 == 0) -46f else 40f
        flapQueue = if (isEvolution) 4 else 2 + min(2, tapStreak - 1)
        flapClock = 0f
        chirpClock = if (isEvolution) 0.42f else 0.32f

        burstFeathers(1f + tapStreak * 0.22f, isEvolution)
        emitChirp()
    }

    private fun burstFeathers(intensity: Float, isEvolution: Boolean) {
        val pal = stagePalette(stage)
        val count = if (isEvolution) 22 else (12 * intensity).toInt().coerceAtMost(24)
        for (i in 0 until count) {
            val angle = (2f * PI.toFloat() * i) / count + (Random.nextFloat() - 0.5f) * 0.45f
            val speed = (if (isEvolution) 1.8f else 1.2f) + Random.nextFloat() * (if (isEvolution) 2.2f else 1.6f)
            val isSpark = isEvolution && i % 3 == 0
            particles += FeatherParticle(
                kind = if (isSpark) 1 else 0,
                x = 100f + (Random.nextFloat() - 0.5f) * 18f,
                y = 96f + (Random.nextFloat() - 0.5f) * 18f,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed - (if (isEvolution) 1.2f else 0.75f),
                rot = angle,
                vrot = (Random.nextFloat() - 0.5f) * 0.11f,
                flutter = Random.nextFloat() * 6.28f,
                flutterSpeed = 4.2f + Random.nextFloat() * 3.8f,
                size = if (isSpark) 3.2f + Random.nextFloat() * 2.5f else 8.5f + Random.nextFloat() * 6.5f,
                color = pal.motes[i % pal.motes.size],
                life = 1f,
                decay = if (isSpark) 0.024f else 0.012f + Random.nextFloat() * 0.006f,
            )
        }
        if (isEvolution) {
            waves += WaveRing(
                isHalo = true, x = 102f, y = 96f, r = 18f, maxR = 86f,
                delay = 0f, color = pal.rim, life = 1f,
            )
        }
    }

    private fun emitChirp() {
        val bx = if (stage == AraraStage.CHICK) 138f else 142f
        val by = if (stage == AraraStage.CHICK) 80f else 56f
        for (i in 0 until 2) {
            waves += WaveRing(
                isHalo = false, x = bx, y = by,
                r = 6f + i * 5f, maxR = 32f + i * 9f,
                delay = i * 0.11f, color = Color(0xFFF6CE7C), life = 1f,
            )
        }
    }

    private fun emitSleepZ() {
        particles += FeatherParticle(
            kind = 2,
            x = 128f + (Random.nextFloat() - 0.5f) * 10f,
            y = 48f,
            vx = 0.14f + Random.nextFloat() * 0.14f,
            vy = -(0.28f + Random.nextFloat() * 0.14f),
            rot = 0f,
            vrot = 0f,
            flutter = Random.nextFloat() * 6.28f,
            flutterSpeed = 2f,
            size = 9f + Random.nextFloat() * 4f,
            color = Color(0xFFE9D9B8),
            life = 1f,
            decay = 0.009f,
        )
    }

    fun step(dt: Float, t: Float) {
        clockSec = t

        // Idle Duolingo-style micro-saccades when no recent touch target
        if (!resting && t > touchLookUntil) {
            idleLookTimer -= dt
            if (idleLookTimer <= 0f) {
                idleLookTimer = 1.7f + Random.nextFloat() * 3.0f
                val glance = if (Random.nextFloat() < 0.34f) 0f else 1f
                lookTx = glance * (Random.nextFloat() - 0.38f) * 0.92f
                lookTy = glance * (Random.nextFloat() - 0.5f) * 0.65f
            }
        }
        val lx = if (resting) 0f else lookTx
        val ly = if (resting) 0f else lookTy

        pupilX.target = lx * 1.35f
        pupilY.target = ly * 1.25f
        sunX.target = lx * -4.2f
        sunY.target = ly * -3.2f

        val breathFreq = if (resting) 1.45f else 2.35f
        val breath = sin(t * breathFreq)
        val breathOverlap = sin(t * breathFreq - 0.75f)

        squash.target = 1f + breath * (if (resting) 0.016f else 0.013f)
        hopY.target = 0f
        tilt.target = if (resting) -2.2f else lx * 2.4f
        headRot.target = if (resting) {
            -9.5f + breath * 1.1f
        } else {
            ly * 5.0f + lx * 3.2f + sin(t * 0.9f) * 1.4f
        }

        if (flapQueue > 0) {
            flapClock += dt * 15.5f
            val w = sin(flapClock)
            wingRot.target = if (w > 0f) -24f * w else 5f * w
            if (flapClock >= 2f * PI.toFloat()) {
                flapClock -= 2f * PI.toFloat()
                flapQueue--
            }
        } else {
            wingRot.target = if (resting) 1.2f + breath * 1.3f else -1.5f + breathOverlap * 3.4f
        }

        tailRot.target = (if (resting) 1.5f else 0f) +
            sin(t * (if (resting) 1.1f else 1.9f) - 1.15f) * (if (resting) 1.6f else 3.2f) +
            hopY.vel * 0.045f -
            wingRot.v * 0.16f

        if (chirpClock > 0f) {
            chirpClock = max(0f, chirpClock - dt)
            jawRot.target = if (sin((chirpClock / 0.34f) * 2f * PI.toFloat()) > -0.2f) 17f else 3f
        } else {
            jawRot.target = if (resting) 0f else max(0f, breath * 0.9f)
        }

        eggRot.target = if (stage == AraraStage.EGG) {
            sin(t * (if (resting) 1.2f else 2.6f)) * (if (resting) 1.4f else 3.2f)
        } else 0f

        pop.step(dt)
        squash.step(dt)
        hopY.step(dt)
        tilt.step(dt)
        headRot.step(dt)
        wingRot.step(dt)
        tailRot.step(dt)
        jawRot.step(dt)
        eggRot.step(dt)
        pupilX.step(dt)
        pupilY.step(dt)
        sunX.step(dt)
        sunY.step(dt)

        // Organic non-periodic blinking + double-blinks
        eyeScaleY = if (resting) {
            0.05f
        } else if (!blinkActive) {
            blinkTimer -= dt
            if (blinkTimer <= 0f) {
                blinkActive = true
                blinkPhase = 0f
                blinkDouble = Random.nextFloat() < 0.24f
            }
            1f
        } else {
            blinkPhase += dt
            val p = blinkPhase / 0.155f
            if (p >= 1f) {
                blinkActive = false
                if (blinkDouble) {
                    blinkDouble = false
                    blinkTimer = 0.11f
                } else {
                    blinkTimer = 2.3f + Random.nextFloat() * 3.5f
                }
                1f
            } else {
                val closed = if (p < 0.4f) p / 0.4f else 1f - (p - 0.4f) / 0.6f
                max(0.06f, 1f - closed * 0.94f)
            }
        }

        // Update ambient motes
        val stepScale = dt * 60f
        for (m in motes) {
            m.y += m.vy * (if (resting) 0.45f else 1f) * stepScale
            m.x += sin(t * m.speed + m.phase) * 0.16f * stepScale
            if (m.y < 6f) { m.y = 194f; m.x = 16f + Random.nextFloat() * 168f }
            if (m.x < 6f) m.x = 194f
            if (m.x > 194f) m.x = 6f
        }

        if (resting) {
            zTimer += dt
            if (zTimer > 1.55f) {
                zTimer = 0f
                emitSleepZ()
            }
        } else {
            zTimer = 0.6f
        }

        // Update waves
        val wIter = waves.iterator()
        while (wIter.hasNext()) {
            val wv = wIter.next()
            if (wv.delay > 0f) {
                wv.delay -= dt
                continue
            }
            wv.r += (wv.maxR - wv.r) * min(1f, dt * (if (wv.isHalo) 5.2f else 6.4f))
            wv.life -= dt * (if (wv.isHalo) 1.7f else 2.1f)
            if (wv.life <= 0.02f) wIter.remove()
        }

        // Update aerodynamic feathers, sparks & sleep Z's
        val pIter = particles.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            when (p.kind) {
                2 -> { // sleep Z
                    p.x += (p.vx + sin(t * 2f + p.flutter) * 0.1f) * stepScale
                    p.y += p.vy * stepScale
                    p.life -= p.decay * stepScale
                }
                1 -> { // diamond spark
                    p.x += p.vx * stepScale
                    p.y += p.vy * stepScale
                    p.vx *= 0.95f
                    p.vy = p.vy * 0.95f + 0.025f * stepScale
                    p.life -= p.decay * stepScale
                }
                else -> { // aerodynamic feather
                    p.flutter += p.flutterSpeed * dt
                    p.x += (p.vx + sin(p.flutter) * 0.36f) * stepScale
                    p.y += p.vy * stepScale
                    p.vx *= 0.962f
                    p.vy = min(0.75f, p.vy * 0.96f + 0.032f * stepScale)
                    p.rot += p.vrot * stepScale
                    p.life -= p.decay * stepScale
                }
            }
            if (p.life <= 0.02f) pIter.remove()
        }
    }
}

private val sleepZPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.MONOSPACE
    isFakeBoldText = true
}

private fun DrawScope.drawAraraScene(sim: AraraRigSim, stage: AraraStage, lightCard: Boolean, t: Float) {
    val pal = stagePalette(stage)
    val layers = AraraShapes.layers(stage)

    // 1. Warm Aravia sun-disc halo behind the bird (shifts to twilight glow while resting)
    val sunCenter = Offset(104f + sim.sunX.v, 92f + sim.sunY.v)
    val sunPulse = 1f + sin(t * 1.4f) * 0.025f + (if (sim.flapQueue > 0) 0.04f else 0f)
    val discColor = if (sim.resting) Color(0xFF4F82B8) else pal.sun
    val rimColor = if (sim.resting) Color(0xFF8BB7E3) else pal.rim
    val baseAlpha = if (lightCard) 0.22f else 0.32f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(rimColor.copy(alpha = baseAlpha * 1.15f), discColor.copy(alpha = baseAlpha * 0.55f), Color.Transparent),
            center = sunCenter,
            radius = (pal.sunRadius + 22f) * sunPulse,
        ),
        radius = (pal.sunRadius + 22f) * sunPulse,
        center = sunCenter,
    )
    drawCircle(
        color = discColor.copy(alpha = if (lightCard) 0.14f else 0.19f),
        radius = (pal.sunRadius - 6f) * sunPulse,
        center = sunCenter,
    )

    // 2. Ambient floating pollen/firefly motes behind/around habitat
    val moteBoost = when (stage) {
        AraraStage.LEGEND -> 1.35f
        AraraStage.ROYAL -> 1.15f
        else -> 0.8f
    }
    sim.motes.forEachIndexed { idx, m ->
        val twinkle = 0.45f + 0.55f * sin(t * 2.1f + m.phase)
        val a = (m.alpha * twinkle * moteBoost * (if (lightCard) 0.7f else 1f)).coerceIn(0f, 0.85f)
        drawCircle(pal.motes[idx % pal.motes.size].copy(alpha = a), radius = m.r, center = Offset(m.x, m.y))
    }

    // 3. Hierarchical rigged Arara with volume-preserving squash & stretch
    val popScale = sim.pop.v.coerceIn(0.65f, 1.25f)
    val sqY = sim.squash.v.coerceIn(0.84f, 1.18f)
    val sqX = 1f / sqrt(sqY)
    val footPivot = Offset(102f, 146f)
    val headPivot = if (stage == AraraStage.CHICK) Offset(102f, 92f) else Offset(102f, 72f)
    val jawPivot = if (stage == AraraStage.CHICK) Offset(118f, 86f) else Offset(121f, 63f)
    val eyeCenter = if (stage == AraraStage.CHICK) Offset(111f, 78f) else Offset(113f, 55f)
    val wingPivot = Offset(101f, 89f)
    val tailPivot = Offset(92f, 134f)
    val eggPivot = Offset(100f, 144f)

    // Split fixed layers into back (before bird/egg) and front (nest front rim)
    var seenMovable = false
    val fixedBack = mutableListOf<Layer>()
    val fixedFront = mutableListOf<Layer>()
    val movable = mutableListOf<Layer>()
    for (l in layers) {
        if (l.role == Role.FIXED) {
            if (!seenMovable) fixedBack += l else fixedFront += l
        } else {
            seenMovable = true
            movable += l
        }
    }

    scale(popScale, popScale, pivot = Offset(100f, 100f)) {
        for (l in fixedBack) drawPath(l.path, l.color)

        withTransform({
            translate(0f, sim.hopY.v)
            rotate(sim.tilt.v, pivot = footPivot)
            scale(sqX, sqY, pivot = footPivot)
        }) {
            if (stage == AraraStage.EGG) {
                rotate(sim.eggRot.v, pivot = eggPivot) {
                    for (l in movable) drawPath(l.path, eggColour(l, lightCard))
                }
            } else {
                // Draw tail, body, wing, and parented head (with hinged jaw + tracking eye)
                for (l in movable) {
                    when (l.role) {
                        Role.TAIL -> rotate(sim.tailRot.v, pivot = tailPivot) { drawPath(l.path, l.color) }
                        Role.BODY -> drawPath(l.path, l.color)
                        Role.WING -> rotate(sim.wingRot.v, pivot = wingPivot) { drawPath(l.path, l.color) }
                        Role.HEAD -> rotate(sim.headRot.v, pivot = headPivot) {
                            if (l.rgb == 0x2B2522) {
                                rotate(sim.jawRot.v, pivot = jawPivot) { drawPath(l.path, l.color) }
                            } else {
                                drawPath(l.path, l.color)
                            }
                        }
                        Role.EYE -> rotate(sim.headRot.v, pivot = headPivot) {
                            scale(1f, sim.eyeScaleY, pivot = eyeCenter) {
                                if (l.rgb == 0x111111 || l.rgb == 0xFFFFFF) {
                                    translate(sim.pupilX.v, sim.pupilY.v) { drawPath(l.path, l.color) }
                                } else {
                                    drawPath(l.path, l.color)
                                }
                            }
                        }
                        else -> drawPath(l.path, l.color)
                    }
                }

                // Live blinking + tracking baby eye for Filhote (chick)
                if (stage == AraraStage.CHICK) {
                    rotate(sim.headRot.v, pivot = headPivot) {
                        scale(1f, sim.eyeScaleY, pivot = eyeCenter) {
                            drawCircle(Color(0xFFF2D98A), radius = 3.2f, center = eyeCenter)
                            translate(sim.pupilX.v, sim.pupilY.v) {
                                drawCircle(Color(0xFF111111), radius = 2.0f, center = eyeCenter)
                                drawCircle(Color.White, radius = 0.75f, center = Offset(eyeCenter.x + 0.7f, eyeCenter.y - 0.8f))
                            }
                        }
                    }
                }

                // Peaceful curved sleeping eyelid when resting
                if (sim.resting) {
                    rotate(sim.headRot.v, pivot = headPivot) {
                        drawCircle(Color(0xFFF3ECE1), radius = 4.1f, center = eyeCenter)
                        val lid = Path().apply {
                            moveTo(eyeCenter.x - 3.3f, eyeCenter.y + 0.3f)
                            quadraticTo(eyeCenter.x, eyeCenter.y + 3.2f, eyeCenter.x + 3.3f, eyeCenter.y + 0.3f)
                        }
                        drawPath(lid, Color(0xFF2B2522), style = Stroke(width = 1.5f, cap = StrokeCap.Round))
                    }
                }
            }
        }

        for (l in fixedFront) drawPath(l.path, l.color)
    }

    // 4. Acoustic chirp arcs & golden evolution halo rings
    for (wv in sim.waves) {
        if (wv.delay > 0f) continue
        val a = (wv.life * 0.85f).coerceIn(0f, 1f)
        if (wv.isHalo) {
            drawCircle(wv.color.copy(alpha = a), radius = wv.r, center = Offset(wv.x, wv.y), style = Stroke(width = 2.2f))
        } else {
            drawArc(
                color = wv.color.copy(alpha = a),
                startAngle = -45f,
                sweepAngle = 78f,
                useCenter = false,
                topLeft = Offset(wv.x - wv.r, wv.y - wv.r),
                size = Size(wv.r * 2f, wv.r * 2f),
                style = Stroke(width = 2.2f, cap = StrokeCap.Round),
            )
        }
    }

    // 5. Aerodynamic curved macaw feathers, diamond sparks & peaceful sleep Z's
    for (p in sim.particles) {
        when (p.kind) {
            2 -> {
                val a = (sin(p.life.coerceIn(0f, 1f) * PI.toFloat()) * 0.85f).coerceIn(0f, 1f)
                sleepZPaint.color = android.graphics.Color.argb(
                    (a * 255).toInt(),
                    if (lightCard) 90 else 233,
                    if (lightCard) 76 else 217,
                    if (lightCard) 60 else 184,
                )
                sleepZPaint.textSize = p.size
                drawContext.canvas.nativeCanvas.drawText("z", p.x, p.y, sleepZPaint)
            }
            1 -> {
                val s = p.size * p.life
                rotate(45f, pivot = Offset(p.x, p.y)) {
                    drawRect(
                        color = p.color.copy(alpha = p.life.coerceIn(0f, 1f)),
                        topLeft = Offset(p.x - s / 2f, p.y - s / 2f),
                        size = Size(s, s),
                    )
                }
            }
            else -> drawMacawFeather(p)
        }
    }
}

private fun DrawScope.drawMacawFeather(p: FeatherParticle) {
    val len = p.size
    val wid = len * 0.34f
    val alpha = (p.life * 1.15f).coerceIn(0f, 1f)
    val deg = ((p.rot + sin(p.flutter) * 0.38f) * 180f / PI.toFloat())
    val vane = Path().apply {
        moveTo(-len * 0.55f, 0f)
        cubicTo(-len * 0.2f, -wid, len * 0.28f, -wid * 0.9f, len * 0.55f, 0f)
        cubicTo(len * 0.28f, wid * 0.85f, -len * 0.2f, wid, -len * 0.55f, 0f)
        close()
    }
    translate(p.x, p.y) {
        rotate(deg, pivot = Offset.Zero) {
            drawPath(vane, p.color.copy(alpha = alpha))
            drawLine(
                color = Color(0xFFFFFAF2).copy(alpha = alpha * 0.55f),
                start = Offset(-len * 0.65f, 0f),
                end = Offset(len * 0.44f, 0f),
                strokeWidth = 0.9f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** The egg takes the opposite of the card it sits on: cream on dark, dark with a light crack on
 *  light, as on iOS. */
private fun eggColour(layer: Layer, lightCard: Boolean): Color {
    if (layer.role != Role.EGG || !lightCard) return layer.color
    val rgb = when (layer.rgb) {
        0xC7A86A -> 0x14100C
        0xF1E6CF -> 0x3B302A
        0x8A6A3A -> 0xE9D9B8
        else -> layer.rgb
    }
    return Color(0xFF000000.toInt() or rgb).copy(alpha = layer.alpha)
}
