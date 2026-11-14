package dev.voxkey.bench

/** JNI surface, see app/src/main/cpp/jni.cpp. nativeRun returns a JSON string. */
object Whisper {
    init {
        System.loadLibrary("voxkey")
    }

    external fun nativeInit(modelPath: String, threads: Int): Long
    external fun nativeRun(handle: Long, pcm: FloatArray, lang: String): String
    external fun nativeFree(handle: Long)
    external fun nativeBackend(): String
}
