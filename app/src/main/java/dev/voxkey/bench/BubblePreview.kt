package dev.voxkey.bench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Aperçu de la position de la bulle dans le clavier.
 *
 * Un clavier factice aux mêmes proportions suffit : l'aperçu prend la couleur que la bulle
 * aurait (clavier lu, sombre ou accent) et se redessine à chaque déplacement du curseur.
 */
class BubblePreview(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    /** Fractions du clavier, 0..1, dans le même repère que [Bubble]. */
    var bx = DEFAULT_X
    var by = 1f

    private fun dp(v: Float) = v * density

    /** Relit la préférence ; sans réglage, montre la position d'origine du service. */
    fun refresh(ctx: Context) {
        bx = if (Bubble.custom(ctx)) Bubble.x(ctx) else DEFAULT_X
        by = if (Bubble.custom(ctx)) Bubble.y(ctx) else 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Clavier factice, proportions de Gboard en portrait (1080 × 883 environ).
        paint.color = Ui.CARD_HI
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, dp(16f), dp(16f), paint)

        // Quelques touches : la position de la bulle se lit d'un coup d'œil.
        paint.color = Ui.LINE
        val pad = dp(10f)
        val rowH = (h - pad * 2) / 4.6f
        for (r in 0 until 4) {
            val top = pad + r * (rowH + dp(5f))
            val cols = if (r < 3) 8 else 5
            val colW = (w - pad * 2) / cols
            for (c in 0 until cols) {
                rect.set(pad + c * colW + dp(2f), top, pad + c * colW + colW - dp(2f), top + rowH)
                canvas.drawRoundRect(rect, dp(5f), dp(5f), paint)
            }
        }

        // La bulle, à l'échelle du clavier factice : mêmes fractions que le service.
        val scale = w / resources.displayMetrics.widthPixels
        val bw = dp(56f) * scale
        val bh = dp(44f) * scale
        val left = bx * (w - bw)
        val bottom = h - dp(4f) * scale - bh
        val top = bottom - (1f - by) * bottom
        paint.color = Bubble.background(context)
        rect.set(left, top, left + bw, top + bh)
        canvas.drawRoundRect(rect, dp(9f) * scale, dp(9f) * scale, paint)

        // Cinq bandes, comme la vraie touche, dans l'encre du fond choisi.
        paint.color = Bubble.idleInk(context) ?: 0xFFBFC6D2.toInt()
        val bars = floatArrayOf(0.5f, 0.82f, 1f, 0.72f, 0.42f)
        val barW = bw * 0.055f
        val gap = barW * 0.55f
        val total = bars.size * barW + (bars.size - 1) * gap
        var x = left + (bw - total) / 2f
        for (bar in bars) {
            val height = bh * 0.62f * bar
            val barTop = top + (bh - height) / 2f
            rect.set(x, barTop, x + barW, barTop + height)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            x += barW + gap
        }
    }

    companion object {
        /** Position historique de la bulle, en fraction de la largeur du clavier. */
        const val DEFAULT_X = 0.21f
    }
}
