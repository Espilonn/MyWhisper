package dev.voxkey.bench

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Réglages : mise en place (droits), modèle, dictée. La logique vient de l'ancien écran d'accueil
 * et n'a pas changé : mêmes intentions, mêmes préférences, même démarrage de la bulle.
 */
class SettingsScreen(private val act: Activity, private val ui: UiKit) {

    companion object {
        fun isAccessibilityOn(act: Activity): Boolean {
            val enabled = Settings.Secure.getString(
                act.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains("${act.packageName}/")
        }

        fun micGranted(act: Activity) =
            act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /** Une étape de mise en place : son état et l'action proposée tant qu'elle n'est pas faite. */
    private inner class Step(
        val number: Int,
        val title: String,
        val hint: String,
        val doneText: String,
        val buttonLabel: String,
        val isDone: () -> Boolean,
        val onAction: () -> Unit,
    ) {
        lateinit var badge: TextView
        lateinit var status: TextView
        lateinit var button: Button
    }

    private val steps = mutableListOf<Step>()
    private lateinit var modelSpinner: Spinner
    private lateinit var modelEmpty: TextView
    val view: ScrollView

    init {
        steps += Step(
            1, "Superposition",
            hint = "Affiche la touche par-dessus les autres applis.",
            doneText = "Autorisée",
            buttonLabel = "Autoriser la superposition",
            isDone = { Settings.canDrawOverlays(act) },
            onAction = {
                act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
            },
        )
        steps += Step(
            2, "Accessibilité",
            hint = "Écrit le texte dicté dans le champ actif.",
            doneText = "Activée",
            buttonLabel = "Ouvrir les réglages d'accessibilité",
            isDone = { isAccessibilityOn(act) },
            onAction = { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )
        steps += Step(
            3, "Micro",
            hint = "Enregistre ta voix pendant la dictée.",
            doneText = "Autorisé",
            buttonLabel = "Autoriser le micro",
            isDone = { micGranted(act) },
            onAction = { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
        )
        steps += Step(
            4, "Touche de dictée",
            hint = "Apparaît en bas à gauche du clavier.",
            doneText = "Active",
            buttonLabel = "Activer la touche de dictée",
            isDone = { OverlayService.running },
            onAction = { ensureBubble() },
        )

        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(24))
        }
        content.addView(ui.text("Réglages", 26f, Ui.INK, bold = true))
        content.addFull(ui.text("Droits, modèle et fonctionnement de la dictée.", 14f, Ui.MUTED), 4)

        val setup = ui.card()
        setup.addView(ui.sectionTitle(R.drawable.ic_nav_settings, "Mise en place"))
        steps.forEachIndexed { i, s -> setup.addFull(stepRow(s), if (i == 0) 14 else 18) }
        content.addFull(setup, 20)

        val model = ui.card()
        model.addView(ui.sectionTitle(R.drawable.ic_words, "Modèle de reconnaissance"))
        modelSpinner = Spinner(act)
        model.addFull(modelSpinner, 12)
        modelEmpty = ui.text(
            "Aucun modèle trouvé. Ajoute un fichier de modèle (.bin ou .gguf) à l'app, puis appuie sur Rafraîchir.",
            13f, Ui.MUTED,
        )
        model.addFull(modelEmpty, 8)
        model.addFull(ui.button("Rafraîchir", primary = false) { refreshModels() }, 12)
        content.addFull(model, 16)

        val dictation = ui.card()
        dictation.addView(ui.sectionTitle(R.drawable.ic_nav_home, "Dictée"))
        dictation.addFull(ui.row("Langue", "Français"), 14)
        dictation.addFull(ui.row("Commande", "Appui pour dicter, appui pour arrêter"), 8)
        dictation.addFull(ui.row("Transcription", "Sur le téléphone"), 8)
        dictation.addFull(ui.row("Pour dicter", "Ouvre un champ de texte : la touche apparaît en bas à gauche du clavier."), 8)
        content.addFull(dictation, 16)

        val about = ui.card()
        about.addView(ui.sectionTitle(R.drawable.ic_nav_dictionary, "À propos"))
        about.addFull(ui.row("Version", appVersion()), 14)
        content.addFull(about, 16)

        view = ScrollView(act).apply { addView(content) }
    }

    /** Relit l'état des droits et des modèles (appelé à chaque retour sur l'app). */
    fun refresh() {
        refreshState()
        refreshModels()
    }

    private fun appVersion(): String =
        act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "inconnue"

    private fun stepRow(s: Step): View {
        s.badge = ui.text("", 14f, Ui.INK, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ui.dp(28), ui.dp(28))
        }
        s.status = ui.text("", 13f, Ui.MUTED)
        s.button = ui.button(s.buttonLabel, primary = true) { s.onAction() }

        val labels = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(s.title, 16f, Ui.INK, bold = true))
            addView(s.status)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = ui.dp(12) }
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(s.badge)
            addView(labels)
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addFull(s.button, 12)
        }
    }

    /** Pastille (numéro ou coche) par étape ; le bouton n'est affiché que pour la prochaine étape. */
    private fun refreshState() {
        val done = steps.map { it.isDone() }
        val current = done.indexOf(false)
        steps.forEachIndexed { i, s ->
            val isDone = done[i]
            val isCurrent = i == current
            s.badge.text = if (isDone) "✓" else "${s.number}"
            s.badge.setTextColor(
                when {
                    isDone -> Ui.INK
                    isCurrent -> Ui.ON_ACCENT
                    else -> Ui.MUTED
                }
            )
            s.badge.background = ui.rounded(
                when {
                    isDone -> Ui.LINE
                    isCurrent -> Ui.ACCENT
                    else -> Ui.CARD_HI
                },
                14,
            )
            s.status.text = if (isDone) s.doneText else s.hint
            s.status.setTextColor(if (isDone) Ui.GOOD else Ui.MUTED)
            s.button.visibility = if (isCurrent) View.VISIBLE else View.GONE
        }
    }

    private fun refreshModels() {
        val files = act.getExternalFilesDir(null)?.listFiles()
            ?.filter { it.name.endsWith(".bin") || it.name.endsWith(".gguf") }
            ?.sortedBy { it.length() }
            ?: emptyList()

        modelSpinner.adapter = ArrayAdapter(
            act,
            android.R.layout.simple_spinner_dropdown_item,
            files.map { "${it.name} (${it.length() / 1_048_576} Mo)" }
        )
        modelSpinner.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        modelEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE

        val prefs = act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE)
        val saved = prefs.getString("model", null)
        val idx = files.indexOfFirst { it.name == saved }
        if (idx >= 0) modelSpinner.setSelection(idx)

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                files.getOrNull(pos)?.let {
                    prefs.edit().putString("model", it.name).apply()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun ensureBubble() {
        if (!Settings.canDrawOverlays(act)) {
            Toast.makeText(act, "Autorise d'abord la superposition", Toast.LENGTH_SHORT).show()
            return
        }
        act.startService(Intent(act, OverlayService::class.java))
        Toast.makeText(act, "Touche de dictée activée", Toast.LENGTH_SHORT).show()
    }
}
