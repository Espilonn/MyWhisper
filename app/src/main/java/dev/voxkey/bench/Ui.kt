package dev.voxkey.bench

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * Système visuel MyWisper — thème unique sombre (la bulle de dictée est sombre elle aussi).
 *
 * Couleur    fond #0B0C0F · carte #15171D · carte haute #1E2128 · filet #262A33
 *            encre #F4F5F7 · secondaire #8F96A3 · pas d'accent fixe : [Accent] le porte
 *            succès #5BD67A · alerte #FF9060 (+ leurs fonds à 12 %)
 * Espace     4 · 8 · 12 · 16 · 20 · 24 · 32 · 48 — marge d'écran 20, rembourrage de carte 18
 * Type       affiche 52 · titre 26 · intertitre 17 · texte 15 · méta 13/12 · étiquette 11 capitales
 * Rayons     8 pastille · 14 champ/segment · 16 bouton · 20 carte · 24 dialogue
 * Élévation  par différence de ton (pas d'ombre) : trois surfaces suffisent à tout hiérarchiser.
 */
object Ui {
    const val BG = 0xFF0B0C0F.toInt()
    const val CARD = 0xFF15171D.toInt()
    const val CARD_HI = 0xFF1E2128.toInt()
    const val LINE = 0xFF262A33.toInt()
    const val INK = 0xFFF4F5F7.toInt()
    const val MUTED = 0xFF8F96A3.toInt()
    /** Jour sans dictée dans la grille : visible, mais éteint. */
    const val OFF = 0xFF232730.toInt()
    const val GOOD = 0xFF5BD67A.toInt()
    const val WARN = 0xFFFF9060.toInt()
    const val GOOD_BG = 0x1F5BD67A
    const val WARN_BG = 0x1FFF9060

    /** Accent choisi par l'utilisateur (préférence), et ses deux dérivés. */
    val ACCENT: Int get() = Accent.current()
    val ON_ACCENT: Int get() = Accent.on(ACCENT)
    val ACCENT_BG: Int get() = Accent.bg(ACCENT)
}

/** Ajoute une vue pleine largeur, avec une marge au-dessus (en dp). */
fun LinearLayout.addFull(v: View, topDp: Int = 0) {
    val d = context.resources.displayMetrics.density
    addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (topDp * d).toInt() })
}

/** Filet de séparation de 1 dp, à l'intérieur d'une carte. */
fun LinearLayout.addRule(topDp: Int = 0) {
    val d = context.resources.displayMetrics.density
    addView(View(context).apply { setBackgroundColor(Ui.LINE) },
        LinearLayout.LayoutParams(MATCH, (1 * d).toInt()).apply { topMargin = (topDp * d).toInt() })
}

/** Briques d'interface partagées. Tout est construit en code, sans XML de mise en page. */
class UiKit(private val ctx: Context) {

