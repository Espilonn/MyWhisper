package dev.voxkey.bench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Aperçu de la position de la bulle dans le clavier.
 *
 * Un clavier factice aux proportions de Gboard suffit : mêmes rangées de touches, même barre
 * du bas (espace large), et la bulle posée avec exactement le calcul du service. L'aperçu
 * prend la couleur que la bulle aurait (clavier lu, sombre ou accent).
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

    /** Proportions du vrai clavier : 1080 × 883 px sur le téléphone de référence. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * KB_H / KB_W).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val scale = w / KB_W

        paint.color = Ui.CARD_HI
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, dp(16f), dp(16f), paint)

        // Bande de suggestions (les trois mots proposés par Gboard), puis quatre rangées de
        // touches qui remplissent la hauteur jusqu'au bas de l'écran.
        paint.color = Ui.LINE
        val stripH = 118f * scale
        repeat(3) { i ->
            val w3 = (w - 120f * scale) / 3f
            val l = 30f * scale + i * (w3 + 30f * scale)
            rect.set(l, stripH * 0.28f, l + w3, stripH * 0.72f)
            canvas.drawRoundRect(rect, stripH * 0.22f, stripH * 0.22f, paint)
        }

        val padX = 12f * scale
        val gap = 12f * scale
        val rowGap = 10f * scale
        // Espace libre sous la dernière rangée, comme sur Gboard (barre de navigation du
        // téléphone), à la moitié de la place qu'il occupait avant.
        val bottomPad = 140f * scale
        val keyH = (h - stripH - rowGap * 3f - bottomPad) / 4f
        val rows = listOf(
            FloatArray(10) { 1f },
            FloatArray(10) { 1f },
            FloatArray(10) { 1f },
            floatArrayOf(1.5f, 1f, 1f, 4.5f, 1f, 1.5f),
        )
        var y = stripH
        for (weights in rows) {
            drawRow(canvas, weights, padX, w - padX, y, keyH, gap)
            y += keyH + rowGap
        }

        // La bulle : mêmes fractions, même ancrage en bas que [OverlayService.placeAttached].
        val bw = dp(56f) * scale
        val bh = dp(44f) * scale
        val inset = dp(4f) * scale
        val travel = h - inset - bh
        val left = bx * (w - bw)
        val top = travel - (1f - by) * travel
        paint.color = Bubble.background(context)
        rect.set(left, top, left + bw, top + bh)
        canvas.drawRoundRect(rect, dp(9f) * scale, dp(9f) * scale, paint)

        // Cinq bandes, comme la vraie touche, dans l'encre du fond choisi.
        paint.color = Bubble.idleInk(context) ?: 0xFFBFC6D2.toInt()
        val bars = floatArrayOf(0.5f, 0.82f, 1f, 0.72f, 0.42f)
        val barW = bw * 0.055f
        val gap2 = barW * 0.55f
        val total = bars.size * barW + (bars.size - 1) * gap2
        var x = left + (bw - total) / 2f
        for (bar in bars) {
            val height = bh * 0.62f * bar
            val barTop = top + (bh - height) / 2f
            rect.set(x, barTop, x + barW, barTop + height)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            x += barW + gap2
        }
    }

    /** Une rangée de touches, largeurs relatives dans [weights]. */
    private fun drawRow(
        canvas: Canvas,
        weights: FloatArray,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
        gap: Float,
    ) {
        val usable = right - left - gap * (weights.size - 1)
        val unit = usable / weights.sum()
        var x = left
        for (weight in weights) {
            val kw = unit * weight
            rect.set(x, top, x + kw, top + height)
            canvas.drawRoundRect(rect, 5f * (height / 118f), 5f * (height / 118f), paint)
            x += kw + gap
        }
    }

    companion object {
        /** Position historique de la bulle, en fraction de la largeur du clavier. */
        const val DEFAULT_X = 0.21f

        /** Taille du clavier de référence, en pixels : 1080 × 883 chez Gboard en portrait. */
        private const val KB_W = 1080f
        private const val KB_H = 883f
    }
}
