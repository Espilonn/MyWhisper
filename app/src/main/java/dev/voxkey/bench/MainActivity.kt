package dev.voxkey.bench

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Debug
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * Coquille à 5 onglets : Accueil, Réglages, Mots-clés, Historique, Dictionnaire.
 * Au premier lancement, la mise en place n'est pas finie : on atterrit sur Réglages,
 * qui sert d'écran de bienvenue.
 *
 * Le banc d'essai vit dans Réglages mais reste invisible par défaut : sept appuis sur
 * « Version » le débloquent (préférence `bench_visible`). C'est l'outil qui mesure les
 * modèles, pas une fonction de l'app.
 */
class MainActivity : Activity() {

    private lateinit var ui: UiKit
    private lateinit var settings: SettingsScreen
    private lateinit var content: FrameLayout
    private val screens = mutableListOf<View>()
    private val refreshAll = mutableListOf<() -> Unit>()
    private val navIcons = mutableListOf<ImageView>()
    private val navLabels = mutableListOf<TextView>()
    private val navPills = mutableListOf<FrameLayout>()
    private var currentTab = 0
    private val fMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val fSans = Typeface.create("sans-serif", Typeface.NORMAL)

    // --- banc d'essai (construit seulement quand il est débloqué)
    private var handle = 0L
    private var loadedModel: String? = null
    private var pcm = FloatArray(0)
    private var durationSec = 10
    private var benchModel: File? = null
    private var benchBuilt = false

    private lateinit var modelsBox: RadioGroup
    private lateinit var etThreads: EditText
    private lateinit var tvAudio: TextView
    private lateinit var tvMetrics: TextView
    private lateinit var tvText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnTranscribe: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        ui = UiKit(this)

        val home = HomeScreen(this, ui, onOpenHistory = { select(3) }, onOpenSettings = { select(1) })
        settings = SettingsScreen(this, ui)
        val keywords = KeywordsScreen(this, ui)
        val history = HistoryScreen(this, ui)
        val dictionary = DictionaryScreen(this, ui)

        content = FrameLayout(this)
        screens += listOf(home.view, settings.view, keywords.view, history.view, dictionary.view)
        screens.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        refreshAll += listOf(home::refresh, settings::refresh, keywords::refresh, history::refresh, dictionary::refresh)

        // Le banc n'est posé que si la préférence le demande : aucune trace au premier lancement.
        settings.onBenchVisibilityChanged = { showBench(it) }
        showBench(SettingsScreen.benchVisible(this))

        // Un accent choisi dans Réglages repeint l'app entière, sans redémarrage.
        settings.onAccentChanged = { restyle() }

