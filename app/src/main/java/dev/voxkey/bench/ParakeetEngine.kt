package dev.voxkey.bench

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
        handle = Parakeet.nativeInit(path, 4)
        loadedPath = if (handle != 0L) path else null
        return handle != 0L
    }

    /** Renvoie le texte seul ; les mesures restent dans le JSON natif. */
    @Synchronized
    fun transcribe(pcm: FloatArray, threads: Int = 4): String {
        if (handle == 0L) return ""
        val raw = Parakeet.nativeRun(handle, pcm)
        return try {
            JSONObject(raw).optString("text", "")
        } catch (_: Exception) {
            ""
        }
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
