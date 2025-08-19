//// WhisperLib.c — whisper.cpp JNI safe loader + transcriber (full version)
//// - InputStream / Asset / File path の3系統ローダー
//// - InputStream: ヒープ確保・GlobalRef・64KBグローバルバッファ再利用
//// - JNIEnv* はスレッドごとに取得（JavaVMを保持）
//// - AAsset_read の負値ハンドリング、例外チェック、ログ補強
//// - メモリリーク/ダングリングポインタ防止
//// Build: C/NDK (C11推奨)
//
//#include <jni.h>
//#include <android/asset_manager.h>
//#include <android/asset_manager_jni.h>
//#include <android/log.h>
//#include <stdlib.h>
//#include <string.h>
//#include <stdbool.h>
//
//#include "whisper.h"
//
//#define TAG "JNI-Whisper"
//#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
//#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
//#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
//
///* ============================================================
// * Helpers
// * ============================================================ */
//
//static inline JNIEnv* get_env_from_jvm(JavaVM* jvm) {
//    if (!jvm) return NULL;
//    JNIEnv* env = NULL;
//    // For C, second arg is (void**)
//    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
//        if ((*jvm)->AttachCurrentThread(jvm, (void**)&env, NULL) != 0) {
//            LOGE("AttachCurrentThread failed");
//            return NULL;
//        }
//    }
//    return env;
//}
//
///* ============================================================
// * InputStream loader (safe)
// * ============================================================ */
//
//struct input_stream_context {
//    JavaVM   *jvm;           // JVM (thread-safeにJNIEnv取得用)
//    jobject   input_stream;  // GlobalRef to Java InputStream
//    jmethodID mid_read;      // int read(byte[], int, int)
//    jobject   buffer_gl;     // GlobalRef to byte[] (64KB)
//    jint      buf_len;       // = 64*1024
//    int       eof;           // 1 when EOF observed
//};
//
//static size_t is_read(void *ctx, void *output, size_t read_size) {
//    struct input_stream_context* is = (struct input_stream_context*)ctx;
//    if (!is || !is->jvm || !is->input_stream || !is->buffer_gl) return 0;
//
//    JNIEnv* env = get_env_from_jvm(is->jvm);
//    if (!env) return 0;
//
//    jint chunk = (jint)((read_size > (size_t)is->buf_len) ? is->buf_len : read_size);
//    // Call InputStream.read(byte[], off, len)
//    jint n = (*env)->CallIntMethod(env, is->input_stream, is->mid_read, is->buffer_gl, 0, chunk);
//
//    if ((*env)->ExceptionCheck(env)) {
//        LOGE("Exception in InputStream.read(); stopping load");
//        (*env)->ExceptionDescribe(env);
//        (*env)->ExceptionClear(env);
//        is->eof = 1;
//        return 0;
//    }
//
//    if (n <= 0) { // EOF or no data
//        is->eof = 1;
//        return 0;
//    }
//
//    jbyte* ptr = (*env)->GetByteArrayElements(env, (jbyteArray)is->buffer_gl, NULL);
//    if (!ptr) {
//        LOGE("GetByteArrayElements returned NULL");
//        is->eof = 1;
//        return 0;
//    }
//
//    memcpy(output, (const void*)ptr, (size_t)n);
//    (*env)->ReleaseByteArrayElements(env, (jbyteArray)is->buffer_gl, ptr, JNI_ABORT);
//    return (size_t)n;
//}
//
//static bool is_eof(void *ctx) {
//    struct input_stream_context* is = (struct input_stream_context*)ctx;
//    return is ? (is->eof != 0) : true;
//}
//
//static void is_close(void *ctx) {
//    struct input_stream_context* is = (struct input_stream_context*)ctx;
//    if (!is) return;
//
//    JNIEnv* env = get_env_from_jvm(is->jvm);
//    if (env) {
//        if (is->input_stream) {
//            (*env)->DeleteGlobalRef(env, is->input_stream);
//            is->input_stream = NULL;
//        }
//        if (is->buffer_gl) {
//            (*env)->DeleteGlobalRef(env, is->buffer_gl);
//            is->buffer_gl = NULL;
//        }
//    }
//    free(is);
//}
//
//JNIEXPORT jlong JNICALL
//Java_com_whispercpp_whisper_WhisperLib_initContextFromInputStream(
//        JNIEnv *env, jclass clazz, jobject input_stream) {
//    (void)clazz;
//    if (!input_stream) {
//        LOGW("initContextFromInputStream: input_stream is NULL");
//        return 0;
//    }
//
//    struct input_stream_context* inp = (struct input_stream_context*)calloc(1, sizeof(*inp));
//    if (!inp) {
//        LOGE("calloc failed");
//        return 0;
//    }
//
//    if ((*env)->GetJavaVM(env, &inp->jvm) != 0) {
//        LOGE("GetJavaVM failed");
//        free(inp);
//        return 0;
//    }
//
//    inp->input_stream = (*env)->NewGlobalRef(env, input_stream);
//    if (!inp->input_stream) {
//        LOGE("NewGlobalRef(input_stream) failed");
//        free(inp);
//        return 0;
//    }
//
//    jclass cls = (*env)->GetObjectClass(env, input_stream);
//    if (!cls) {
//        LOGE("GetObjectClass(input_stream) failed");
//        (*env)->DeleteGlobalRef(env, inp->input_stream);
//        free(inp);
//        return 0;
//    }
//
//    inp->mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");
//    (*env)->DeleteLocalRef(env, cls);
//    if (!inp->mid_read) {
//        LOGE("GetMethodID(read([BII)I) failed");
//        (*env)->DeleteGlobalRef(env, inp->input_stream);
//        free(inp);
//        return 0;
//    }
//
//    inp->buf_len = 64 * 1024;
//    jbyteArray buffer_local = (*env)->NewByteArray(env, inp->buf_len);
//    if (!buffer_local) {
//        LOGE("NewByteArray(64KB) failed");
//        (*env)->DeleteGlobalRef(env, inp->input_stream);
//        free(inp);
//        return 0;
//    }
//    inp->buffer_gl = (*env)->NewGlobalRef(env, buffer_local);
//    (*env)->DeleteLocalRef(env, buffer_local);
//    if (!inp->buffer_gl) {
//        LOGE("NewGlobalRef(buffer) failed");
//        (*env)->DeleteGlobalRef(env, inp->input_stream);
//        free(inp);
//        return 0;
//    }
//
//    struct whisper_model_loader loader = {
//            .context = inp,
//            .read    = is_read,
//            .eof     = is_eof,
//            .close   = is_close,
//    };
//
//    struct whisper_context_params cparams = whisper_context_default_params();
//    struct whisper_context *ctx = whisper_init_with_params(&loader, cparams);
//    if (!ctx) {
//        LOGE("whisper_init_with_params failed (InputStream)");
//        // on failure, loader.close() は呼ばれないので明示的に後始末
//        is_close(inp);
//        return 0;
//    }
//
//    return (jlong) ctx;
//}
//
///* ============================================================
// * Asset loader
// * ============================================================ */
//
//static size_t asset_read(void *ctx, void *output, size_t read_size) {
//    int r = AAsset_read((AAsset *)ctx, output, (size_t)read_size);
//    return (r > 0) ? (size_t)r : 0;
//}
//
//static bool asset_eof(void *ctx) {
//    return AAsset_getRemainingLength64((AAsset *)ctx) <= 0;
//}
//
//static void asset_close(void *ctx) {
//    if (ctx) AAsset_close((AAsset *)ctx);
//}
//
//static struct whisper_context *whisper_init_from_asset(
//        JNIEnv *env, jobject assetManager, const char *asset_path) {
//    if (!assetManager || !asset_path) {
//        LOGW("whisper_init_from_asset: invalid args");
//        return NULL;
//    }
//
//    LOGI("Loading model from asset '%s'", asset_path);
//    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
//    if (!mgr) { LOGW("AAssetManager_fromJava returned NULL"); return NULL; }
//
//    AAsset *asset = AAssetManager_open(mgr, asset_path, AASSET_MODE_STREAMING);
//    if (!asset) { LOGW("Failed to open asset '%s'", asset_path); return NULL; }
//
//    struct whisper_model_loader loader = {
//            .context = asset,
//            .read    = asset_read,
//            .eof     = asset_eof,
//            .close   = asset_close,
//    };
//
//    struct whisper_context_params cparams = whisper_context_default_params();
//    struct whisper_context *ctx = whisper_init_with_params(&loader, cparams);
//    if (!ctx) LOGE("whisper_init_with_params failed (Asset)");
//    return ctx;
//}
//
//JNIEXPORT jlong JNICALL
//Java_com_whispercpp_whisper_WhisperLib_initContextFromAsset(
//        JNIEnv *env, jclass clazz, jobject assetManager, jstring asset_path_str) {
//    (void)clazz;
//    if (!asset_path_str) {
//        LOGW("initContextFromAsset: asset_path_str is NULL");
//        return 0;
//    }
//    const char *path = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
//    if (!path) {
//        LOGE("GetStringUTFChars failed");
//        return 0;
//    }
//    struct whisper_context *ctx = whisper_init_from_asset(env, assetManager, path);
//    (*env)->ReleaseStringUTFChars(env, asset_path_str, path);
//    return (jlong) ctx;
//}
//
///* ============================================================
// * File path loader
// * ============================================================ */
//
//JNIEXPORT jlong JNICALL
//Java_com_whispercpp_whisper_WhisperLib_initContext(
//        JNIEnv *env, jclass clazz, jstring model_path_str) {
//    (void)env; (void)clazz;
//    if (!model_path_str) {
//        LOGW("initContext: model_path_str is NULL");
//        return 0;
//    }
//    const char *path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
//    if (!path) {
//        LOGE("GetStringUTFChars failed (model_path)");
//        return 0;
//    }
//    struct whisper_context_params cparams = whisper_context_default_params();
//    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
//    (*env)->ReleaseStringUTFChars(env, model_path_str, path);
//    if (!ctx) LOGE("whisper_init_from_file_with_params failed (File)");
//    return (jlong) ctx;
//}
//
//JNIEXPORT void JNICALL
//Java_com_whispercpp_whisper_WhisperLib_freeContext(
//        JNIEnv *env, jclass clazz, jlong context_ptr) {
//    (void)env; (void)clazz;
//    if (context_ptr) whisper_free((struct whisper_context *) context_ptr);
//}
//
///* ============================================================
// * Transcribe
// * ============================================================ */
//
//JNIEXPORT void JNICALL
//Java_com_whispercpp_whisper_WhisperLib_fullTranscribe(
//        JNIEnv *env, jclass clazz, jlong context_ptr, jstring lang_str, jint num_threads,
//        jboolean translate, jfloatArray audio_data) {
//    (void)clazz;
//    struct whisper_context *ctx = (struct whisper_context *) context_ptr;
//    if (!ctx || !audio_data) {
//        LOGW("fullTranscribe: ctx or audio_data is NULL");
//        return;
//    }
//
//    jfloat *pcm = (*env)->GetFloatArrayElements(env, audio_data, NULL);
//    if (!pcm) {
//        LOGE("GetFloatArrayElements(audio_data) failed");
//        return;
//    }
//    const jsize n = (*env)->GetArrayLength(env, audio_data);
//
//    const char *lang = NULL;
//    if (lang_str) lang = (*env)->GetStringUTFChars(env, lang_str, NULL);
//
//    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
//    p.n_threads       = (int)(num_threads > 0 ? num_threads : 1);
//    p.translate       = (translate == JNI_TRUE);
//    p.no_context      = true;
//    p.single_segment  = false;
//    p.print_realtime  = false;
//    p.print_progress  = false;
//    p.print_timestamps= false;
//    p.print_special   = false;
//
//    if (lang && lang[0] != '\0' && strcmp(lang, "auto") != 0) {
//        p.language        = lang;
//        p.detect_language = false;
//    } else {
//        p.detect_language = true;
//    }
//
//    whisper_reset_timings(ctx);
//    LOGI("whisper_full: n=%d, threads=%d, translate=%d", (int)n, p.n_threads, p.translate ? 1 : 0);
//
//    if (whisper_full(ctx, p, pcm, (int)n) != 0) {
//        LOGW("whisper_full failed");
//    } else {
//        whisper_print_timings(ctx);
//    }
//
//    if (lang_str && lang) (*env)->ReleaseStringUTFChars(env, lang_str, lang);
//    (*env)->ReleaseFloatArrayElements(env, audio_data, pcm, JNI_ABORT);
//}
//
///* ============================================================
// * Segments
// * ============================================================ */
//
//JNIEXPORT jint JNICALL
//Java_com_whispercpp_whisper_WhisperLib_getTextSegmentCount(
//        JNIEnv *env, jclass clazz, jlong context_ptr) {
//    (void)env; (void)clazz;
//    if (!context_ptr) return 0;
//    return whisper_full_n_segments((struct whisper_context*)context_ptr);
//}
//
//JNIEXPORT jstring JNICALL
//Java_com_whispercpp_whisper_WhisperLib_getTextSegment(
//        JNIEnv *env, jclass clazz, jlong context_ptr, jint index) {
//    (void)clazz;
//    if (!context_ptr) return (*env)->NewStringUTF(env, "");
//    const char *s = whisper_full_get_segment_text((struct whisper_context*)context_ptr, index);
//    return (*env)->NewStringUTF(env, s ? s : "");
//}
//
//JNIEXPORT jlong JNICALL
//Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT0(
//        JNIEnv *env, jclass clazz, jlong context_ptr, jint index) {
//    (void)env; (void)clazz;
//    if (!context_ptr) return 0;
//    return whisper_full_get_segment_t0((struct whisper_context*)context_ptr, index);
//}
//
//JNIEXPORT jlong JNICALL
//Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT1(
//        JNIEnv *env, jclass clazz, jlong context_ptr, jint index) {
//    (void)env; (void)clazz;
//    if (!context_ptr) return 0;
//    return whisper_full_get_segment_t1((struct whisper_context*)context_ptr, index);
//}
//
///* ============================================================
// * System / Bench
// * ============================================================ */
//
//JNIEXPORT jstring JNICALL
//Java_com_whispercpp_whisper_WhisperLib_getSystemInfo(
//        JNIEnv *env, jclass clazz) {
//    (void)clazz;
//    const char *s = whisper_print_system_info();
//    return (*env)->NewStringUTF(env, s ? s : "");
//}
//
//JNIEXPORT jstring JNICALL
//Java_com_whispercpp_whisper_WhisperLib_benchMemcpy(
//        JNIEnv *env, jclass clazz, jint n_threads) {
//    (void)clazz;
//    const char *s = whisper_bench_memcpy_str(n_threads);
//    return (*env)->NewStringUTF(env, s ? s : "");
//}
//
//JNIEXPORT jstring JNICALL
//Java_com_whispercpp_whisper_WhisperLib_benchGgmlMulMat(
//        JNIEnv *env, jclass clazz, jint n_threads) {
//    (void)clazz;
//    const char *s = whisper_bench_ggml_mul_mat_str(n_threads);
//    return (*env)->NewStringUTF(env, s ? s : "");
//}
//
///* ============================================================
// * JNI OnLoad
// * ============================================================ */
//
//JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
//    (void)vm; (void)reserved;
//    // ここで何か初期化したい場合は追加
//    return JNI_VERSION_1_6;
//}