    init {
        // Premier contexte disponible : l'accent est lu ici pour toute la suite du process.
        Accent.current(ctx)
    }

    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)

    /** Un champ de dialogue : libellé, aide, valeur de départ, nombre de lignes. */
    class Field(
        val label: String,
        val hint: String = "",
        val initial: String = "",
        val lines: Int = 1,
    )

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    /** Fond cliquable : la forme arrondie, plus l'ondulation du thème par-dessus. */
    fun tappable(color: Int, radiusDp: Int): Drawable {
        val tv = TypedValue()
        val found = ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        val ripple = if (found && tv.resourceId != 0) ctx.getDrawable(tv.resourceId) else null
        val base = rounded(color, radiusDp)
        return if (ripple == null) base else LayerDrawable(arrayOf<Drawable>(base, ripple))
    }

    // ---------------------------------------------------------------- typographie

    /** Portrait chiffré d'un écran (le grand nombre de l'accueil). */
    fun display(t: String, color: Int = Ui.INK) = text(t, 52f, color).apply {
        typeface = light
        letterSpacing = -0.02f
    }

    /** Titre d'écran. */
    fun title(t: String) = text(t, 26f, Ui.INK).apply {
        typeface = medium
        letterSpacing = -0.01f
    }

    /** Intertitre : titre de carte, de ligne de liste. */
    fun heading(t: String, color: Int = Ui.INK) = text(t, 17f, color).apply { typeface = medium }

    /** Texte courant. */
    fun body(t: String, color: Int = Ui.MUTED) = text(t, 15f, color)

    /** Texte secondaire, deux crans sous l'encre. */
    fun meta(t: String, color: Int = Ui.MUTED) = text(t, 13f, color)

    fun micro(t: String, color: Int = Ui.MUTED) = text(t, 11f, color)

    /** Étiquette de section : capitales espacées, seul niveau de titre dans une carte. */
    fun label(t: String, color: Int = Ui.MUTED) = text(t.uppercase(), 11f, color).apply {
        typeface = medium
        letterSpacing = 0.12f
    }

    /** Brique de base ; les niveaux ci-dessus ne font que la régler. */
    fun text(t: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        this.text = t
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) medium else sans
        includeFontPadding = false
    }

    // ------------------------------------------------------------------- structure

    /** Socle d'écran : en-tête fixe, corps qui occupe la place restante. */
    class Page(val root: View, val body: LinearLayout)

    /**
     * Monte un écran complet. Mêmes marges pour les cinq onglets : 20 dp sur les côtés,
     * en-tête collé au bord haut, corps qui remplit tout ce qui reste.
     *
     * [scroll] à false pour un écran dont le corps se répartit (l'accueil) : ses cartes
     * peuvent alors prendre du poids et remplir la hauteur.
     */
    fun page(
        title: String,
        subtitle: String? = null,
        trailing: View? = null,
        scroll: Boolean = true,
    ): Page {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(14))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(this@UiKit.title(title))
                subtitle?.let {
                    addView(this@UiKit.body(it).apply { setPadding(0, dp(4), 0, 0) })
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            trailing?.let {
                addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
            }
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(16))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        if (scroll) {
            root.addView(ScrollView(ctx).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        } else {
            root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        return Page(root, body)
    }

    /** Surface de contenu : une seule matière pour toutes les cartes. */
    fun card(pad: Int = 18) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.CARD, 20)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    /** En-tête de carte : petites capitales, avec ou sans icône. */
    fun sectionTitle(res: Int, label: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(res, Ui.MUTED, 14))
        addView(this@UiKit.label(label).apply { setPadding(dp(8), 0, 0, 0) })
    }

    fun icon(res: Int, color: Int, sizeDp: Int = 18) = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Bouton d'en-tête : icône dans une pastille d'accent, cible de 48 dp. */
    fun iconButton(res: Int, desc: String, onClick: () -> Unit) = FrameLayout(ctx).apply {
        contentDescription = desc
        background = tappable(Ui.ACCENT, 99)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        addView(icon(res, Ui.ON_ACCENT, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    /** Repeint un bouton d'en-tête quand l'accent change, sans reconstruire l'écran. */
    fun retintIconButton(btn: View) {
        btn.background = tappable(Ui.ACCENT, 99)
        ((btn as? ViewGroup)?.getChildAt(0) as? ImageView)?.setColorFilter(Ui.ON_ACCENT)
    }

    /** Pastille : badge, état, variation. */
    fun pill(t: String, color: Int, bg: Int) = text(t, 12f, color).apply {
        typeface = medium
        background = rounded(bg, 99)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        gravity = Gravity.CENTER
    }

    /** Ligne « libellé / valeur » : le libellé au-dessus, petit et discret. */
    fun row(label: String, value: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(this@UiKit.label(label))
        addView(this@UiKit.body(value, Ui.INK).apply {
            typeface = medium
            setPadding(0, dp(6), 0, 0)
        })
    }

    /**
     * Ligne de liste : texte principal, éventuellement une ligne au-dessus (heure), un
     * sous-titre, un signe de tête à gauche, une pastille et une action à droite.
     * L'action est une cible tactile de 44 dp, distincte du clic sur la ligne.
     * Les lignes s'empilent directement dans une carte, séparées par [addRule].
     */
    fun listRow(
        title: String,
        over: String? = null,
        subtitle: String? = null,
        lead: View? = null,
        badge: View? = null,
        glyphIcon: Int? = null,
        glyphDesc: String? = null,
        onOpen: (() -> Unit)? = null,
        onGlyph: (() -> Unit)? = null,
    ) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (onOpen != null) {
            background = tappable(0, 14)
            setOnClickListener { onOpen() }
        }
        setPadding(0, dp(12), 0, dp(12))

        lead?.let {
            val lp = it.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = dp(12)
            addView(it, lp)
        }

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            over?.let { addView(this@UiKit.meta(it)) }
            addView(this@UiKit.heading(title, Ui.INK).apply {
                textSize = 15f
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (over != null) setPadding(0, dp(3), 0, 0)
            })
            subtitle?.let {
                addView(this@UiKit.meta(it).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
            }
        })

        badge?.let {
            addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
        }
        glyphIcon?.let {
            addView(icon(it, Ui.MUTED, 20).apply {
                contentDescription = glyphDesc
                background = tappable(0, 99)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(2) }
                setOnClickListener { onGlyph?.invoke() }
            })
        }
    }

    /** État vide : icône, phrase d'explication, action facultative. Sans surface, il se pose
     *  aussi bien sur le fond d'écran que dans une carte. */
    fun empty(
        res: Int,
        title: String,
        body: String,
        action: String? = null,
        onAction: (() -> Unit)? = null,
    ) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(24), dp(12), dp(24))
        addView(icon(res, Ui.ACCENT, 24).apply {
            background = rounded(Ui.ACCENT_BG, 99)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        })
        addView(heading(title).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        addView(this@UiKit.body(body, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        if (action != null) {
            addView(button(action, primary = true) { onAction?.invoke() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) })
        }
    }

    // ------------------------------------------------------------------- actions

    fun button(label: String, primary: Boolean, onClick: () -> Unit) = buttonStyled(
        label,
        if (primary) Ui.ON_ACCENT else Ui.INK,
        if (primary) Ui.ACCENT else Ui.CARD_HI,
        onClick = onClick,
    )

    private fun buttonStyled(label: String, textColor: Int, bg: Int, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = medium
        setTextColor(textColor)
        background = tappable(bg, 16)
        stateListAnimator = null
        minHeight = dp(52)
        minWidth = 0
        setPadding(dp(20), dp(12), dp(20), dp(12))
        setOnClickListener { onClick() }
    }

    /** Action discrète en pied de carte : « Tout l'historique → ». */
    fun textButton(label: String, onClick: () -> Unit) = text(label, 14f, Ui.ACCENT).apply {
        typeface = medium
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    /** Sélecteur segmenté (Semaine / Mois). */
    fun segmented(labels: List<String>, initial: Int, onSelect: (Int) -> Unit): LinearLayout {
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Ui.BG, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val items = labels.map { label ->
            text(label, 13f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(11), dp(12), dp(11))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                track.addView(this)
            }
        }
        fun paint(sel: Int) = items.forEachIndexed { i, tv ->
            val on = i == sel
            tv.background = if (on) rounded(Ui.CARD_HI, 10) else null
            tv.setTextColor(if (on) Ui.INK else Ui.MUTED)
            tv.typeface = if (on) medium else sans
        }
        paint(initial)
        items.forEachIndexed { i, tv -> tv.setOnClickListener { paint(i); onSelect(i) } }
        return track
    }

    /** Champ de saisie : une ligne ou plusieurs. */
    fun input(hint: String, initial: String = "", lines: Int = 1) = EditText(ctx).apply {
        this.hint = hint
        setText(initial)
        setTextColor(Ui.INK)
        setHintTextColor(Ui.MUTED)
        textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT or
            (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        minLines = lines
        maxLines = if (lines > 1) lines else 1
        gravity = if (lines > 1) Gravity.TOP else Gravity.CENTER_VERTICAL
        background = rounded(Ui.CARD_HI, 14, Ui.LINE)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        // minLines seul ne suffit pas ici : on pose la hauteur de la zone de saisie.
        minHeight = dp(48 + (lines - 1) * 22)
        // Le curseur du thème reste jaune : on le cale sur l'accent courant.
        textCursorDrawable = rounded(Ui.ACCENT, 99).apply { setSize(dp(2), 0) }
    }

    // ------------------------------------------------------------------ dialogues

    private fun dialog(): Dialog = Dialog(ctx).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    @Suppress("DEPRECATION")
    private fun show(d: Dialog, card: LinearLayout) {
        // Fenêtre plein écran, carte centrée : le voile système couvre alors tout l'écran,
        // et la carte remonte d'elle-même quand le clavier s'ouvre.
        d.setContentView(FrameLayout(ctx).apply {
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(card, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(MATCH, MATCH)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.72f)
            // La fenêtre reste dans les limites système : le clavier la raccourcit au lieu
            // de passer par-dessus la carte.
            setDecorFitsSystemWindows(true)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        d.show()
    }

    private fun dialogCard() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.CARD, 24)
        setPadding(dp(22), dp(22), dp(22), dp(18))
    }

    private fun actions(
        d: Dialog,
        confirmLabel: String,
        textColor: Int,
        bg: Int,
        onConfirm: () -> Boolean,
    ) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        addView(button("Annuler", primary = false) { d.dismiss() },
            LinearLayout.LayoutParams(WRAP, WRAP))
        addView(buttonStyled(confirmLabel, textColor, bg) {
            // Une saisie refusée garde la boîte ouverte : rien ne se perd en silence.
            if (onConfirm()) d.dismiss()
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
    }

    /** Saisie : rend les valeurs (vides comprises) ; false garde la boîte ouverte. */
    fun prompt(
        title: String,
        subtitle: String? = null,
        fields: List<Field>,
        confirm: String = "Enregistrer",
        onSubmit: (List<String>) -> Boolean,
    ) {
        val d = dialog()
        val inputs = mutableListOf<EditText>()
        val card = dialogCard()
        card.addView(heading(title).apply { textSize = 20f })
        subtitle?.let { card.addView(body(it).apply { setPadding(0, dp(6), 0, 0) }) }
        fields.forEach { f ->
            card.addFull(label(f.label), 18)
            val e = input(f.hint, f.initial, f.lines)
            inputs += e
            card.addFull(e, 8)
        }
        card.addView(
            actions(d, confirm, Ui.ON_ACCENT, Ui.ACCENT) {
                onSubmit(inputs.map { it.text.toString().trim() })
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) },
        )
        d.setOnShowListener {
            inputs.firstOrNull()?.let {
                it.requestFocus()
                it.setSelection(it.text.length)
            }
        }
        show(d, card)
    }

    /** Confirmation : toute action destructive passe par ici. */
    fun confirm(
        title: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        val d = dialog()
        val card = dialogCard()
        card.addView(heading(title).apply { textSize = 20f })
        card.addView(body(message).apply { setPadding(0, dp(8), 0, 0) })
        card.addView(
            actions(
                d, confirmLabel,
                if (destructive) Ui.WARN else Ui.ON_ACCENT,
                if (destructive) Ui.WARN_BG else Ui.ACCENT,
            ) {
                onConfirm()
                true
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) },
        )
        show(d, card)
    }
}
