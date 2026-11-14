package dev.voxkey.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DspTest {

    private fun sine(hz: Double, n: Int = 16000) =
        FloatArray(n) { sin(2 * PI * hz * it / 16000).toFloat() }

    private fun peak(x: FloatArray) = x.drop(x.size / 2).maxOf { kotlin.math.abs(it) }

    @Test
    fun grondeSousLaVoixEstAttenue() {
        assertTrue(peak(Dsp.bandpass(sine(30.0))) < 0.3f) // 2e ordre : ~-11 dB à 30 Hz, pas plus
    }

    @Test
    fun voixMediumEstConservee() {
        assertTrue(peak(Dsp.bandpass(sine(1000.0))) > 0.9f)
    }

    @Test
    fun aiguAuDelaDe4kEstAttenue() {
        assertTrue(peak(Dsp.bandpass(sine(7000.0))) < 0.1f)
    }

    @Test
    fun silenceSousLaPorte() {
        assertEquals(0f, Dsp.rms(FloatArray(1600)), 0f)
        assertTrue(Dsp.rms(sine(1000.0)) > Dsp.SILENCE_RMS)
    }
}
