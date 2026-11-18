package dev.voxkey.bench

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/** Enregistre du PCM 16 kHz mono en continu et expose un instantané en float [-1, 1]. */
class Recorder {

    private val sampleRate = 16000
    private val chunks = ArrayList<ShortArray>()
    private var total = 0
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    val sampleCount: Int get() = synchronized(chunks) { total }

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sampleRate * 2)
        )
        record = ar
        synchronized(chunks) {
            chunks.clear()
            total = 0
        }
        running = true
        ar.startRecording()

        worker = thread(name = "voxkey-rec") {
            val chunk = ShortArray(sampleRate / 10) // 100 ms
            while (running) {
                val n = ar.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(chunks) {
                        chunks.add(chunk.copyOf(n))
                        total += n
                    }
                }
            }
        }
    }

    /** Copie des `maxSamples` derniers échantillons (ou tout si plus court). */
    fun snapshot(maxSamples: Int = Int.MAX_VALUE): FloatArray {
        val out: FloatArray
        synchronized(chunks) {
            val count = minOf(total, maxSamples)
            out = FloatArray(count)
            // on remplit depuis la fin
            var written = 0
            var ci = chunks.size - 1
            var offsetInChunk = 0
            while (written < count && ci >= 0) {
                val c = chunks[ci]
                val available = c.size - offsetInChunk
                val need = count - written
                val take = minOf(available, need)
                val start = c.size - (available) // début du segment restant
                for (i in 0 until take) {
                    out[count - written - take + i] = c[start + i] / 32768f
                }
                written += take
                ci--
                offsetInChunk = 0
            }
        }
        return out
    }

    fun stop(): FloatArray {
        running = false
        // Arrêter la capture AVANT le `join` : un `read` en cours ne rendrait pas la main, et
        // `stop()` est appelé sur le thread principal — l'interface se figeait.
        runCatching { record?.stop() }
        worker?.join(500)
        runCatching { record?.release() }
        record = null
        return snapshot()
    }
}
