package dev.voxkey.bench

/** JNI surface, see app/src/main/cpp/jni.cpp. nativeRun returns a JSON string. */
object Parakeet {
    init {
        System.loadLibrary("voxkey")
    }

    external fun nativeInit(modelPath: String, threads: Int): Long
    external fun nativeRun(handle: Long, pcm: FloatArray): String
    external fun nativeFree(handle: Long)
}
