package dev.voxkey.bench

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var modelSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var recordButton: Button

    private val recorder = Recorder()
    private var pcm: FloatArray? = null
    private var handle = 0L
    private var loadedModel: String? = null
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        modelSpinner = Spinner(this)
        val refresh = Button(this).apply {
            text = "Rafraîchir les modèles"
            setOnClickListener { listModels() }
        }

        recordButton = Button(this).apply {
            text = "Enregistrer"
            setOnClickListener { toggleRecording() }
        }

        val transcribe = Button(this).apply {
            text = "Transcrire"
            setOnClickListener { transcribe() }
        }

        result = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }

        listOf<android.view.View>(
            TextView(this).apply { text = "Modèle"; textSize = 14f },
            modelSpinner, refresh, recordButton, transcribe, status, result
        ).forEach { root.addView(it) }

        setContentView(root)
        listModels()
    }

    private fun modelsDir(): File? = getExternalFilesDir(null)

    private fun listModels() {
        val files = modelsDir()?.listFiles()
            ?.filter { it.name.endsWith(".bin") }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (files.isEmpty()) {
            modelSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("aucun .bin trouvé")
            )
            status.text = "Aucun modèle.\nPousse un .bin avec:\n" +
                "adb push ggml-small-q5_1.bin " +
                "/sdcard/Android/data/dev.voxkey.bench/files/"
        } else {
            modelSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                files.map { "${it.name} (${it.length() / 1_048_576} Mo)" }
            )
        }
    }

    private fun selectedModel(): File? {
        val files = modelsDir()?.listFiles()
            ?.filter { it.name.endsWith(".bin") }
            ?.sortedBy { it.name }
            ?: return null
        val idx = modelSpinner.selectedItemPosition
        return files.getOrNull(idx)
    }

    private fun toggleRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        if (!recording) {
            recorder.start()
            recording = true
            recordButton.text = "Arrêter"
            status.text = "Enregistrement…"
        } else {
            pcm = recorder.stop()
            recording = false
            recordButton.text = "Enregistrer"
            val secs = (pcm!!.size / 16000f)
            status.text = String.format(Locale.US, "Audio: %.1f s (%d échantillons)", secs, pcm!!.size)
        }
    }

    private fun transcribe() {
        val audio = pcm ?: run { status.text = "Rien à transcrire."; return }
        val model = selectedModel() ?: run { status.text = "Aucun modèle."; return }

        transcribeButtonEnabled(false)
        thread {
            try {
                val initStart = System.currentTimeMillis()
                if (handle == 0L || loadedModel != model.absolutePath) {
                    if (handle != 0L) Whisper.nativeFree(handle)
                    handle = Whisper.nativeInit(model.absolutePath)
                    loadedModel = model.absolutePath
                }
                val initMs = System.currentTimeMillis() - initStart
                if (handle == 0L) {
                    ui { status.text = "Échec du chargement du modèle."; transcribeButtonEnabled(true) }
                    return@thread
                }

                val diag = FloatArray(2)
                val text = Whisper.nativeRun(handle, audio, "fr", 4, diag)

                val audioSec = audio.size / 16000f
                val rtf = (diag[0] / 1000f) / audioSec
                val hwmKb = peakMemoryKb()

                val line = String.format(
                    Locale.US,
                    "{\"model\":\"%s\",\"audio_s\":%.2f,\"wall_ms\":%.0f,\"encode_ms\":%.0f,\"init_ms\":%d,\"rtf\":%.2f,\"peak_mb\":%d,\"threads\":4,\"backend\":\"cpu\",\"text\":\"%s\"}",
                    model.name, audioSec, diag[0], diag[1], initMs, rtf, hwmKb / 1024,
                    text.replace("\"", "'").replace("\n", " ")
                )
                val out = File(getExternalFilesDir(null), "bench.jsonl")
                out.appendText(line + "\n")

                ui {
                    transcribeButtonEnabled(true)
                    status.text = String.format(
                        Locale.US,
                        "audio %.1f s | mur %.0f ms | encode %.0f ms | init %d ms | RTF %.2f | pic %d Mo",
                        audioSec, diag[0], diag[1], initMs, rtf, hwmKb / 1024
                    )
                    result.text = text
                }
            } catch (t: Throwable) {
                ui {
                    transcribeButtonEnabled(true)
                    status.text = "Erreur: ${t.message}"
                }
            }
        }
    }

    private fun transcribeButtonEnabled(v: Boolean) {
        ui { result.text = if (v) result.text else "…" }
    }

    private fun peakMemoryKb(): Long {
        return try {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("VmHWM:") }
                ?.filter { it.isDigit() }
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
