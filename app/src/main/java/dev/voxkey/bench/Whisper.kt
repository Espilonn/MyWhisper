package dev.voxkey.bench

object Whisper {
    init {
        System.loadLibrary("voxkey")
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeRun(handle: Long, pcm: FloatArray, lang: String, threads: Int, diag: FloatArray): String
    external fun nativeFree(handle: Long)
}
