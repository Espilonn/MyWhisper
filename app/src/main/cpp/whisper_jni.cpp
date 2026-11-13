#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_dev_voxkey_bench_Whisper_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_voxkey_bench_Whisper_nativeRun(JNIEnv *env, jclass, jlong handle,
                                        jfloatArray pcm, jstring lang,
                                        jint threads, jfloatArray diag) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(pcm);
    std::vector<float> audio(n);
    env->GetFloatArrayRegion(pcm, 0, n, audio.data());

    const char *langStr = env->GetStringUTFChars(lang, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_special    = false;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_timestamps = false;
    p.translate        = false;
    p.language         = langStr;
    p.n_threads        = threads;
    p.no_context       = true;
    p.no_timestamps    = true;
    p.single_segment   = false;
    p.suppress_blank   = true;
    p.temperature      = 0.0f;

    auto t0 = std::chrono::steady_clock::now();
    int rc = whisper_full(ctx, p, audio.data(), (int) audio.size());
    auto t1 = std::chrono::steady_clock::now();

    env->ReleaseStringUTFChars(lang, langStr);

    float totalMs = std::chrono::duration<float, std::milli>(t1 - t0).count();
    float encMs = 0.f;
    if (rc == 0) {
        encMs = whisper_get_timings(ctx)->encode_ms;
    }

    if (env->GetArrayLength(diag) >= 2) {
        float d[2] = { totalMs, encMs };
        env->SetFloatArrayRegion(diag, 0, 2, d);
    }

    if (rc != 0) {
        std::string err = "[ERREUR whisper_full: " + std::to_string(rc) + "]";
        return env->NewStringUTF(err.c_str());
    }

    std::string out;
    int nseg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nseg; i++) {
        const char *s = whisper_full_get_segment_text(ctx, i);
        if (s) out += s;
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_voxkey_bench_Whisper_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}
