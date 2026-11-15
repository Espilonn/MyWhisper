package dev.voxkey.bench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Grille de contributions façon GitHub : un carré par jour, un écart régulier entre les
 * carrés, et une intensité qui dit le nombre de mots du jour.
 *
 * Mois : sept lignes (lundi en haut), une colonne par semaine, initiales du jour à gauche.
 * Semaine : une seule ligne de sept carrés plus grands, initiales en dessous.
 *
 * Pas de survol sur mobile : un appui sur un carré choisit le jour, l'écran affiche son détail.
 */
class DayGrid(context: Context) : View(context) {

    class Day(val date: LocalDate, val words: Int)

    private val density = resources.displayMetrics.density
    private val gap = 4f * density
    private val cellMonth = 22f * density
    private val cellWeek = 34f * density
    private val labelW = 18f * density
    private val labelH = 20f * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED
        textSize = 10f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Ui.INK
    }
    private val rect = RectF()

    private var days: List<Day> = emptyList()
    private var slots = IntArray(0)
    private var byWeek = false
    private var cols = 1
    private var max_words = 0
    private var selected = -1

    /** Appelé quand on appuie sur un carré. */
    var onPick: ((Day) -> Unit)? = null

    /** Une seule source de vérité pour l'intensité : cinq tons, du jour vide au jour plein. */
    private val ramp get() = Accent.ramp()

    /**
     * [list] va du plus ancien au plus récent ; l'appelant fournit les dates, la vue n'en
     * invente aucune.
     */
    fun setData(list: List<Day>, weekMode: Boolean) {
        days = list
        byWeek = weekMode
        max_words = list.maxOfOrNull { it.words } ?: 0
        if (list.isEmpty()) {
            slots = IntArray(0)
            cols = 1
        } else if (weekMode) {
            cols = 7
            slots = IntArray(list.size) { list[it].date.dayOfWeek.value - 1 }
        } else {
            // Décalage du premier jour dans sa semaine : la première colonne est partielle.
            val offset = list.first().date.dayOfWeek.value - 1
            cols = (offset + list.size + 6) / 7
            slots = IntArray(list.size) { (offset + it) / 7 }
        }
        selected = -1
        contentDescription = "Activité de la dictée sur ${list.size} jours"
        requestLayout()
        invalidate()
    }

    /** Sélectionne le dernier jour (aujourd'hui) sans notifier l'appelant. */
    fun selectLast() {
        selected = days.lastIndex
        invalidate()
    }

    private fun level(words: Int): Int = when {
        words <= 0 || max_words <= 0 -> 0
        else -> (words * 4.0 / max_words).toInt().coerceIn(1, 4)
    }

    private val cell get() = if (byWeek) cellWeek else cellMonth

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (byWeek) cell + gap + labelH else 7 * cell + 6 * gap
        setMeasuredDimension(width, h.roundToInt())
    }

    /** Coin gauche du bloc (initiales comprises), centré dans la largeur disponible. */
    private fun blockLeft(): Float {
        val blockW = if (byWeek) 0f else labelW
        val gridW = cols * cell + (cols - 1) * gap
        return (width - blockW - gridW) / 2f + blockW
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val cell = cell
        val left = blockLeft()
        val radius = cell * 0.22f
        // Quatre tons calculés une fois par dessin, pas par carré.
        val tones = ramp

        if (!byWeek) {
            // Initiales des jours à gauche, une par ligne.
            "LMMJVSD".forEachIndexed { row, c ->
                val y = row * (cell + gap) + cell / 2f - (text.descent() + text.ascent()) / 2f
                canvas.drawText(c.toString(), left - labelW / 2f, y, text)
            }
        }

        slots.forEachIndexed { i, col ->
            val row = if (byWeek) 0 else days[i].date.dayOfWeek.value - 1
            val x = left + col * (cell + gap)
            val y = row * (cell + gap)
            rect.set(x, y, x + cell, y + cell)
            val level = level(days[i].words)
            fill.color = if (level == 0) Ui.OFF else tones[level - 1]
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (i == selected) {
                rect.inset(-2f * density, -2f * density)
                canvas.drawRoundRect(rect, radius + 2f * density, radius + 2f * density, ring)
            }
            if (byWeek) {
                val cx = x + cell / 2f
                val cy = y + cell + gap + labelH / 2f - (text.descent() + text.ascent()) / 2f
                canvas.drawText(weekdayInitial(days[i].date), cx, cy, text)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (days.isEmpty()) return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val cell = cell
        val left = blockLeft()
        slots.forEachIndexed { i, col ->
            val row = if (byWeek) 0 else days[i].date.dayOfWeek.value - 1
            val x = left + col * (cell + gap)
            val y = row * (cell + gap)
            if (event.x >= x && event.x <= x + cell && event.y >= y && event.y <= y + cell) {
                selected = i
                invalidate()
                onPick?.invoke(days[i])
                return true
            }
        }
        return true
    }
}
