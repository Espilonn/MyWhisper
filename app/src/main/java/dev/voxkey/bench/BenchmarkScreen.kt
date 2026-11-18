package dev.voxkey.bench

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * Comparatif local / API : les mêmes prises passées aux deux moteurs, chronométrées.
 * Trois prises de 10 s suffisent à voir l'ordre de grandeur et la régularité ; elles restent
 * en mémoire, rien n'est écrit sur le téléphone.
 */
class BenchmarkScreen(private val act: Activity, private val ui: UiKit) {

    private val page = ui.page(
        title = ui.t(R.string.bench_tab),
        subtitle = ui.t(R.string.bench_tab_sub),
    )
    val view = page.root
    private val body = page.body

    private val takes = arrayOfNulls<FloatArray>(TAKES)
    private val takeButtons = ArrayList<View>()
    private val takeCues = ArrayList<TextView>()
    private lateinit var compareBtn: View
    private lateinit var status: TextView
    private lateinit var results: TextView
    private var busy = false

    init {
        build()
        paint()
    }

    fun refresh() = paint()

    // ------------------------------------------------------------------ écran

    private fun build() {
        val audio = ui.card()
        audio.addView(ui.sectionTitle(R.drawable.ic_nav_bench, ui.t(R.string.bench_takes)))
        for (i in 0 until TAKES) {
            val button = ui.button(ui.t(R.string.bench_take, i + 1), primary = false) { record(i) }
            val cue = ui.meta(ui.t(R.string.bench_take_empty))
            audio.addFull(button, 12)
            audio.addFull(cue, 6)
            takeButtons += button
            takeCues += cue
        }
        body.addView(audio)

        val compare = ui.card()
        compare.addView(ui.sectionTitle(R.drawable.ic_nav_history, ui.t(R.string.bench_compare_card)))
        compareBtn = ui.button(ui.t(R.string.bench_compare), primary = true) { run() }
        compare.addFull(compareBtn, 12)
        status = ui.meta("")
        compare.addFull(status, 8)
        results = ui.text("", 12f, Ui.INK).apply { typeface = Typeface.MONOSPACE }
        compare.addFull(results, 10)
        body.addView(compare, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
    }

    private fun paint() {
        takes.forEachIndexed { i, pcm ->
            val cue = takeCues[i]
            val button = takeButtons[i] as android.widget.Button
            button.text = ui.t(if (pcm == null) R.string.bench_take else R.string.bench_redo, i + 1)
            cue.text = if (pcm == null) {
                ui.t(R.string.bench_take_empty)
            } else {
                val silent = Dsp.rms(pcm) <= Dsp.SILENCE_RMS
                ui.t(R.string.bench_take_ready, pcm.size / SAMPLE_RATE, pcm.size) +
                    if (silent) ui.t(R.string.bench_take_quiet) else ""
            }
        }
        setBusy(busy)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        takeButtons.forEach { it.isEnabled = !value }
        compareBtn.isEnabled = !value
    }

    // ------------------------------------------------------------------ prises

    private fun record(index: Int) {
        if (!SettingsScreen.micGranted(act)) {
            status.text = ui.t(R.string.bench_need_mic)
            return
        }
        setBusy(true)
        status.text = ui.t(R.string.bench_recording, index + 1)
        Thread {
            val outcome = runCatching { Audio.record(TAKE_SECONDS) }
            act.runOnUiThread {
                setBusy(false)
                status.text = ""
                outcome.onSuccess {
                    takes[index] = it
                    paint()
                }.onFailure { status.text = it.message ?: ui.t(R.string.bench_need_mic) }
            }
        }.start()
    }

    // ------------------------------------------------------------------ mesure

    /** Modèle que la dictée utilise : préférence `model`, sinon le plus petit fichier présent. */
    private fun localModel(): String? {
        val dir = act.getExternalFilesDir(null) ?: return null
        val name = act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE).getString("model", null)
        val file = name?.let { File(dir, it) }
        if (file != null && file.exists()) return file.absolutePath
        return dir.listFiles()?.filter { it.name.endsWith(".bin") }
            ?.minByOrNull { it.length() }?.absolutePath
    }

    private fun run() {
        val chosen = takes.mapIndexedNotNull { i, pcm -> pcm?.let { i to it } }
        if (chosen.isEmpty()) {
            status.text = ui.t(R.string.bench_need_audio)
            return
        }
        setBusy(true)
        status.text = ui.t(R.string.bench_measuring)
        results.text = ""

        Thread {
            val lines = ArrayList<String>()
            fun post(line: String? = null) {
                if (line != null) lines += line
                act.runOnUiThread { results.text = lines.joinToString("\n") }
            }

            val path = localModel()
            val parakeet = path != null && isParakeetModel(File(path).name)
            val localReady = when {
                path == null -> false
                parakeet -> ParakeetEngine.ensure(path)
                else -> WhisperEngine.ensure(path)
            }
            if (path == null) post(ui.t(R.string.bench_no_local))
            else if (!localReady) post(ui.t(R.string.bench_local_failed))

            val cloud = RemoteEngine.key(act).isNotBlank()
            if (!cloud) post(ui.t(R.string.bench_no_key))

            var localTotal = 0L
            var cloudTotal = 0L
            var measured = 0

            chosen.forEach { (index, pcm) ->
                post(ui.t(R.string.bench_take_head, index + 1, pcm.size / SAMPLE_RATE))
                if (localReady) {
                    val t0 = System.nanoTime()
                    val outcome = runCatching {
                        if (parakeet) ParakeetEngine.transcribe(pcm) else WhisperEngine.transcribe(pcm, "fr", 4)
                    }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    localTotal += ms
                    measured++
                    post(
                        outcome.fold(
                            { row("local", ms, it) },
                            { ui.t(R.string.bench_row_error, "local", it.message.orEmpty().take(80)) },
                        )
                    )
                }
                if (cloud) {
                    val t0 = System.nanoTime()
                    val outcome = runCatching { RemoteEngine.transcribe(act, pcm) }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val name = if (RemoteEngine.provider(act) == RemoteEngine.OPENROUTER) "OpenRouter" else "Groq"
                    outcome.onSuccess { cloudTotal += ms }
                    post(
                        outcome.fold(
                            { row(name, ms, it) },
                            { ui.t(R.string.bench_row_error, name, it.message.orEmpty().take(80)) },
                        )
                    )
                }
            }

            if (localReady && cloud && measured > 0) {
                val local = localTotal / measured
                val remote = cloudTotal / measured
                post()
                post(ui.t(R.string.bench_average, local, remote))
                post(
                    ui.t(
                        R.string.bench_verdict,
                        if (local <= remote) "local" else "API",
                        Math.abs(remote - local),
                    )
                )
            }

            act.runOnUiThread {
                setBusy(false)
                status.text = ui.t(R.string.bench_done)
            }
        }.start()
    }

    /** Une ligne de mesure, alignée en chasse fixe : moteur, durée, longueur du texte. */
    private fun row(engine: String, ms: Long, text: String) =
        String.format(Locale.US, "  %-10s %6d ms  %4d car.", engine, ms, text.length)

    private companion object {
        const val TAKES = 3
        const val TAKE_SECONDS = 10
        const val SAMPLE_RATE = 16_000
    }
}
