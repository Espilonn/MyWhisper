package dev.voxkey.bench

import android.app.Activity
import android.widget.Toast

internal fun SettingsScreen.buildAboutCard() {
    val about = ui.card()
    about.addView(ui.label(ui.t(R.string.settings_about)))
    about.addView(ui.row(ui.t(R.string.settings_version), appVersion()).apply {
        setOnClickListener { countVersionTap() }
    })
    content.addFull(about, 16)
}

/**
 * Geste discret : sept appuis sur « Version » ouvrent ou ferment le banc d'essai.
 * Rien à l'écran ne le laisse deviner ; c'est un outil de mesure, pas une fonction.
 */
private fun SettingsScreen.countVersionTap() {
    versionTaps++
    if (versionTaps < 7) return
    versionTaps = 0
    val on = !SettingsScreen.benchVisible(act)
    act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE)
        .edit().putBoolean(SettingsScreen.BENCH_VISIBLE, on).apply()
    Toast.makeText(
        act,
        if (on) ui.t(R.string.settings_bench_on) else ui.t(R.string.settings_bench_off),
        Toast.LENGTH_SHORT,
    ).show()
    onBenchVisibilityChanged?.invoke(on)
}

private fun SettingsScreen.appVersion(): String =
    act.packageManager.getPackageInfo(act.packageName, 0).versionName
        ?: ui.t(R.string.settings_version_unknown)
