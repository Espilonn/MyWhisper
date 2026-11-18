package dev.voxkey.bench

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

internal fun SettingsScreen.buildAppearanceCard() {
    // Apparence : un accent unique, choisi parmi cinq, qui recolore toute l'app.
    val theme = ui.card()
    val themeHead = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.label(ui.t(R.string.settings_appearance)), LinearLayout.LayoutParams(0, WRAP, 1f))
        accentName = ui.meta("")
        addView(accentName)
    }
    theme.addView(themeHead)
    accentRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    theme.addView(accentRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
    theme.addView(ui.meta(ui.t(R.string.settings_appearance_hint)).apply {
        setPadding(0, ui.dp(14), 0, 0)
    })
    content.addFull(theme, 16)
}

/** Les cinq accents proposés, répartis sur la largeur ; l'accent actif porte une coche
 *  (un anneau clair se perdrait sur le jaune). */
internal fun SettingsScreen.renderAccents() {
    accentName.text = ui.t(R.string.settings_accent_named, ui.t(Accent.nameRes()))
    accentRow.removeAllViews()
    Accent.palette.forEach { (name, color) ->
        val on = Accent.current() == color
        accentRow.addView(FrameLayout(act).apply {
            contentDescription = ui.t(R.string.settings_accent_named, ui.t(name)) +
                if (on) ui.t(R.string.settings_accent_chosen) else ""
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(48), 1f)
            setOnClickListener { pickAccent(color) }
            addView(FrameLayout(act).apply {
                background = swatch(color)
                layoutParams = FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER)
                if (on) addView(ui.text("✓", 17f, Accent.on(color)).apply {
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(MATCH, MATCH))
            })
        })
    }
}

private fun SettingsScreen.swatch(color: Int): android.graphics.drawable.Drawable {
    val tv = android.util.TypedValue()
    val found = act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
    val ripple = if (found && tv.resourceId != 0) act.getDrawable(tv.resourceId) else null
    val base = ui.rounded(color, 99)
    if (ripple == null) return base
    return android.graphics.drawable.LayerDrawable(arrayOf(base, ripple))
}

private fun SettingsScreen.pickAccent(color: Int) {
    if (Accent.current() == color) return
    Accent.set(act, color)
    renderAccents()
    onAccentChanged?.invoke()
}
