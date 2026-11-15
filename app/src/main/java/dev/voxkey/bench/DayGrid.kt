package dev.voxkey.bench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Grille de jours : un carré par jour, intensité = mots dictés, appui = détail du jour.
 *
 * Deux dispositions, selon la fenêtre montrée :
 *  · [WEEK] et [MONTH] — sept colonnes de jours (lundi à gauche), initiales au-dessus, une
 *    ligne par semaine : une semaine se lit d'un trait, un mois tient en cinq lignes ;
 *  · [DENSE] — la même grille couchée : une colonne par semaine, une ligne par jour, carrés
 *    minuscules. Cent quatre-vingts jours en sept colonnes feraient vingt-six lignes, un mur
 *    qui ne tient pas dans une carte ; couchée, la bande tient en une centaine de dp.
 *
 * La largeur disponible est toujours remplie : le côté du carré s'en déduit, avec un plafond
 * pour ne pas devenir énorme sur une tablette.
 */
class DayGrid(context: Context) : View(context) {

    class Day(val date: LocalDate, val words: Int)

    private val density = resources.displayMetrics.density
    private val labelH = 18f * density

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

    /** Initiales des jours dans la langue du téléphone : « LMMJVSD » ou « MTWTFSS ». */
    private val initials = resources.getString(R.string.weekday_initials)

    private var days: List<Day> = emptyList()
    /** Colonne et ligne de chaque jour, dans l'ordre de [days]. */
    private var cols = IntArray(0)
    private var rows = IntArray(0)
    private var colCount = 1
    private var rowCount = 1
    private var mode = MONTH
    private var maxWords = 0
    private var selected = -1

    /** Appelé quand on appuie sur un carré. */
    var onPick: ((Day) -> Unit)? = null

    /** Une seule source de vérité pour l'intensité : cinq tons, du jour vide au jour plein. */
    private val ramp get() = Accent.ramp()

    private val dense get() = mode == DENSE
    private val turned get() = dense
    private val gap get() = (if (dense) 2 else 4) * density

    /**
     * [list] va du plus ancien au plus récent ; l'appelant fournit les dates, la vue n'en
     * invente aucune.
     */
    fun setData(list: List<Day>, mode: Int) {
        days = list
        this.mode = mode
        maxWords = list.maxOfOrNull { it.words } ?: 0
        cols = IntArray(list.size)
        rows = IntArray(list.size)
        // Décalage du premier jour dans sa semaine : la première ligne est partielle.
        val offset = if (list.isEmpty()) 0 else list.first().date.dayOfWeek.value - 1
        list.forEachIndexed { i, day ->
            val weekday = day.date.dayOfWeek.value - 1
            cols[i] = if (turned) (offset + i) / 7 else weekday
            rows[i] = if (turned) weekday else (offset + i) / 7
        }
        colCount = (cols.maxOrNull() ?: 0) + 1
        rowCount = (rows.maxOrNull() ?: 0) + 1
        selected = -1
        contentDescription = resources.getString(R.string.home_activity_desc, list.size)
        requestLayout()
        invalidate()
    }

    /** Sélectionne le dernier jour (aujourd'hui) sans notifier l'appelant. */
    fun selectLast() {
        selected = days.lastIndex
        invalidate()
    }

    private fun level(words: Int): Int = when {
        words <= 0 || maxWords <= 0 -> 0
        else -> (words * 4.0 / maxWords).toInt().coerceIn(1, 4)
    }

    /** Marge réservée au contour de sélection, qui déborde de 2 dp, plus un peu d'air. */
    private val pad = 6f * density

    /** Côté du carré : la grille remplit la largeur, plafonnée selon la disposition. */
    private fun cell(width: Int): Float {
        val cap = when (mode) {
            DENSE -> 14f
            WEEK -> 44f
            else -> 56f
        } * density
        return min((width - 2 * pad - (colCount - 1) * gap) / colCount, cap)
    }

    private fun left(c: Float): Float =
        pad + (width - 2 * pad - (colCount * c + (colCount - 1) * gap)) / 2f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val c = cell(width)
        val labels = if (turned) 0f else labelH
        setMeasuredDimension(
            width,
            (labels + 2 * pad + rowCount * c + (rowCount - 1) * gap).roundToInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val c = cell(width)
        val x0 = left(c)
        val radius = c * 0.22f
        val top = if (turned) pad else labelH + pad
        // Quatre tons calculés une fois par dessin, pas par carré.
        val tones = ramp

        // Initiales des jours au-dessus des sept colonnes (semaine et mois seulement).
        if (!turned) {
            val middle = (text.descent() + text.ascent()) / 2f
            initials.forEachIndexed { col, initial ->
                canvas.drawText(initial.toString(), x0 + col * (c + gap) + c / 2f, pad + labelH / 2f - middle, text)
            }
        }

        days.forEachIndexed { i, day ->
            val x = x0 + cols[i] * (c + gap)
            val y = top + rows[i] * (c + gap)
            rect.set(x, y, x + c, y + c)
            val level = level(day.words)
            fill.color = if (level == 0) Ui.OFF else tones[level - 1]
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (i == selected) {
                rect.inset(-2f * density, -2f * density)
                canvas.drawRoundRect(rect, radius + 2f * density, radius + 2f * density, ring)
            }
        }
    }

    /** Un appui choisit le carré le plus proche : la cible reste confortable même en six mois. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (days.isEmpty()) return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val c = cell(width)
        val pitch = c + gap
        val top = if (turned) pad else labelH + pad
        val col = ((event.x - left(c)) / pitch).roundToInt()
        val row = ((event.y - top) / pitch).roundToInt()
        val picked = days.indices.firstOrNull { cols[it] == col && rows[it] == row } ?: return true
        selected = picked
        invalidate()
        onPick?.invoke(days[picked])
        return true
    }

    companion object {
        /** Sept jours sur une ligne (Semaine). */
        const val WEEK = 0
        /** Sept colonnes de jours, une ligne par semaine (Mois). */
        const val MONTH = 1
        /** Grille couchée, carrés minuscules (six mois). */
        const val DENSE = 2
    }
}