        // Barre d'onglets sur le fond de l'écran, détachée du contenu par un simple filet :
        // pas de bloc opaque collé en bas.
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BG)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        listOf(
            Triple(R.drawable.ic_nav_home, getString(R.string.nav_home), 0),
            Triple(R.drawable.ic_nav_settings, getString(R.string.nav_settings), 1),
            Triple(R.drawable.ic_nav_keywords, getString(R.string.nav_keywords), 2),
            Triple(R.drawable.ic_nav_history, getString(R.string.nav_history), 3),
            Triple(R.drawable.ic_nav_dictionary, getString(R.string.nav_dictionary), 4),
        ).forEach { (res, label, index) -> nav.addView(navItem(res, label, index)) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(this@MainActivity).apply { setBackgroundColor(Ui.LINE) },
                LinearLayout.LayoutParams(MATCH, ui.dp(1)))
            addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        setContentView(root)

        // targetSdk 35 : la fenêtre va bord à bord. On écarte nous-mêmes le contenu de la
        // barre d'état et la barre d'onglets du geste système.
        edgeToEdge()
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            root.setPadding(0, bars.top, 0, 0)
            nav.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6) + bars.bottom)
            insets
        }

        // Sans les trois droits, l'app ne peut pas dicter : l'écran de mise en place s'ouvre.
        select(if (ready()) 0 else 1)

        // La bulle n'a pas besoin qu'on la lance à la main : elle vit tant que
        // la permission est là et que l'utilisateur ne l'a pas désactivée.
        // En mode greffé, seul le service d'accessibilité est nécessaire : exiger en plus
        // `canDrawOverlays` empêchait la relance depuis l'app. `onCreate` s'arrête de lui-même
        // si ni l'accessibilité ni la permission ne sont là.
        if (OverlayService.isEnabled(this)) {
            startService(Intent(this, OverlayService::class.java))
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_PERM)
        }
    }

    override fun onResume() {
        super.onResume()
        // Une dictée a pu être enregistrée pendant que l'app était en arrière-plan.
        refreshAll.forEach { it() }
    }

    override fun onDestroy() {
        if (handle != 0L) Whisper.nativeFree(handle)
        super.onDestroy()
    }

    /** Bord à bord : le fond de l'app passe sous les barres système, le contenu s'écarte tout seul. */
    @Suppress("DEPRECATION")
    private fun edgeToEdge() = window.setDecorFitsSystemWindows(false)

    /** Nouvel accent : on reconstruit le contenu des cinq écrans et on repeint la barre. */
    private fun restyle() {
        refreshAll.forEach { it() }
        select(currentTab)
    }

    private fun ready() =
        Settings.canDrawOverlays(this) && SettingsScreen.isAccessibilityOn(this) && SettingsScreen.micGranted(this)

    // ------------------------------------------------------------------ onglets

    /**
     * Un onglet : la pastille d'état actif derrière l'icône, le libellé dessous.
     * Cible tactile de toute la largeur du cinquième, 56 dp de haut.
     */
    private fun navItem(res: Int, label: String, index: Int): LinearLayout {
        val icon = ui.icon(res, Ui.MUTED, 22)
        val pill = FrameLayout(this).apply {
            background = null
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(32))
            addView(icon, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
        }
        val text = ui.micro(label).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        navIcons += icon
        navLabels += text
        navPills += pill
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(6), 0, ui.dp(6))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(pill)
            addView(text, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ui.dp(4) })
            setOnClickListener { select(index) }
        }
    }

    private fun select(index: Int) {
        currentTab = index
        screens.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        navPills.forEachIndexed { i, pill ->
            pill.background = if (i == index) ui.rounded(Ui.ACCENT_BG, 99) else null
        }
        navIcons.forEachIndexed { i, icon ->
            icon.setColorFilter(if (i == index) Ui.ACCENT else Ui.MUTED)
        }
        navLabels.forEachIndexed { i, label ->
            label.setTextColor(if (i == index) Ui.INK else Ui.MUTED)
            label.typeface = if (i == index) fMedium else fSans
        }
        // Un ajout ou une suppression sur un autre onglet doit se voir en revenant.
        refreshAll.getOrNull(index)?.invoke()
    }

    // ------------------------------------------------------------- banc d'essai

    /** Pose la carte au bas de Réglages la première fois, puis la montre ou la cache. */
    private fun showBench(on: Boolean) {
        if (on && !benchBuilt) {
            settings.benchSlot.addFull(benchCard(), 16)
            benchBuilt = true
        }
        settings.benchSlot.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Carte du banc d'essai : modèle, threads, durée, capture/WAV, transcription, métriques. */
    private fun benchCard(): View {
        val card = ui.card()
        card.addView(ui.sectionTitle(R.drawable.ic_words, getString(R.string.bench_title)))
        card.addFull(ui.text(ui.t(R.string.bench_backend, Whisper.nativeBackend()), 12f, Ui.MUTED), 8)

        card.addFull(ui.text(ui.t(R.string.bench_model), 13f, Ui.MUTED, bold = true), 14)
        modelsBox = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        card.addFull(modelsBox, 4)
        card.addFull(ui.button(ui.t(R.string.bench_import), primary = false) { pickFile(REQ_MODEL, "*/*") }, 8)

        card.addFull(ui.text(ui.t(R.string.bench_threads), 13f, Ui.MUTED, bold = true), 14)
        etThreads = EditText(this).apply {
            setText("4")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Ui.INK)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            background = ui.rounded(Ui.BG, 14)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        }
        card.addFull(etThreads, 4)

        card.addFull(ui.text(ui.t(R.string.bench_duration), 13f, Ui.MUTED, bold = true), 14)
        card.addFull(
            ui.segmented(
                listOf(ui.t(R.string.bench_seconds, 10), ui.t(R.string.bench_seconds, 30)),
                0,
            ) { durationSec = if (it == 1) 30 else 10 },
            4,
        )

        btnRecord = ui.button(ui.t(R.string.bench_record), primary = false) { record() }
        card.addFull(btnRecord, 14)
        card.addFull(ui.button(ui.t(R.string.bench_pick_wav), primary = false) { pickFile(REQ_WAV, "audio/*") }, 8)

        tvAudio = ui.text(ui.t(R.string.bench_audio_none), 13f, Ui.MUTED)
        card.addFull(tvAudio, 10)

        btnTranscribe = ui.button(ui.t(R.string.bench_transcribe), primary = true) { transcribe() }
        card.addFull(btnTranscribe, 10)

        tvMetrics = ui.text("", 12f, Ui.MUTED).apply { typeface = Typeface.MONOSPACE }
        card.addFull(tvMetrics, 12)

        tvText = ui.text("", 14f, Ui.INK).apply { setTextIsSelectable(true) }
        card.addFull(tvText, 8)

        refreshBenchModels()
        return card
    }

    /** Le banc liste les modèles là où l'app les cherche : racine des fichiers + ancien sous-dossier models/. */
    private fun modelFiles(): List<File> {
        val root = getExternalFilesDir(null) ?: return emptyList()
        return (root.listFiles()?.toList().orEmpty() + File(root, "models").listFiles().orEmpty())
            .filter { it.isFile && it.name.endsWith(".bin") }
            .sortedBy { it.name }
    }

    private fun refreshBenchModels() {
        modelsBox.removeAllViews()
        val files = modelFiles()
        if (files.isEmpty()) {
            benchModel = null
            modelsBox.addView(ui.text(ui.t(R.string.bench_no_model, getExternalFilesDir(null)?.path.toString()), 13f, Ui.MUTED))
            return
        }
        val saved = getSharedPreferences("voxkey", MODE_PRIVATE).getString("model", null)
        var checkedId = -1
        files.forEach { f ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${f.name}  (${ui.t(R.string.megabytes, "%.1f".format(f.length() / 1e6))})"
                setTextColor(Ui.INK)
                setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT))
                tag = f
            }
            modelsBox.addView(rb)
            if (f.name == saved) checkedId = rb.id
        }
        if (checkedId == -1) checkedId = modelsBox.getChildAt(0).id
        modelsBox.check(checkedId)
        benchModel = files.firstOrNull { it.name == saved } ?: files.first()
        modelsBox.setOnCheckedChangeListener { group, id ->
            benchModel = group.findViewById<RadioButton>(id)?.tag as? File
        }
    }

    private fun pickFile(req: Int, mime: String) {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
            },
            req
        )
    }

    @Deprecated("framework activity results are enough for a bench tool")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQ_MODEL -> {
                Thread {
                    runCatching {
                        val name = queryName(uri) ?: "model.bin"
                        val out = File(getExternalFilesDir(null), name)
                        contentResolver.openInputStream(uri)!!.use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        name
                    }
                    runOnUiThread {
                        refreshBenchModels()
                        settings.refresh()
                    }
                }.start()
            }
            REQ_WAV -> {
                Thread {
                    val decoded = runCatching {
                        contentResolver.openInputStream(uri)!!.use { Audio.decodeWav(it) }
                    }
                    runOnUiThread {
                        decoded.onSuccess { setAudio(it, queryName(uri) ?: "wav") }
                            .onFailure { tvAudio.text = ui.t(R.string.bench_audio, it.message.toString()) }
                    }
                }.start()
            }
        }
    }

    private fun queryName(uri: android.net.Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }

    // ----------------------------------------------------------------- audio

    private fun record() {
        if (SettingsScreen.micGranted(this).not()) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_PERM)
            return
        }
        val seconds = durationSec
        setBusy(true)
        tvAudio.text = ui.t(R.string.bench_audio_recording, seconds)
        Thread {
            val result = runCatching { Audio.record(seconds) }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { setAudio(it, ui.t(R.string.bench_source_mic, seconds)) }
                    .onFailure { tvAudio.text = ui.t(R.string.bench_audio, it.message.toString()) }
            }
        }.start()
    }

    private fun setAudio(samples: FloatArray, source: String) {
        pcm = samples
        tvAudio.text = ui.t(
            R.string.bench_audio_samples,
            source,
            samples.size / Audio.RATE.toDouble(),
            samples.size,
        )
    }

    // ------------------------------------------------------------- transcribe

    private fun transcribe() {
        val model = benchModel
        if (model == null) { tvText.text = ui.t(R.string.bench_err_model); return }
        if (pcm.isEmpty()) { tvText.text = ui.t(R.string.bench_err_audio); return }

        val threads = etThreads.text.toString().toIntOrNull() ?: 4
        setBusy(true)
        tvMetrics.text = ui.t(R.string.bench_running)
        val samples = pcm

        Thread {
            val outcome = runCatching {
                if (handle == 0L || loadedModel != model.path) {
                    if (handle != 0L) Whisper.nativeFree(handle)
                    handle = Whisper.nativeInit(model.path, threads)
                    loadedModel = if (handle != 0L) model.path else null
                    if (handle == 0L) error(ui.t(R.string.bench_err_load, model.name))
                }
                JSONObject(Whisper.nativeRun(handle, samples, "fr"))
            }
            runOnUiThread { report(outcome, model, threads, samples.size) }
        }.start()
    }

    private fun report(
        outcome: Result<JSONObject>,
        model: File,
        threads: Int,
        sampleCount: Int
    ) {
        setBusy(false)
        val json = outcome.getOrElse {
            tvMetrics.text = ui.t(R.string.bench_failed)
            tvText.text = it.message
            return
        }
        json.optString("error").takeIf { it.isNotEmpty() }?.let {
            tvMetrics.text = ui.t(R.string.bench_failed)
            tvText.text = it
            return
        }

        val audioSec = sampleCount / Audio.RATE.toDouble()
        val wallMs = json.optDouble("wallMs")
        val encodeMs = json.optDouble("encodeMs")
        val decodeMs = json.optDouble("decodeMs")
        val rtf = wallMs / (audioSec * 1000.0)
        val peakKb = peakRssKb()
        val text = json.optString("text")

        tvMetrics.text = ui.t(
            R.string.bench_metrics,
            audioSec, wallMs, encodeMs, decodeMs, rtf,
            ui.t(R.string.megabytes, peakKb / 1024),
            ui.t(R.string.megabytes, Debug.getNativeHeapAllocatedSize() / (1024 * 1024)),
            Whisper.nativeBackend(), threads,
        )
        tvText.text = text

        val line = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("model", model.name)
            .put("lang", "fr")
            .put("threads", threads)
            .put("backend", Whisper.nativeBackend())
            .put("audioSec", audioSec)
            .put("wallMs", wallMs)
            .put("encodeMs", encodeMs)
            .put("decodeMs", decodeMs)
            .put("rtf", rtf)
            .put("peakRssKb", peakKb)
            .put("chars", text.length)
            .put("text", text)
            .toString()

        runCatching {
            File(getExternalFilesDir(null), "bench.jsonl").appendText(line + "\n")
        }
    }

    /** Peak resident set size of this process, in kB. */
    private fun peakRssKb(): Int =
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun setBusy(value: Boolean) {
        btnRecord.isEnabled = !value
        btnTranscribe.isEnabled = !value
    }

    companion object {
        private const val REQ_MODEL = 101
        private const val REQ_WAV = 102
        private const val REQ_PERM = 103
    }
}
