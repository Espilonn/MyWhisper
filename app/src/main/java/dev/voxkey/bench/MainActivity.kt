package dev.voxkey.bench

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Debug
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/**
 * Coquille à 5 onglets : Accueil, Réglages, Mots-clés, Historique, Dictionnaire.
 * L'onglet Réglages porte aussi le banc d'essai (modèle, threads, capture, transcription),
 * qui reste la seule façon de tester whisper.cpp depuis l'app.
 */
class MainActivity : Activity() {

    private lateinit var ui: UiKit
    private lateinit var home: HomeScreen
    private lateinit var settings: SettingsScreen
    private lateinit var content: FrameLayout
    private val screens = mutableListOf<View>()
    private val navIcons = mutableListOf<ImageView>()
    private val navLabels = mutableListOf<TextView>()

    // --- banc d'essai
    private var handle = 0L
    private var loadedModel: String? = null
    private var pcm = FloatArray(0)
    private var durationSec = 10
    private var benchModel: File? = null

    private lateinit var modelsBox: RadioGroup
    private lateinit var etThreads: EditText
    private lateinit var tvAudio: TextView
    private lateinit var tvMetrics: TextView
    private lateinit var tvText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnTranscribe: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        home = HomeScreen(this, ui, onOpenHistory = { select(3) }, onOpenSettings = { select(1) })
        settings = SettingsScreen(this, ui)
        val keywords = KeywordsScreen(this, ui)
        val history = HistoryScreen(this, ui)
        val dictionary = DictionaryScreen(this, ui)

