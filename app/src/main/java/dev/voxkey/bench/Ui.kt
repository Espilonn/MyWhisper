package dev.voxkey.bench

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Palette sombre inspirée de la référence : fond quasi noir, cartes relevées, accent jaune voxkey. */
object Ui {
    const val BG = 0xFF0B0C0F.toInt()
    const val CARD = 0xFF16181E.toInt()
    const val CARD_HI = 0xFF23262E.toInt()
    const val LINE = 0xFF262A33.toInt()
    const val INK = 0xFFF3F4F6.toInt()
    const val MUTED = 0xFF8A909B.toInt()
    const val ACCENT = 0xFFFFD400.toInt()
    const val ON_ACCENT = 0xFF14161B.toInt()
    const val GOOD = 0xFF5BD67A.toInt()
    const val WARN = 0xFFFF9A5C.toInt()
    const val GOOD_BG = 0x2235D67A
    const val WARN_BG = 0x22FF9A5C
}

/** Ajoute une vue pleine largeur, avec une marge au-dessus (en dp). */
fun LinearLayout.addFull(v: View, topDp: Int = 0) {
    val d = context.resources.displayMetrics.density
    addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (topDp * d).toInt() })
}

/** Filet de séparation de 1 px-dp, à l'intérieur d'une carte. */
fun LinearLayout.addRule(topDp: Int = 0) {
    val d = context.resources.displayMetrics.density
    addView(View(context).apply { setBackgroundColor(Ui.LINE) },
        LinearLayout.LayoutParams(MATCH, (1 * d).toInt()).apply { topMargin = (topDp * d).toInt() })
}

/** Briques d'interface partagées. Tout est construit en code, sans XML de mise en page. */
class UiKit(private val ctx: Context) {

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    fun text(t: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        this.text = t
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.CARD, 20)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    fun icon(res: Int, color: Int, sizeDp: Int = 18) = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Titre de section : petite icône + libellé, comme « Active fleet » dans la référence. */
    fun sectionTitle(res: Int, label: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(res, Ui.MUTED, 16))
        addView(text(label, 13f, Ui.MUTED, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
    }

    /** Pastille : badge de variation, étiquette, état. */
    fun pill(label: String, color: Int, bg: Int) = text(label, 12f, color, bold = true).apply {
        background = rounded(bg, 10)
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

    fun sampleTag() = pill("Données d'exemple", Ui.MUTED, Ui.LINE)

    /** Ligne « libellé / valeur » dans une carte. */
    fun row(label: String, value: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.BG, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(text(label, 15f, Ui.INK, bold = true))
        addView(text(value, 13f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
    }

    /** Une dictée : heure, texte, nombre de mots. */
    fun dictationRow(d: SampleData.Dictation) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(text(d.time, 12f, Ui.MUTED))
            addView(text(d.text, 15f, Ui.INK).apply { setPadding(0, dp(2), 0, 0) })
        })
        addView(pill("${countWords(d.text)} mots", Ui.MUTED, Ui.LINE).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) }
        })
    }

    fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = if (primary) 15f else 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Ui.ON_ACCENT else Ui.INK)
        background = rounded(if (primary) Ui.ACCENT else Ui.CARD_HI, 14)
        stateListAnimator = null
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    /** Sélecteur segmenté (style Live / Replay de la référence). */
    fun segmented(labels: List<String>, initial: Int, onSelect: (Int) -> Unit): LinearLayout {
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Ui.BG, 14)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val items = labels.map { label ->
            text(label, 13f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                track.addView(this)
            }
        }
        fun paint(sel: Int) = items.forEachIndexed { i, tv ->
            val on = i == sel
            tv.background = if (on) rounded(Ui.CARD_HI, 11) else null
            tv.setTextColor(if (on) Ui.INK else Ui.MUTED)
            tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        paint(initial)
        items.forEachIndexed { i, tv -> tv.setOnClickListener { paint(i); onSelect(i) } }
        return track
    }
}
