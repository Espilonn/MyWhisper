package dev.voxkey.bench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Graphique en points façon matrice de la référence : une colonne par jour, la dernière en jaune.
 * Ne dessine que les valeurs fournies par [setData]. Aucune animation.
 */
class DotBarsView(context: Context) : View(context) {

    private val rows = 10
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values = IntArray(0)

    fun setData(v: IntArray) {
        values = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty() || width == 0 || height == 0) return
        val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val cell = width.toFloat() / values.size
        val rowH = height.toFloat() / rows
        val dot = minOf(cell, rowH) * 0.6f
        values.forEachIndexed { col, v ->
            // nombre de points allumés, arrondi au supérieur, au moins 1 si la valeur est > 0
            val lit = (v * rows + max - 1) / max
            val cx = cell * col + cell / 2f
            for (r in 0 until rows) {
                paint.color = when {
                    r >= lit -> 0xFF2A2E36.toInt()
                    col == values.lastIndex -> Ui.ACCENT
                    else -> 0xFF8A909B.toInt()
                }
                canvas.drawCircle(cx, height - (r + 0.5f) * rowH, dot / 2f, paint)
            }
        }
    }
}