        content = FrameLayout(this)
        screens += listOf(home.view, settings.view, keywords.view, history.view, dictionary.view)
        screens.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }

        // Le banc d'essai vit dans l'onglet Réglages, sous les cartes de mise en place.
        (settings.view.getChildAt(0) as LinearLayout).addFull(benchCard(), 16)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.CARD)
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
        }
        listOf(
            Triple(R.drawable.ic_nav_home, "Accueil", 0),
            Triple(R.drawable.ic_nav_settings, "Réglages", 1),
            Triple(R.drawable.ic_nav_keywords, "Mots-clés", 2),
            Triple(R.drawable.ic_nav_history, "Historique", 3),
            Triple(R.drawable.ic_nav_dictionary, "Dictionnaire", 4),
        ).forEach { (res, label, index) -> nav.addView(navItem(res, label, index)) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        setContentView(root)

        select(0)

        // La bulle n'a pas besoin qu'on la lance à la main : elle vit tant que
        // la permission est là et que l'utilisateur ne l'a pas désactivée.
        if (OverlayService.isEnabled(this) && android.provider.Settings.canDrawOverlays(this)) {
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
        home.refresh()
        settings.refresh()
    }

    override fun onDestroy() {
        if (handle != 0L) Whisper.nativeFree(handle)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ onglets

    private fun navItem(res: Int, label: String, index: Int): LinearLayout {
        val icon = ui.icon(res, Ui.MUTED, 22)
        val text = ui.text(label, 10f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        navIcons += icon
        navLabels += text
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(9), 0, ui.dp(7))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(icon)
            addView(text, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ui.dp(3) })
            setOnClickListener { select(index) }
        }
    }

    private fun select(index: Int) {
        screens.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        navIcons.forEachIndexed { i, icon ->
            icon.setColorFilter(if (i == index) Ui.ACCENT else Ui.MUTED)
        }
        navLabels.forEachIndexed { i, label ->
            label.setTextColor(if (i == index) Ui.INK else Ui.MUTED)
            label.typeface = if (i == index) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ---------------------------------------------------------------- banc

    /** Carte du banc d'essai : modèle, threads, durée, capture/WAV, transcription, métriques. */
    private fun benchCard(): View {
        val card = ui.card()
        card.addView(ui.sectionTitle(R.drawable.ic_words, "Banc d'essai"))
        card.addFull(ui.text("backend : ${Whisper.nativeBackend()}", 12f, Ui.MUTED), 8)

        card.addFull(ui.text("Modèle", 13f, Ui.MUTED, bold = true), 14)
        modelsBox = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        card.addFull(modelsBox, 4)
        card.addFull(ui.button("Importer un modèle…", primary = false) { pickFile(REQ_MODEL, "*/*") }, 8)

        card.addFull(ui.text("Threads", 13f, Ui.MUTED, bold = true), 14)
        etThreads = EditText(this).apply {
            setText("4")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Ui.INK)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            background = ui.rounded(Ui.BG, 14)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        }
        card.addFull(etThreads, 4)

        card.addFull(ui.text("Durée", 13f, Ui.MUTED, bold = true), 14)
        card.addFull(ui.segmented(listOf("10 s", "30 s"), 0) { durationSec = if (it == 1) 30 else 10 }, 4)

        btnRecord = ui.button("Enregistrer", primary = false) { record() }
        card.addFull(btnRecord, 14)
        card.addFull(ui.button("Choisir un WAV…", primary = false) { pickFile(REQ_WAV, "audio/*") }, 8)

        tvAudio = ui.text("audio : aucun", 13f, Ui.MUTED)
        card.addFull(tvAudio, 10)

        btnTranscribe = ui.button("Transcrire", primary = true) { transcribe() }
        card.addFull(btnTranscribe, 10)

        tvMetrics = ui.text("", 12f, Ui.MUTED).apply { typeface = Typeface.MONOSPACE }
        card.addFull(tvMetrics, 12)

        tvText = ui.text("", 14f, Ui.INK).apply { setTextIsSelectable(true) }
        card.addFull(tvText, 8)

        refreshBenchModels()
        return card
    }

    // ---------------------------------------------------------------- modèles

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
            modelsBox.addView(ui.text("Aucun modèle. Utilise Importer (dossier ${getExternalFilesDir(null)?.path}).", 13f, Ui.MUTED))
            return
        }
        val saved = getSharedPreferences("voxkey", MODE_PRIVATE).getString("model", null)
        var checkedId = -1
        files.forEach { f ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${f.name}  (${"%.1f".format(f.length() / 1e6)} Mo)"
                setTextColor(Ui.INK)
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
                            .onFailure { tvAudio.text = "audio : ${it.message}" }
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
        tvAudio.text = "audio : enregistrement ${seconds} s…"
        Thread {
            val result = runCatching { Audio.record(seconds) }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { setAudio(it, "micro ${seconds} s") }
                    .onFailure { tvAudio.text = "audio : ${it.message}" }
            }
        }.start()
    }

    private fun setAudio(samples: FloatArray, source: String) {
        pcm = samples
        tvAudio.text = "audio : $source, %.2f s (${samples.size} échantillons)".format(
            samples.size / Audio.RATE.toDouble()
        )
    }

    // ------------------------------------------------------------- transcribe

    private fun transcribe() {
        val model = benchModel
        if (model == null) { tvText.text = "Choisis d'abord un modèle"; return }
        if (pcm.isEmpty()) { tvText.text = "Enregistre ou choisis un audio d'abord"; return }

        val threads = etThreads.text.toString().toIntOrNull() ?: 4
        setBusy(true)
        tvMetrics.text = "en cours…"
        val samples = pcm

        Thread {
            val outcome = runCatching {
                if (handle == 0L || loadedModel != model.path) {
                    if (handle != 0L) Whisper.nativeFree(handle)
                    handle = Whisper.nativeInit(model.path, threads)
                    loadedModel = if (handle != 0L) model.path else null
                    if (handle == 0L) error("chargement du modèle impossible : ${model.name}")
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
            tvMetrics.text = "échec"
            tvText.text = it.message
            return
        }
        json.optString("error").takeIf { it.isNotEmpty() }?.let {
            tvMetrics.text = "échec"
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

        tvMetrics.text = """
            audio     %.2f s
            wall      %.0f ms  (encode %.0f / decode %.0f)
            RTF       %.3f
            peak RSS  %d Mo
            native    %d Mo (alloué)
            backend   %s · %d threads
        """.trimIndent().format(
            audioSec, wallMs, encodeMs, decodeMs, rtf,
            peakKb / 1024, Debug.getNativeHeapAllocatedSize() / (1024 * 1024),
            Whisper.nativeBackend(), threads
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
