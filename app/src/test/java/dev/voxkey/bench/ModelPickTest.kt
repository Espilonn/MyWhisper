package dev.voxkey.bench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickTest {

    @Test
    fun routesParakeetGgufToParakeet() {
        assertTrue(isParakeetModel("parakeet-tdt-0.6b-v3-q4_k.gguf"))
        assertTrue(isParakeetModel("Parakeet-TDT-0.6B-v3-GGUF.gguf"))
    }

    @Test
    fun routesWhisperBinsToWhisper() {
        assertFalse(isParakeetModel("ggml-small.bin"))
        assertFalse(isParakeetModel("ggml-large-v3-turbo-q5_0.bin"))
    }
}
