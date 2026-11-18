package dev.voxkey.bench

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * Réglages : mise en place (droits), modèle, dictée. La logique vient de l'ancien écran d'accueil
 * et n'a pas changé : mêmes intentions, mêmes préférences, même démarrage de la bulle.
 */
class SettingsScreen(private val act: Activity, private val ui: UiKit) {

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

    /** Langue appliquée à l'application : celle du téléphone tant qu'aucun choix n'est fait. */
    private fun languageValue(): Int = when (chosenLanguage()) {
        "fr" -> R.string.settings_language_fr
        "en" -> R.string.settings_language_en
        else -> R.string.settings_language_system
    }

    /** Code de la langue choisie dans l'app, "" si l'application suit le téléphone. */
    @SuppressLint("NewApi")
    private fun chosenLanguage(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return ""
        val locales = act.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) "" else locales[0].language
    }

    /** Choix de la langue : suivre le téléphone, français ou anglais. */
    @SuppressLint("NewApi")
    private fun pickLanguage() {
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

    /** Une étape de mise en place : son état et l'action proposée tant qu'elle n'est pas faite. */
    private inner class Step(
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

    private val steps = mutableListOf<Step>()

    /** Emplacement du banc d'essai, rempli par MainActivity seulement s'il est débloqué. */
    val benchSlot = LinearLayout(act)

    /** Prévient MainActivity du déblocage, pour poser ou masquer la carte sans redémarrer. */
    var onBenchVisibilityChanged: ((Boolean) -> Unit)? = null

    /** Prévient MainActivity d'un changement d'accent : les cinq écrans sont repeints. */
    var onAccentChanged: (() -> Unit)? = null

    private var versionTaps = 0

    private val page = ui.page(ui.t(R.string.settings_title), ui.t(R.string.settings_desc))
    private val content = page.body

    private lateinit var modelSpinner: Spinner
    private lateinit var modelField: LinearLayout
    private lateinit var modelRefresh: TextView
    private lateinit var accentName: TextView
    private lateinit var accentRow: LinearLayout
    private lateinit var modelEmpty: TextView
    private lateinit var downloadBox: LinearLayout
    private lateinit var downloadStatus: TextView
    /** Carte du modèle local : masquée quand la dictée passe par une API distante. */
    private lateinit var modelCard: LinearLayout
    private lateinit var cloudBox: LinearLayout
    private lateinit var cloudStatus: TextView
    private lateinit var modelInput: android.widget.EditText
    private lateinit var keyInput: android.widget.EditText
    private val prefs by lazy { act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE) }
    private lateinit var deleteModel: Button
    private var modelFiles: List<File> = emptyList()
    private lateinit var setupProgress: TextView
    /** Aide affichée tant que l'accessibilité est bloquée par les paramètres restreints. */
    private lateinit var restrictedHint: TextView
    private lateinit var restrictedButton: Button
    private lateinit var bubblePreview: BubblePreview
    private lateinit var bubbleH: SeekBar
    private lateinit var bubbleV: SeekBar
    private lateinit var bubbleHValue: TextView
    private lateinit var bubbleVValue: TextView
    /** Pastilles du mode de couleur : le mode est relu à chaque rendu. */
    private val bubbleModes = ArrayList<Pair<String, TextView>>()
    /** Un seul téléchargement à la fois ; les boutons sont reconstruits désactivés pendant l'attente. */
    private var downloading = false
    /** Vrai pendant qu'on réaligne le spinner sur la préférence : ne pas réécrire le choix. */
    private var suppressModelWrite = false
    val view: View = page.root

    init {
        steps += Step(
            1, ui.t(R.string.settings_step_overlay),
            hint = ui.t(R.string.settings_step_overlay_hint),
            doneText = ui.t(R.string.settings_step_overlay_done),
            buttonLabel = ui.t(R.string.settings_step_overlay_action),
            isDone = { Settings.canDrawOverlays(act) },
            onAction = {
                act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
            },
        )
        steps += Step(
            2, ui.t(R.string.settings_step_a11y),
            hint = ui.t(R.string.settings_step_a11y_hint),
            doneText = ui.t(R.string.settings_step_a11y_done),
            buttonLabel = ui.t(R.string.settings_step_a11y_action),
            isDone = { isAccessibilityOn(act) },
            onAction = { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )
        steps += Step(
            3, ui.t(R.string.settings_step_mic),
            hint = ui.t(R.string.settings_step_mic_hint),
            doneText = ui.t(R.string.settings_step_mic_done),
            buttonLabel = ui.t(R.string.settings_step_mic_action),
            isDone = { micGranted(act) },
            onAction = { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
        )
        steps += Step(
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

        val engine = ui.card()
        engine.addView(ui.label(ui.t(R.string.settings_engine)))
        engine.addFull(
            ui.segmented(
                listOf(ui.t(R.string.settings_engine_local), ui.t(R.string.settings_engine_cloud)),
                if (RemoteEngine.isCloud(act)) 1 else 0
            ) { i ->
                prefs.edit().putString(RemoteEngine.PREF_ENGINE, if (i == 0) "local" else "cloud").apply()
                renderEngine()
            }, 10
        )
        engine.addFull(ui.meta(ui.t(R.string.settings_engine_hint)), 8)

        cloudBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        cloudBox.addFull(
            ui.segmented(
                listOf("Groq", "OpenRouter"),
                if (RemoteEngine.provider(act) == RemoteEngine.OPENROUTER) 1 else 0
            ) { i ->
                val before = RemoteEngine.provider(act)
                val p = if (i == 0) RemoteEngine.GROQ else RemoteEngine.OPENROUTER
                // Le champ appartient encore au fournisseur précédent : on le range chez lui
                // avant de charger la clé de l'autre.
                RemoteEngine.saveKey(act, before, keyInput.text.toString())
                prefs.edit().putString(RemoteEngine.PREF_PROVIDER, p)
                    .putString(RemoteEngine.PREF_MODEL, RemoteEngine.defaultModel(p)).apply()
                modelInput.setText(RemoteEngine.defaultModel(p))
                keyInput.setText(RemoteEngine.key(act, p))
                cloudStatus.text = ""
            }, 14
        )
        modelInput = ui.input(ui.t(R.string.settings_cloud_model_hint), RemoteEngine.model(act)).apply {
            setOnFocusChangeListener { _, focused ->
                if (!focused) saveCloudKey()
            }
        }
        cloudBox.addFull(modelInput, 10)
        keyInput = ui.input(ui.t(R.string.settings_cloud_key_hint), RemoteEngine.key(act)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { _, focused ->
                if (!focused) saveCloudKey()
            }
        }
        cloudBox.addFull(keyInput, 8)
        cloudBox.addFull(ui.button(ui.t(R.string.settings_cloud_test), primary = false) { testCloud() }, 12)
        cloudStatus = ui.meta("")
        cloudBox.addFull(cloudStatus, 8)
        engine.addFull(cloudBox, 4)
        content.addFull(engine, 16)

        val model = ui.card().also { modelCard = it }
        val modelHead = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.label(ui.t(R.string.settings_model)), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.text(ui.t(R.string.settings_refresh), 13f, Ui.ACCENT).apply {
                modelRefresh = this
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(ui.dp(10), ui.dp(10), 0, ui.dp(10))
                setOnClickListener { refreshModels() }
            })
        }
        model.addView(modelHead)
        // Un sélecteur maison : le Spinner système impose sa propre taille de texte.
        modelSpinner = Spinner(act).apply {
            isFocusable = false
            background = null
            setPadding(ui.dp(14), 0, ui.dp(6), 0)
        }
        modelField = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Ui.CARD_HI, 14, Ui.LINE)
            addView(modelSpinner, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
            addView(ui.icon(R.drawable.ic_chevron, Ui.MUTED, 18).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(ui.dp(26), ui.dp(18)).apply { rightMargin = ui.dp(14) }
            })
            setOnClickListener { modelSpinner.performClick() }
        }
        model.addFull(modelField, 10)
        deleteModel = ui.button(ui.t(R.string.settings_model_delete), primary = false) { confirmDeleteModel() }
        model.addFull(deleteModel, 8)
        modelEmpty = ui.meta(
            ui.t(R.string.settings_model_empty),
        )
        model.addFull(modelEmpty, 8)
        downloadBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        model.addFull(downloadBox, 6)
        downloadStatus = ui.meta("")
        model.addFull(downloadStatus, 12)
        content.addFull(model, 16)
        renderEngine()

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

        val about = ui.card()
        about.addView(ui.label(ui.t(R.string.settings_about)))
        about.addView(ui.row(ui.t(R.string.settings_version), appVersion()).apply {
            setOnClickListener { countVersionTap() }
        })
        content.addFull(about, 16)

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

    /** Curseur d'une fraction de la position, avec sa valeur en pourcentage. */
    private fun addSlider(
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
    private fun syncBubble() {
        val custom = Bubble.custom(act)
        bubbleH.progress = Math.round((if (custom) Bubble.x(act) else BubblePreview.DEFAULT_X) * 100)
        bubbleV.progress = Math.round((if (custom) Bubble.y(act) else 1f) * 100)
        bubblePreview.refresh(act)
        renderBubbleModes()
    }

    /** Colore les pastilles du mode de fond ; l'aperçu suit la couleur retenue. */
    private fun renderBubbleModes() {
        val current = Bubble.mode(act)
        bubbleModes.forEach { (mode, pill) ->
            val on = mode == current
            pill.setTextColor(if (on) Ui.ON_ACCENT else Ui.INK)
            pill.background = if (on) ui.rounded(Ui.ACCENT, 12) else ui.rounded(Ui.CARD_HI, 12, Ui.LINE)
            pill.contentDescription = pill.text.toString() + if (on) ui.t(R.string.settings_accent_chosen) else ""
        }
        bubblePreview.refresh(act)
    }

    /** Les cinq accents proposés, répartis sur la largeur ; l'accent actif porte une coche
     *  (un anneau clair se perdrait sur le jaune). */
    private fun renderAccents() {
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

    private fun swatch(color: Int): android.graphics.drawable.Drawable {
        val tv = android.util.TypedValue()
        val found = act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        val ripple = if (found && tv.resourceId != 0) act.getDrawable(tv.resourceId) else null
        val base = ui.rounded(color, 99)
        if (ripple == null) return base
        return android.graphics.drawable.LayerDrawable(arrayOf(base, ripple))
    }

    private fun pickAccent(color: Int) {
        if (Accent.current() == color) return
        Accent.set(act, color)
        renderAccents()
        onAccentChanged?.invoke()
    }

    /**
     * Geste discret : sept appuis sur « Version » ouvrent ou ferment le banc d'essai.
     * Rien à l'écran ne le laisse deviner ; c'est un outil de mesure, pas une fonction.
     */
    private fun countVersionTap() {
        versionTaps++
        if (versionTaps < 7) return
        versionTaps = 0
        val on = !benchVisible(act)
        act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE)
            .edit().putBoolean(BENCH_VISIBLE, on).apply()
        Toast.makeText(
            act,
            if (on) ui.t(R.string.settings_bench_on) else ui.t(R.string.settings_bench_off),
            Toast.LENGTH_SHORT,
        ).show()
        onBenchVisibilityChanged?.invoke(on)
    }

    private fun appVersion(): String =
        act.packageManager.getPackageInfo(act.packageName, 0).versionName
            ?: ui.t(R.string.settings_version_unknown)

    private fun stepRow(s: Step): View {
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

    private fun refreshModels() {
        val files = act.getExternalFilesDir(null)?.listFiles()
            ?.filter { it.name.endsWith(".bin") || it.name.endsWith(".gguf") }
            ?.sortedBy { it.length() }
            ?: emptyList()
        modelFiles = files

        modelSpinner.adapter = object : ArrayAdapter<String>(act, 0, files.map {
            ui.t(R.string.settings_model_file, it.name, ui.t(R.string.megabytes, it.length() / 1_048_576))
        }) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                ui.body(getItem(position).orEmpty(), Ui.INK).apply {
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    layoutParams = android.widget.AbsListView.LayoutParams(MATCH, ui.dp(52))
                    setPadding(ui.dp(2), 0, ui.dp(2), 0)
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                ui.body(getItem(position).orEmpty(), Ui.INK).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = ui.tappable(Ui.CARD_HI, 0)
                    layoutParams = android.widget.AbsListView.LayoutParams(MATCH, ui.dp(48))
                    setPadding(ui.dp(16), 0, ui.dp(16), 0)
                }
        }
        modelSpinner.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        modelField.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        modelEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        deleteModel.visibility = if (files.isEmpty() || downloading) View.GONE else View.VISIBLE

        val prefs = act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE)
        val saved = prefs.getString("model", null)
        val idx = files.indexOfFirst { it.name == saved }

        // Le listener est posé AVANT setSelection : sinon un rafraîchissement de l'écran
        // réécrivait le modèle choisi par le premier de la liste (le plus petit).
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressModelWrite) return
                files.getOrNull(pos)?.let {
                    prefs.edit().putString("model", it.name).apply()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        suppressModelWrite = true
        if (idx >= 0) modelSpinner.setSelection(idx)
        modelSpinner.post { suppressModelWrite = false }

        renderDownloads(files.map { it.name }.toSet())
    }

    /** Une ligne par modèle du catalogue absent du téléphone. */
    private fun renderDownloads(present: Set<String>) {
        downloadBox.removeAllViews()
        val missing = ModelCatalog.all.filter { it.file !in present }
        if (missing.isEmpty()) {
            downloadBox.addFull(ui.meta(ui.t(R.string.settings_models_installed)), 6)
            return
        }
        downloadBox.addFull(ui.label(ui.t(R.string.settings_download_title)), 16)
        for (m in missing) {
            downloadBox.addFull(
                ui.meta(ui.t(R.string.settings_model_line, ui.t(m.labelRes), ui.t(R.string.megabytes, m.mo))),
                10,
            )
            val b = ui.button(ui.t(R.string.settings_download_action), primary = false) { startDownload(m) }
            b.isEnabled = !downloading
            b.alpha = if (downloading) 0.4f else 1f
            downloadBox.addFull(b, 8)
        }
    }

    private fun confirmDeleteModel() {
        val f = modelFiles.getOrNull(modelSpinner.selectedItemPosition) ?: return
        AlertDialog.Builder(act)
            .setMessage(ui.t(R.string.settings_model_delete_confirm, f.name))
            .setNegativeButton(ui.t(R.string.cancel), null)
            .setPositiveButton(ui.t(R.string.settings_model_delete)) { _, _ -> removeModel(f) }
            .show()
    }

    private fun removeModel(f: File) {
        if (!f.delete()) {
            Toast.makeText(act, ui.t(R.string.settings_model_delete_failed), Toast.LENGTH_SHORT).show()
            return
        }
        // Le modèle supprimé peut être celui que la dictée charge au démarrage.
        val prefs = act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE)
        if (prefs.getString("model", null) == f.name) {
            val next = modelFiles.firstOrNull { it != f }
            if (next != null) prefs.edit().putString("model", next.name).apply()
            else prefs.edit().remove("model").apply()
        }
        Toast.makeText(act, ui.t(R.string.settings_model_deleted), Toast.LENGTH_SHORT).show()
        refreshModels()
    }

    /** Local : la carte du modèle ; cloud : fournisseur, modèle, clé. */
    private fun renderEngine() {
        val cloud = RemoteEngine.isCloud(act)
        cloudBox.visibility = if (cloud) View.VISIBLE else View.GONE
        modelCard.visibility = if (cloud) View.GONE else View.VISIBLE
    }

    private fun saveCloudKey() {
        prefs.edit().putString(RemoteEngine.PREF_MODEL, modelInput.text.toString().trim()).apply()
        RemoteEngine.saveKey(act, RemoteEngine.provider(act), keyInput.text.toString())
    }

    private fun testCloud() {
        saveCloudKey()
        cloudStatus.setTextColor(Ui.MUTED)
        cloudStatus.text = ui.t(R.string.settings_cloud_testing)
        thread(name = "voxkey-key") {
            val err = RemoteEngine.check(act)
            act.runOnUiThread {
                cloudStatus.setTextColor(if (err == null) Ui.GOOD else Ui.WARN)
                cloudStatus.text =
                    if (err == null) ui.t(R.string.settings_cloud_test_ok)
                    else ui.t(R.string.settings_cloud_test_fail, err)
            }
        }
    }

    private fun startDownload(m: ModelCatalog.Remote) {
        val dir = act.getExternalFilesDir(null)
        if (dir == null) {
            Toast.makeText(act, ui.t(R.string.settings_storage_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        downloading = true
        downloadStatus.text = ui.t(R.string.settings_download_connecting, ui.t(m.labelRes))
        refreshModels()
        ModelCatalog.download(
            act, m.file, dir,
            onProgress = { done, total ->
                act.runOnUiThread {
                    downloadStatus.text = if (total > 0) {
                        ui.t(
                            R.string.settings_download_percent,
                            ui.t(m.labelRes),
                            done * 100 / total,
                            ui.t(R.string.megabytes, done / 1_048_576),
                            ui.t(R.string.megabytes, m.mo),
                        )
                    } else {
                        ui.t(
                            R.string.settings_download_size,
                            ui.t(m.labelRes),
                            ui.t(R.string.megabytes, done / 1_048_576),
                        )
                    }
                }
            },
            onDone = {
                act.runOnUiThread {
                    downloading = false
                    act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE)
                        .edit().putString("model", m.file).apply()
                    downloadStatus.text = ui.t(R.string.settings_download_done, ui.t(m.labelRes))
                    Toast.makeText(act, ui.t(R.string.settings_model_ready), Toast.LENGTH_SHORT).show()
                    refreshModels()
                }
            },
            onError = { msg ->
                act.runOnUiThread {
                    downloading = false
                    downloadStatus.text = ui.t(R.string.settings_download_failed, msg)
                    Toast.makeText(act, ui.t(R.string.settings_failed, msg), Toast.LENGTH_LONG).show()
                    refreshModels()
                }
            },
        )
    }

    /** Active ou coupe la bulle (préférence lue aussi au démarrage du service et de l'accessibilité). */
    private fun setBubble(on: Boolean) {
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
