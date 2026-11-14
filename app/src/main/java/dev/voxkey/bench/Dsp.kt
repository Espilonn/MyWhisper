package dev.voxkey.bench

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pré-traitement avant whisper : passe-bande voix (100 Hz - 4 kHz, Butterworth 2e ordre)
 * et porte de silence (RMS). Coupe le grondement de fond sous la voix et le souffle aigu.
 * shortcut: bande fixe, à régler à l'oreille ; une voix grave perd un peu de fondamental.
 */
object Dsp {
    const val HIGH_PASS_HZ = 100.0
    const val LOW_PASS_HZ = 4000.0
    private const val SAMPLE_RATE = 16000.0

    /** En dessous, on considère que c'est du silence : whisper n'est pas appelé. */
    const val SILENCE_RMS = 0.008f

    fun bandpass(x: FloatArray): FloatArray =
        biquad(biquad(x, coef(HIGH_PASS_HZ, high = true)), coef(LOW_PASS_HZ, high = false))

    fun rms(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        return sqrt(sum / x.size).toFloat()
    }

    private class Coef(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    // Coefficients RBJ « audio EQ cookbook », Q = 1/sqrt(2) (Butterworth).
    private fun coef(f0: Double, high: Boolean): Coef {
        val w = 2 * PI * f0 / SAMPLE_RATE
        val c = cos(w)
        val alpha = sin(w) / sqrt(2.0)
        val a0 = 1 + alpha
        val b0 = if (high) (1 + c) / 2 else (1 - c) / 2
        val b1 = if (high) -(1 + c) else (1 - c)
        return Coef(b0 / a0, b1 / a0, b0 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    private fun biquad(x: FloatArray, k: Coef): FloatArray {
        val y = FloatArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val yi = k.b0 * xi + k.b1 * x1 + k.b2 * x2 - k.a1 * y1 - k.a2 * y2
            y[i] = yi.toFloat()
            x2 = x1; x1 = xi; y2 = y1; y1 = yi
        }
        return y
    }
}
