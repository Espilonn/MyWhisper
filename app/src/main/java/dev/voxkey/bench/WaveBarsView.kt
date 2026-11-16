package dev.voxkey.bench

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Logo voxkey : cinq bandes verticales façon égaliseur.
 *
 * Au repos les bandes sont basses et fixes ; [active] les fait osciller en boucle.
 * Une seule [ValueAnimator] pour toutes les bandes, coupée net à l'arrêt : au repos
 * la vue ne redessine plus du tout (pas de CPU, pas de fuite).
 */
class WaveBarsView(context: Context) : View(context) {

    private companion object {
        const val IDLE = 0xFF8F97A6.toInt()
        const val IDLE_TOP = 0xFFBFC6D2.toInt()
        const val PERIOD_MS = 1150L

        /** Hauteur relative de chaque bande : plus haute au centre, comme une onde. */
        val WEIGHTS = floatArrayOf(0.50f, 0.82f, 1f, 0.72f, 0.42f)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private var anim: ValueAnimator? = null
    private var shaderForActive = false
    private var shader: Shader? = null
    private var shaderAccent = 0
    private var shaderInk = 0
    private var shaderLitInk = 0

    /**
     * L'accent est relu à chaque dessin : la bulle peut rester ouverte pendant que
     * l'utilisateur change la couleur de l'app, la dictée suivante suit le nouveau choix.
     */
    private fun accent(): Int = Accent.current(context)

    /** Encre imposée par le fond de la bulle ; null = teintes d'origine, prévues pour un fond sombre. */
    var ink: Int? = null
        set(v) {
            if (field == v) return
            field = v
            shader = null
            invalidate()
        }

    /** Encre des bandes actives quand le fond est déjà l'accent. */
    var litInk: Int? = null
        set(v) {
            if (field == v) return
            field = v
            shader = null
            invalidate()
        }

    var active = false
        set(on) {
            if (field == on) return
            field = on
            shader = null
            if (on || busy) start() else stop()
        }

    /** Transcription en cours : les bandes défilent de gauche à droite, sans écoute. */
    var busy = false
        set(on) {
            if (field == on) return
            field = on
            shader = null
            if (on || active) start() else stop()
        }

    private fun stop() {
        anim?.cancel()
        anim = null
        phase = 0f
        invalidate()
    }

    private fun start() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val n = WEIGHTS.size
        // n barres + (n-1) gouttières de 0,55 barre
        val barW = w / (n + (n - 1) * 0.55f)
        val gap = barW * 0.55f
        val maxH = h * 0.94f
        val minH = h * 0.24f

        val lit = active || busy
        val accent = accent()
        val idleInk = ink
        val activeInk = litInk
        if (shader == null || shaderForActive != lit || shaderAccent != accent ||
            shaderInk != (idleInk ?: 0) || shaderLitInk != (activeInk ?: 0)
        ) {
            shaderForActive = lit
            shaderAccent = accent
            shaderInk = idleInk ?: 0
            shaderLitInk = activeInk ?: 0
            shader = LinearGradient(
                0f, (h - maxH) / 2f, 0f, (h + maxH) / 2f,
                when {
                    lit && activeInk != null -> Accent.light(activeInk)
                    lit -> Accent.light(accent)
                    idleInk != null -> Accent.light(idleInk, 0.45f)
                    else -> IDLE_TOP
                },
                when {
                    lit && activeInk != null -> activeInk
                    lit -> accent
                    idleInk != null -> idleInk
                    else -> IDLE
                },
                Shader.TileMode.CLAMP
            )
        }
        paint.shader = shader
        val radius = barW / 2f

        for (i in 0 until n) {
            val wave = when {
                busy -> 0.5f + 0.5f * Math.sin((phase * 2f - i * 0.9f).toDouble()).toFloat()
                active -> 0.5f + 0.5f * Math.sin((phase + i * 1.15f).toDouble()).toFloat()
                else -> 0.28f
            }
            val bh = minH + (maxH - minH) * WEIGHTS[i] * wave
            val left = i * (barW + gap)
            val top = (h - bh) / 2f
            rect.set(left, top, left + barW, top + bh)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
