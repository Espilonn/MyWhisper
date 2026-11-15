package dev.voxkey.bench

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

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
    private lateinit var setupProgress: TextView
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

        val model = ui.card()
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
        modelEmpty = ui.meta(
            ui.t(R.string.settings_model_empty),
        )
        model.addFull(modelEmpty, 8)
        downloadBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        model.addFull(downloadBox, 6)
        downloadStatus = ui.meta("")
        model.addFull(downloadStatus, 12)
        content.addFull(model, 16)

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
        modelRefresh.setTextColor(Ui.ACCENT)
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
    }

    private fun refreshModels() {
        val files = act.getExternalFilesDir(null)?.listFiles()
            ?.filter { it.name.endsWith(".bin") || it.name.endsWith(".gguf") }
            ?.sortedBy { it.length() }
            ?: emptyList()

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
