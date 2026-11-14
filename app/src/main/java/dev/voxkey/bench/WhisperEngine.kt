package dev.voxkey.bench

import android.util.Log
import org.json.JSONObject

/** Garde le modèle chargé entre deux dictées et le libère quand il ne sert plus. */
object WhisperEngine {

    private var handle = 0L
    private var loadedPath: String? = null

    @Synchronized
    fun ensure(path: String): Boolean {
        if (handle != 0L && loadedPath == path) return true
        release()
        val t0 = System.nanoTime()
        handle = Whisper.nativeInit(path, 4)
        loadedPath = if (handle != 0L) path else null
        Log.d("VXK", "whisper load=${(System.nanoTime() - t0) / 1_000_000}ms ok=${handle != 0L} $path")
        return handle != 0L
    }

    /** Renvoie le texte seul ; les mesures restent dans le JSON natif. */
    @Synchronized
    fun transcribe(pcm: FloatArray, lang: String = "fr", threads: Int = 4): String {
        if (handle == 0L) return ""
        val t0 = System.nanoTime()
        val raw = Whisper.nativeRun(handle, pcm, lang)
        val audioMs = pcm.size * 1000L / 16_000
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
        Log.d(
            "VXK",
            "whisper run wall=${(System.nanoTime() - t0) / 1_000_000}ms" +
                " audio=${audioMs}ms enc=${json?.optDouble("encodeMs")} dec=${json?.optDouble("decodeMs")}" +
                " err=${json?.optString("error")}",
        )
        return json?.optString("text", "") ?: ""
    }

    @Synchronized
    fun release() {
        if (handle != 0L) {
            Whisper.nativeFree(handle)
            handle = 0L
            loadedPath = null
        }
    }
}
