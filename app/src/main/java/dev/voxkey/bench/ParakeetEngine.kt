package dev.voxkey.bench

import android.util.Log
import org.json.JSONObject

/** Vrai pour un modèle Parakeet (GGUF), faux pour tout le reste (whisper .bin). */
fun isParakeetModel(name: String): Boolean = name.contains("parakeet", ignoreCase = true)

/** Garde le modèle chargé entre deux dictées et le libère quand il ne sert plus. */
object ParakeetEngine {

    private var handle = 0L
    private var loadedPath: String? = null

    @Synchronized
    fun ensure(path: String): Boolean {
        if (handle != 0L && loadedPath == path) return true
        release()
        val t0 = System.nanoTime()
        handle = Parakeet.nativeInit(path, 4)
        loadedPath = if (handle != 0L) path else null
        Log.d("VXK", "parakeet load=${(System.nanoTime() - t0) / 1_000_000}ms ok=${handle != 0L} $path")
        return handle != 0L
    }

    /** Renvoie le texte seul ; les mesures du moteur sont recopiées dans le journal. */
    @Synchronized
    fun transcribe(pcm: FloatArray, threads: Int = 4): String {
        if (handle == 0L) return ""
        val t0 = System.nanoTime()
        val raw = Parakeet.nativeRun(handle, pcm)
        val audioMs = pcm.size * 1000L / 16_000
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
        Log.d(
            "VXK",
            "parakeet run wall=${(System.nanoTime() - t0) / 1_000_000}ms" +
                " audio=${audioMs}ms enc=${json?.optDouble("encodeMs")} dec=${json?.optDouble("decodeMs")}" +
                " err=${json?.optString("error")}",
        )
        return json?.optString("text", "") ?: ""
    }

    @Synchronized
    fun release() {
        if (handle != 0L) {
            Parakeet.nativeFree(handle)
            handle = 0L
            loadedPath = null
        }
    }
}
