package dev.voxkey.bench

/** Garde le modèle chargé entre deux dictées et le libère quand il ne sert plus. */
object WhisperEngine {

    private var handle = 0L
    private var loadedPath: String? = null

    @Synchronized
    fun ensure(path: String): Boolean {
        if (handle != 0L && loadedPath == path) return true
        release()
        handle = Whisper.nativeInit(path)
        loadedPath = if (handle != 0L) path else null
        return handle != 0L
    }

    @Synchronized
    fun transcribe(pcm: FloatArray, lang: String = "fr", threads: Int = 4): String {
        if (handle == 0L) return ""
        return Whisper.nativeRun(handle, pcm, lang, threads, FloatArray(2))
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
