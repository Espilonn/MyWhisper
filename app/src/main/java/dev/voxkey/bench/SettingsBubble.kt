package dev.voxkey.bench

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

internal fun SettingsScreen.buildBubbleCard() {
    // Bulle : sa place dans le clavier (aperçu en direct) et la couleur de son fond.
    val bubble = ui.card()
    bubble.addView(ui.label(ui.t(R.string.settings_bubble)))
    bubblePreview = BubblePreview(act).apply { refresh(act) }
    // Hauteur calculée par l'aperçu lui-même, aux proportions du clavier.
    bubble.addFull(bubblePreview, 12)
    addSlider(bubble, ui.t(R.string.settings_bubble_horizontal), BubblePreview.DEFAULT_X) { x ->
        Bubble.setPosition(act, x, Bubble.y(act))
        bubblePreview.refresh(act)
    }.let { bubbleH = it.first; bubbleHValue = it.second }
    addSlider(bubble, ui.t(R.string.settings_bubble_vertical), 1f) { y ->
        Bubble.setPosition(act, Bubble.x(act), y)
        bubblePreview.refresh(act)
    }.let { bubbleV = it.first; bubbleVValue = it.second }
    bubble.addFull(ui.button(ui.t(R.string.settings_bubble_reset), primary = false) {
        Bubble.reset(act)
        syncBubble()
    }, 12)
    bubble.addFull(ui.meta(ui.t(R.string.settings_bubble_hint)), 12)
    bubble.addRule()
    val modeRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    listOf(
        Bubble.MODE_KEYBOARD to R.string.settings_bubble_auto,
        Bubble.MODE_DARK to R.string.settings_bubble_dark,
        Bubble.MODE_ACCENT to R.string.settings_bubble_accent,
    ).forEach { (mode, res) ->
        val pill = ui.text(ui.t(res), 13f, Ui.INK).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = ui.dp(8) }
            setOnClickListener {
                Bubble.setMode(act, mode)
                renderBubbleModes()
            }
        }
        bubbleModes.add(mode to pill)
        modeRow.addView(pill)
    }
    bubble.addFull(modeRow, 14)
    renderBubbleModes()
    bubble.addFull(ui.meta(ui.t(R.string.settings_bubble_color_hint)), 10)
    content.addFull(bubble, 16)
}

/** Curseur d'une fraction de la position, avec sa valeur en pourcentage. */
private fun SettingsScreen.addSlider(
    card: LinearLayout,
    title: String,
    value: Float,
    onMove: (Float) -> Unit,
): Pair<SeekBar, TextView> {
    val head = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.body(title, Ui.INK), LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    val amount = ui.meta("")
    head.addView(amount)
    card.addFull(head, 12)
    val bar = SeekBar(act).apply {
        max = 100
        progress = Math.round(value * 100)
        progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                amount.text = ui.t(R.string.settings_bubble_percent, p)
                if (fromUser) onMove(p / 100f)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    amount.text = ui.t(R.string.settings_bubble_percent, bar.progress)
    card.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
    return bar to amount
}

/** Relit la position réglée : après un reset et à chaque retour sur l'écran. */
internal fun SettingsScreen.syncBubble() {
    val custom = Bubble.custom(act)
    bubbleH.progress = Math.round((if (custom) Bubble.x(act) else BubblePreview.DEFAULT_X) * 100)
    bubbleV.progress = Math.round((if (custom) Bubble.y(act) else 1f) * 100)
    bubblePreview.refresh(act)
    renderBubbleModes()
}

/** Colore les pastilles du mode de fond ; l'aperçu suit la couleur retenue. */
private fun SettingsScreen.renderBubbleModes() {
    val current = Bubble.mode(act)
    bubbleModes.forEach { (mode, pill) ->
        val on = mode == current
        pill.setTextColor(if (on) Ui.ON_ACCENT else Ui.INK)
        pill.background = if (on) ui.rounded(Ui.ACCENT, 12) else ui.rounded(Ui.CARD_HI, 12, Ui.LINE)
        pill.contentDescription = pill.text.toString() + if (on) ui.t(R.string.settings_accent_chosen) else ""
    }
    bubblePreview.refresh(act)
}
