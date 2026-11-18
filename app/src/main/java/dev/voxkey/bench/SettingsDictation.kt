package dev.voxkey.bench

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList

internal fun SettingsScreen.buildDictationCard() {
    val dictation = ui.card()
    dictation.addView(ui.label(ui.t(R.string.settings_dictation)))
    dictation.addView(ui.row(ui.t(R.string.settings_language), ui.t(languageValue())).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isClickable = true
            setOnClickListener { pickLanguage() }
        }
    })
    listOf(
        R.string.settings_command to R.string.settings_command_value,
        R.string.settings_transcription to R.string.settings_on_device,
        R.string.settings_how_to_dictate to R.string.settings_how_to_dictate_value,
    ).forEach { (label, value) ->
        dictation.addRule()
        dictation.addView(ui.row(ui.t(label), ui.t(value)))
    }
    content.addFull(dictation, 16)
}

/** Langue appliquée à l'application : celle du téléphone tant qu'aucun choix n'est fait. */
private fun SettingsScreen.languageValue(): Int = when (chosenLanguage()) {
    "fr" -> R.string.settings_language_fr
    "en" -> R.string.settings_language_en
    else -> R.string.settings_language_system
}

/** Code de la langue choisie dans l'app, "" si l'application suit le téléphone. */
@SuppressLint("NewApi")
private fun SettingsScreen.chosenLanguage(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return ""
    val locales = act.getSystemService(LocaleManager::class.java).applicationLocales
    return if (locales.isEmpty) "" else locales[0].language
}

/** Choix de la langue : suivre le téléphone, français ou anglais. */
@SuppressLint("NewApi")
private fun SettingsScreen.pickLanguage() {
    val manager = act.getSystemService(LocaleManager::class.java)
    val tags = listOf("", "fr", "en")
    ui.choose(
        ui.t(R.string.settings_language_pick),
        listOf(
            ui.t(R.string.settings_language_system),
            ui.t(R.string.settings_language_fr),
            ui.t(R.string.settings_language_en),
        ),
        tags.indexOf(chosenLanguage()).coerceAtLeast(0),
    ) { i ->
        manager.applicationLocales =
            if (tags[i].isEmpty()) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(tags[i])
    }
}
