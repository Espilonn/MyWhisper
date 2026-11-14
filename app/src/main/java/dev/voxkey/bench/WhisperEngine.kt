package dev.voxkey.bench

import org.json.JSONObject

/** Garde le modèle chargé entre deux dictées et le libère quand il ne sert plus. */
object WhisperEngine {

    private var handle = 0L
    private var loadedPath: String? = null

    @Synchronized
    fun ensure(path: String): Boolean {
        if (handle != 0L && loadedPath == path) return true
        release()
        handle = Whisper.nativeInit(path, 4)
        loadedPath = if (handle != 0L) path else null
        return handle != 0L
    }

    /** Renvoie le texte seul ; les mesures restent dans le JSON natif. */
    @Synchronized
    fun transcribe(pcm: FloatArray, lang: String = "fr", threads: Int = 4): String {
        if (handle == 0L) return ""
        val raw = Whisper.nativeRun(handle, pcm, lang)
        return try {
            JSONObject(raw).optString("text", "")
        } catch (_: Exception) {
            ""
        }
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
