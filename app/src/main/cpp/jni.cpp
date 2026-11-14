// Thin JNI wrapper over whisper.cpp. One model handle, one transcription call.
#include <jni.h>

#include <chrono>
#include <string>

#include "whisper.h"

#ifdef GGML_USE_VULKAN
static constexpr const char * kBackend = "Vulkan";
#else
static constexpr const char * kBackend = "CPU";
#endif

struct Handle {
    whisper_context * ctx;
    int n_threads;
};

static double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

// Shape of the timing capture: t0 marks the start, t_enc the end of the encoder.
struct Timing {
    double t0;
    double t_enc;
    double t_end;
};

static void progress_cb(whisper_context *, whisper_state *, int progress, void * user_data) {
    auto * t = static_cast<Timing *>(user_data);
    // whisper reports encoder progress in 0..100; 100 is the last encoder callback.
    if (progress >= 100 && t->t_enc < 0) {
        t->t_enc = now_ms();
    }
}

static std::string json_escape(const std::string & in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (unsigned char c : in) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_voxkey_bench_Whisper_nativeInit(JNIEnv * env, jclass, jstring j_model, jint threads) {
    const char * model = env->GetStringUTFChars(j_model, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = true;  // only honoured by a GPU backend
    whisper_context * ctx = whisper_init_from_file_with_params(model, cparams);
    env->ReleaseStringUTFChars(j_model, model);

    if (ctx == nullptr) return 0;
    auto * h = new Handle{ctx, threads > 0 ? threads : 4};
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_voxkey_bench_Whisper_nativeRun(JNIEnv * env, jclass, jlong j_handle,
                                        jfloatArray j_pcm, jstring j_lang) {
    auto * h = reinterpret_cast<Handle *>(j_handle);
    if (h == nullptr) return env->NewStringUTF("{\"error\":\"no handle\"}");

    jsize n = env->GetArrayLength(j_pcm);
    jfloat * pcm = env->GetFloatArrayElements(j_pcm, nullptr);
    const char * lang = env->GetStringUTFChars(j_lang, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads        = h->n_threads;
    p.language         = lang;
    p.translate        = false;
    p.no_context       = true;
    p.no_timestamps    = true;
    p.single_segment   = false;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_special    = false;
    p.print_timestamps = false;

    Timing t{now_ms(), -1, 0};
    p.progress_callback = progress_cb;
    p.progress_callback_user_data = &t;

    const int rc = whisper_full(h->ctx, p, pcm, n);
    t.t_end = now_ms();

    env->ReleaseFloatArrayElements(j_pcm, pcm, JNI_ABORT);
    env->ReleaseStringUTFChars(j_lang, lang);

    if (rc != 0) {
        char buf[128];
        snprintf(buf, sizeof(buf), "{\"error\":\"whisper_full failed (%d)\"}", rc);
        return env->NewStringUTF(buf);
    }

    std::string text;
    for (int i = 0; i < whisper_full_n_segments(h->ctx); ++i) {
        text += whisper_full_get_segment_text(h->ctx, i);
    }

    // Decode time is whatever is left after the encoder (sampling + decode).
    const double enc = t.t_enc > 0 ? t.t_enc - t.t0 : 0.0;
    const double wall = t.t_end - t.t0;

    char head[256];
    snprintf(head, sizeof(head),
             "{\"wallMs\":%.1f,\"encodeMs\":%.1f,\"decodeMs\":%.1f,\"text\":\"",
             wall, enc, wall - enc);
    std::string out = head;
    out += json_escape(text);
    out += "\"}";

    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_voxkey_bench_Whisper_nativeFree(JNIEnv *, jclass, jlong j_handle) {
    auto * h = reinterpret_cast<Handle *>(j_handle);
    if (h == nullptr) return;
    whisper_free(h->ctx);
    delete h;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_voxkey_bench_Whisper_nativeBackend(JNIEnv * env, jclass) {
    return env->NewStringUTF(kBackend);
}
