package dev.voxkey.bench

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Réglages : mise en place (droits), modèle, dictée. La logique vient de l'ancien écran d'accueil
 * et n'a pas changé : mêmes intentions, mêmes préférences, même démarrage de la bulle.
 */
class SettingsScreen(internal val act: Activity, internal val ui: UiKit) {

    companion object {
        /** Préférence qui débloque le banc d'essai (7 appuis sur « Version »). */
        const val BENCH_VISIBLE = "bench_visible"

        fun benchVisible(act: Activity) =
            act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE).getBoolean(BENCH_VISIBLE, false)

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
    internal inner class Step(
        val number: Int,
        val title: String,
        val hint: String,
        val doneText: String,
        val buttonLabel: String,
        val isDone: () -> Boolean,
        val onAction: () -> Unit,
        /** Étiquette et action quand l'étape est déjà faite (bouton inverse). */
        val altLabel: String? = null,
        val altAction: (() -> Unit)? = null,
        /** Garde le bouton visible même quand l'étape est faite (réglage, pas une permission). */
        val alwaysShowButton: Boolean = false,
    ) {
        lateinit var badge: TextView
        lateinit var status: TextView
        lateinit var button: Button
    }

    internal val steps = mutableListOf<Step>()

    /** Emplacement du banc d'essai, rempli par MainActivity seulement s'il est débloqué. */
    val benchSlot = LinearLayout(act)

    /** Prévient MainActivity du déblocage, pour poser ou masquer la carte sans redémarrer. */
    var onBenchVisibilityChanged: ((Boolean) -> Unit)? = null

    /** Prévient MainActivity d'un changement d'accent : les cinq écrans sont repeints. */
    var onAccentChanged: (() -> Unit)? = null

    internal var versionTaps = 0

    private val page = ui.page(ui.t(R.string.settings_title), ui.t(R.string.settings_desc))
    internal val content = page.body

    internal lateinit var modelSpinner: Spinner
    internal lateinit var modelField: LinearLayout
    internal lateinit var modelRefresh: TextView
    internal lateinit var accentName: TextView
    internal lateinit var accentRow: LinearLayout
    internal lateinit var modelEmpty: TextView
    internal lateinit var downloadBox: LinearLayout
    internal lateinit var downloadStatus: TextView
    /** Carte du modèle local : masquée quand la dictée passe par une API distante. */
    internal lateinit var modelCard: LinearLayout
    internal lateinit var cloudBox: LinearLayout
    internal lateinit var cloudStatus: TextView
    internal lateinit var modelInput: android.widget.EditText
    internal lateinit var keyInput: android.widget.EditText
    internal val prefs by lazy { act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE) }
    internal lateinit var deleteModel: Button
    internal var modelFiles: List<File> = emptyList()
    internal lateinit var setupProgress: TextView
    /** Aide affichée tant que l'accessibilité est bloquée par les paramètres restreints. */
    internal lateinit var restrictedHint: TextView
    internal lateinit var restrictedButton: Button
    internal lateinit var bubblePreview: BubblePreview
    internal lateinit var bubbleH: SeekBar
    internal lateinit var bubbleV: SeekBar
    internal lateinit var bubbleHValue: TextView
    internal lateinit var bubbleVValue: TextView
    /** Pastilles du mode de couleur : le mode est relu à chaque rendu. */
    internal val bubbleModes = ArrayList<Pair<String, TextView>>()
    /** Un seul téléchargement à la fois ; les boutons sont reconstruits désactivés pendant l'attente. */
    internal var downloading = false
    /** Vrai pendant qu'on réaligne le spinner sur la préférence : ne pas réécrire le choix. */
    internal var suppressModelWrite = false
    val view: View = page.root

    init {
        buildSetupCard()
        buildAppearanceCard()
        buildBubbleCard()
        buildEngineCard()
        buildModelCard()
        renderEngine()
        buildDictationCard()
        buildAboutCard()

        // Emplacement du banc d'essai : vide tant qu'il n'est pas débloqué.
        content.addFull(benchSlot, 0)
    }

    /** Relit l'état des droits et des modèles (appelé à chaque retour sur l'app). */
    fun refresh() {
        refreshState()
        refreshModels()
        renderAccents()
        syncBubble()
        modelRefresh.setTextColor(Ui.ACCENT)
    }

    /** Pastille (numéro ou coche) par étape ; le bouton n'est affiché que pour la prochaine étape.
     *  Étape faite : accent en texte sur pastille teintée. Étape en cours : aplat d'accent plein,
     *  pour les distinguer sans sortir de la palette. */
    private fun refreshState() {
        val done = steps.map { it.isDone() }
        val current = done.indexOf(false)
        setupProgress.text = ui.t(R.string.settings_progress, done.count { it }, steps.size)
        steps.forEachIndexed { i, s ->
            val isDone = done[i]
            val isCurrent = i == current
            s.badge.text = if (isDone) "✓" else "${s.number}"
            s.badge.setTextColor(
                when {
                    isDone -> Ui.ACCENT
                    isCurrent -> Ui.ON_ACCENT
                    else -> Ui.MUTED
                }
            )
            s.badge.background = ui.rounded(
                when {
                    isDone -> Ui.ACCENT_BG
                    isCurrent -> Ui.ACCENT
                    else -> Ui.CARD_HI
                },
                99,
            )
            s.status.text = if (isDone) s.doneText else s.hint
            s.status.setTextColor(if (isDone) Ui.ACCENT else Ui.MUTED)
            // Le bouton inverse d'une étape faite est un réglage, pas une permission : discret.
            if (s.altLabel != null) s.button.text = if (isDone) s.altLabel else s.buttonLabel
            s.button.background = ui.tappable(if (isDone && s.altLabel != null) Ui.CARD_HI else Ui.ACCENT, 16)
            s.button.setTextColor(if (isDone && s.altLabel != null) Ui.INK else Ui.ON_ACCENT)
            s.button.visibility =
                if (isCurrent || s.alwaysShowButton) View.VISIBLE else View.GONE
        }
        // L'aide ne sert que tant que l'accessibilité manque.
        val restricted = !isAccessibilityOn(act)
        restrictedHint.visibility = if (restricted) View.VISIBLE else View.GONE
        restrictedButton.visibility = if (restricted) View.VISIBLE else View.GONE
    }

    /** Active ou coupe la bulle (préférence lue aussi au démarrage du service et de l'accessibilité). */
    internal fun setBubble(on: Boolean) {
        if (on && !isAccessibilityOn(act) && !Settings.canDrawOverlays(act)) {
            Toast.makeText(act, ui.t(R.string.settings_overlay_first), Toast.LENGTH_SHORT).show()
            return
        }
        OverlayService.setEnabled(act, on)
        Toast.makeText(
            act,
            if (on) ui.t(R.string.settings_key_on) else ui.t(R.string.settings_key_off),
            Toast.LENGTH_SHORT,
        ).show()
        refreshState()
    }
}
