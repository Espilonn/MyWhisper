package dev.voxkey.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmark for the Parakeet path on real hardware.
 *
 * Run with:
 *   adb shell am instrument -w \
 *     -e class dev.voxkey.bench.ParakeetBench dev.voxkey.bench.test/androidx.test.runner.AndroidJUnitRunner
 *
 * One model per process on purpose: 415 MB of weights plus ggml buffers will not
 * fit twice, and a SIGKILL mid-run is indistinguishable from a real crash.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetBench {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** bench.wav lives in the test APK, not the app APK. */
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    private fun model(name: String) = java.io.File(ctx.getExternalFilesDir(null), name)

    @Test
    fun benchParakeet() {
        val model = model(MODEL)
        check(model.exists()) { "missing model: ${model.absolutePath}" }
        val pcm = readWav(testCtx.assets.open("bench.wav"))
        val audioMs = pcm.size * 1000L / 16_000L

        val t0 = System.nanoTime()
        val handle = Parakeet.nativeInit(model.absolutePath, THREADS)
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        check(handle != 0L) { "parakeet_init failed" }

        try {
            var wall = 0L
            var enc = 0L
            var dec = 0L
            repeat(RUNS) { i ->
                val r = JSONObject(Parakeet.nativeRun(handle, pcm))
                wall = r.optLong("wallMs")
                enc = r.optLong("encodeMs")
                dec = r.optLong("decodeMs")
                if (i == 0) Log.i(TAG, "text=[${r.optString("text")}]")
            }
            val rtf = wall.toDouble() / audioMs
            Log.i(
                TAG,
                "BENCH model=$MODEL threads=$THREADS runs=$RUNS audioMs=$audioMs " +
                    "loadMs=$loadMs wallMs=$wall encodeMs=$enc decodeMs=$dec " +
                    "rtf=${"%.2f".format(rtf)} peakRssKb=${peakRssKb()}",
            )
        } finally {
            Parakeet.nativeFree(handle)
        }
    }

    /** High-water mark of the process, the only peak number Android gives us for free. */
    private fun peakRssKb(): Long =
        java.io.File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }?.toLong() ?: -1

    /** 16-bit mono PCM WAV, the only format the bench asset is stored in. */
    private fun readWav(input: java.io.InputStream): FloatArray {
        val bytes = input.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = buf.getInt(pos + 4)
            if (id == "data") {
                dataOffset = pos + 8
                dataLen = len
                break
            }
            pos += 8 + len + (len and 1)
        }
        check(dataOffset > 0) { "no data chunk" }
        val n = minOf(dataLen, bytes.size - dataOffset) / 2
        return FloatArray(n) { buf.getShort(dataOffset + it * 2) / 32768f }
    }

    companion object {
        // Point this at either a whisper ggml model or a parakeet ggml model.
        private const val MODEL = "ggml-parakeet-tdt-0.6b-v3-q4_k.bin"
        private const val THREADS = 4
        private const val RUNS = 3
        private const val TAG = "VXK_BENCH"
    }
}
