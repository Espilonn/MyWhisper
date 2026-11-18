package dev.voxkey.bench

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal fun SettingsScreen.buildSetupCard() {
    steps += this.Step(
        1, ui.t(R.string.settings_step_overlay),
        hint = ui.t(R.string.settings_step_overlay_hint),
        doneText = ui.t(R.string.settings_step_overlay_done),
        buttonLabel = ui.t(R.string.settings_step_overlay_action),
        isDone = { Settings.canDrawOverlays(act) },
        onAction = {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
        },
    )
    steps += this.Step(
        2, ui.t(R.string.settings_step_a11y),
        hint = ui.t(R.string.settings_step_a11y_hint),
        doneText = ui.t(R.string.settings_step_a11y_done),
        buttonLabel = ui.t(R.string.settings_step_a11y_action),
        isDone = { SettingsScreen.isAccessibilityOn(act) },
        onAction = { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    )
    steps += this.Step(
        3, ui.t(R.string.settings_step_mic),
        hint = ui.t(R.string.settings_step_mic_hint),
        doneText = ui.t(R.string.settings_step_mic_done),
        buttonLabel = ui.t(R.string.settings_step_mic_action),
        isDone = { SettingsScreen.micGranted(act) },
        onAction = { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
    )
    steps += this.Step(
        4, ui.t(R.string.settings_step_key),
        hint = ui.t(R.string.settings_step_key_hint),
        doneText = ui.t(R.string.settings_step_key_done),
        buttonLabel = ui.t(R.string.settings_step_key_action),
        isDone = { OverlayService.isEnabled(act) },
        onAction = { setBubble(true) },
        altLabel = ui.t(R.string.settings_step_key_off_action),
        altAction = { setBubble(false) },
        alwaysShowButton = true,
    )

    val setup = ui.card()
    val setupHead = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.label(ui.t(R.string.settings_setup)), LinearLayout.LayoutParams(0, WRAP, 1f))
        setupProgress = ui.meta("")
        addView(setupProgress)
    }
    setup.addView(setupHead)
    steps.forEachIndexed { i, s ->
        if (i > 0) setup.addRule()
        setup.addFull(stepRow(s), if (i == 0) 6 else 0)
    }
    // Une app installée hors store a ses bascules d'accessibilité grisées jusqu'à ce que
    // l'utilisateur autorise les paramètres restreints depuis la fiche de l'app.
    restrictedHint = ui.meta(ui.t(R.string.settings_restricted_hint)).apply {
        setPadding(0, ui.dp(12), 0, 0)
        visibility = View.GONE
    }
    setup.addView(restrictedHint)
    restrictedButton = ui.button(ui.t(R.string.settings_restricted_action), primary = false) {
        // Android n'expose pas d'écran dédié : la fiche de l'app porte le menu ⋮.
        act.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}")),
        )
    }.apply { visibility = View.GONE }
    setup.addFull(restrictedButton, 10)
    content.addView(setup, LinearLayout.LayoutParams(MATCH, WRAP))
}

private fun SettingsScreen.stepRow(s: SettingsScreen.Step): View {
    s.badge = ui.text("", 13f, Ui.INK).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ui.dp(30), ui.dp(30))
    }
    s.status = ui.meta("")
    s.button = ui.button(s.buttonLabel, primary = true) {
        if (s.isDone() && s.altAction != null) s.altAction.invoke() else s.onAction()
    }

    val labels = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(ui.text(s.title, 15f, Ui.INK, bold = true))
        addView(s.status, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(3) })
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = ui.dp(14) }
    }
    val head = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(s.badge)
        addView(labels)
    }
    return LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ui.dp(14), 0, ui.dp(14))
        addView(head)
        addFull(s.button, 12)
    }
}
