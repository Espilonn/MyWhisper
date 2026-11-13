package dev.voxkey.bench

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/** Enregistre du PCM 16 kHz mono et le rend en float [-1, 1]. */
class Recorder {

    private val sampleRate = 16000
    private val chunks = ArrayList<ShortArray>()
    private var total = 0
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

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
        chunks.clear()
        total = 0
        running = true
        ar.startRecording()

        worker = thread {
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

    fun stop(): FloatArray {
        running = false
        worker?.join()
        record?.stop()
        record?.release()
        record = null

        val out = FloatArray(total)
        var i = 0
        synchronized(chunks) {
            for (c in chunks) for (s in c) out[i++] = s / 32768f
        }
        return out
    }
}
