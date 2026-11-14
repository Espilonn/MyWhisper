package dev.voxkey.bench

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.InputStream

/** 16 kHz mono PCM16 capture and the matching WAV decode. */
object Audio {
    const val RATE = 16000

    /** Records [seconds] of 16 kHz mono PCM16, returned as floats in [-1, 1]. */
    fun record(seconds: Int): FloatArray {
        val total = RATE * seconds
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, RATE) * 2
        )
        val pcm = ShortArray(total)
        var filled = 0
        try {
            rec.startRecording()
            while (filled < total) {
                val n = rec.read(pcm, filled, minOf(total - filled, RATE))
                if (n <= 0) break
                filled += n
            }
        } finally {
            rec.stop()
            rec.release()
        }
        val out = FloatArray(filled)
        for (i in 0 until filled) out[i] = pcm[i] / 32768f
        return out
    }

    /**
     * Decodes a WAV file. Only 16 kHz mono PCM16 is accepted: anything else is a
     * setup mistake in the benchmark, not worth resampling.
     */
    fun decodeWav(input: InputStream): FloatArray {
        val bytes = input.readBytes()
        require(bytes.size > 44) { "WAV too short" }
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") { "not a WAV file" }

        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        var dataOff = -1
        var dataLen = 0

        var i = 12
        while (i + 8 <= bytes.size) {
            val id = ascii(bytes, i, 4)
            val size = le32(bytes, i + 4)
            val body = i + 8
            when (id) {
                "fmt " -> {
                    format = le16(bytes, body)
                    channels = le16(bytes, body + 2)
                    rate = le32(bytes, body + 4)
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    dataOff = body
                    dataLen = minOf(size, bytes.size - body)
                }
            }
            i = body + size + (size and 1)
        }

        require(format == 1 && bits == 16) { "need PCM16 (format=$format bits=$bits)" }
        require(channels == 1) { "need mono (channels=$channels)" }
        require(rate == RATE) { "need ${RATE} Hz (got $rate)" }
        require(dataOff >= 0) { "no data chunk" }

        val n = dataLen / 2
        return FloatArray(n) {
            val lo = bytes[dataOff + it * 2].toInt() and 0xff
            val hi = bytes[dataOff + it * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    private fun ascii(b: ByteArray, off: Int, len: Int) =
        String(b, off, len, Charsets.US_ASCII)

    private fun le16(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, off: Int) =
        le16(b, off) or (le16(b, off + 2) shl 16)
}
