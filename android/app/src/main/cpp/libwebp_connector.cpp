#include <jni.h>
#include <string>
#include <cstring>
#include <android/log.h>

// libwebp headers
#include "src/webp/encode.h"
#include "src/webp/mux.h"
#include "src/webp/decode.h"

#define LOG_TAG "NativeEncoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#ifndef NDEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void) 0)
#endif

// Encoder state. Ownership is handed to Kotlin as an opaque jlong handle, so
// several encoders can coexist and none leaks if Kotlin forgets about it.
struct EncoderState {
    WebPAnimEncoder *anim_encoder = nullptr;
    WebPConfig config;
    int frame_width = 0;
    int frame_height = 0;
};

static void destroyState(EncoderState *state) {
    if (state == nullptr) return;
    if (state->anim_encoder != nullptr) {
        WebPAnimEncoderDelete(state->anim_encoder);
    }
    delete state;
}

static const char *getWebPErrorString(int error_code) {
    switch (error_code) {
        case VP8_ENC_OK: return "OK";
        case VP8_ENC_ERROR_OUT_OF_MEMORY: return "OUT_OF_MEMORY";
        case VP8_ENC_ERROR_BITSTREAM_OUT_OF_MEMORY: return "BITSTREAM_OUT_OF_MEMORY";
        case VP8_ENC_ERROR_NULL_PARAMETER: return "NULL_PARAMETER";
        case VP8_ENC_ERROR_INVALID_CONFIGURATION: return "INVALID_CONFIGURATION";
        case VP8_ENC_ERROR_BAD_DIMENSION: return "BAD_DIMENSION";
        case VP8_ENC_ERROR_PARTITION0_OVERFLOW: return "PARTITION0_OVERFLOW";
        case VP8_ENC_ERROR_PARTITION_OVERFLOW: return "PARTITION_OVERFLOW";
        case VP8_ENC_ERROR_BAD_WRITE: return "BAD_WRITE";
        case VP8_ENC_ERROR_FILE_TOO_BIG: return "FILE_TOO_BIG";
        case VP8_ENC_ERROR_USER_ABORT: return "USER_ABORT";
        default: return "UNKNOWN_ERROR";
    }
}

extern "C" {

/**
 * Reads a WebP file's raw byte data to get its width and height.
 */
JNIEXPORT jintArray JNICALL
Java_de_loicezt_stickers_video_LibWebP_nativeGetInfo(
        JNIEnv *env,
        jobject /* this */,
        jbyteArray data) {

    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        LOGE("nativeGetInfo: Could not get byte array elements.");
        return nullptr;
    }
    size_t size = env->GetArrayLength(data);

    int width = 0;
    int height = 0;
    bool ok = WebPGetInfo(reinterpret_cast<const uint8_t *>(bytes), size, &width, &height);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    if (!ok) {
        LOGE("nativeGetInfo: WebPGetInfo failed. Invalid WebP data.");
        return nullptr;
    }

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        LOGE("nativeGetInfo: Could not create new int array.");
        return nullptr;
    }
    jint info[2] = {width, height};
    env->SetIntArrayRegion(result, 0, 2, info);
    return result;
}

/**
 * Decodes WebP raw byte data into a pre-allocated direct ByteBuffer as RGBA.
 */
JNIEXPORT jboolean JNICALL
Java_de_loicezt_stickers_video_LibWebP_nativeDecode(
        JNIEnv *env,
        jobject /* this */,
        jbyteArray data,
        jobject out_buffer,
        jint stride) {

    auto *out_pixels = static_cast<uint8_t *>(env->GetDirectBufferAddress(out_buffer));
    if (out_pixels == nullptr) {
        LOGE("nativeDecode: Could not get direct buffer address for output.");
        return JNI_FALSE;
    }
    size_t out_size = env->GetDirectBufferCapacity(out_buffer);

    jbyte *in_bytes = env->GetByteArrayElements(data, nullptr);
    if (in_bytes == nullptr) {
        LOGE("nativeDecode: Could not get byte array elements.");
        return JNI_FALSE;
    }
    size_t in_size = env->GetArrayLength(data);

    uint8_t *decoded_data = WebPDecodeRGBAInto(
            reinterpret_cast<const uint8_t *>(in_bytes), in_size, out_pixels, out_size, stride);
    env->ReleaseByteArrayElements(data, in_bytes, JNI_ABORT);

    if (decoded_data == nullptr) {
        LOGE("nativeDecode: WebPDecodeRGBAInto failed.");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/**
 * Creates an encoder and returns its opaque handle, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_de_loicezt_stickers_video_LibWebP_nativeInitEncoder(
        JNIEnv *env,
        jobject /* this */,
        jint width,
        jint height,
        jobject configJava) {

    if (configJava == nullptr) {
        LOGE("nativeInitEncoder: null config.");
        return 0;
    }

    auto *state = new EncoderState();
    state->frame_width = width;
    state->frame_height = height;

    if (!WebPConfigInit(&state->config)) {
        LOGE("Failed to initialize WebPConfig.");
        destroyState(state);
        return 0;
    }
    // Sensible default when the caller leaves the value unset.
    state->config.thread_level = 1;

    // Look up classes and method ids once, not once per field.
    jclass configClass = env->GetObjectClass(configJava);
    jclass intClass = env->FindClass("java/lang/Integer");
    jclass floatClass = env->FindClass("java/lang/Float");
    jclass boolClass = env->FindClass("java/lang/Boolean");
    jmethodID intValue = env->GetMethodID(intClass, "intValue", "()I");
    jmethodID floatValue = env->GetMethodID(floatClass, "floatValue", "()F");
    jmethodID boolValue = env->GetMethodID(boolClass, "booleanValue", "()Z");
    jclass hintClass = env->FindClass("de/loicezt/stickers/video/WebPImageHint");
    jmethodID ordinal = hintClass != nullptr ? env->GetMethodID(hintClass, "ordinal", "()I")
                                             : nullptr;

    auto getField = [&](const char *name, const char *sig) -> jobject {
        jfieldID fid = env->GetFieldID(configClass, name, sig);
        if (fid == nullptr) {
            env->ExceptionClear();
            LOGE("Config field not found: %s", name);
            return nullptr;
        }
        return env->GetObjectField(configJava, fid);
    };
    auto updateInt = [&](const char *name, int &target) {
        jobject obj = getField(name, "Ljava/lang/Integer;");
        if (obj != nullptr) {
            target = env->CallIntMethod(obj, intValue);
            env->DeleteLocalRef(obj);
        }
    };
    auto updateFloat = [&](const char *name, float &target) {
        jobject obj = getField(name, "Ljava/lang/Float;");
        if (obj != nullptr) {
            target = env->CallFloatMethod(obj, floatValue);
            env->DeleteLocalRef(obj);
        }
    };
    auto updateBoolean = [&](const char *name, int &target) {
        jobject obj = getField(name, "Ljava/lang/Boolean;");
        if (obj != nullptr) {
            target = env->CallBooleanMethod(obj, boolValue) ? 1 : 0;
            env->DeleteLocalRef(obj);
        }
    };
    auto updateEnum = [&](const char *name, WebPImageHint &target) {
        if (ordinal == nullptr) return;
        jobject obj = getField(name, "Lde/loicezt/stickers/video/WebPImageHint;");
        if (obj != nullptr) {
            target = static_cast<WebPImageHint>(env->CallIntMethod(obj, ordinal));
            env->DeleteLocalRef(obj);
        }
    };

    updateBoolean("lossless", state->config.lossless);
    updateFloat("quality", state->config.quality);
    updateInt("method", state->config.method);
    updateEnum("imageHint", state->config.image_hint);
    updateInt("targetSize", state->config.target_size);
    updateFloat("targetPSNR", state->config.target_PSNR);
    updateInt("segments", state->config.segments);
    updateInt("snsStrength", state->config.sns_strength);
    updateInt("filterStrength", state->config.filter_strength);
    updateInt("filterSharpness", state->config.filter_sharpness);
    updateInt("filterType", state->config.filter_type);
    updateInt("autofilter", state->config.autofilter);
    updateInt("alphaCompression", state->config.alpha_compression);
    updateInt("alphaFiltering", state->config.alpha_filtering);
    updateInt("alphaQuality", state->config.alpha_quality);
    updateInt("pass", state->config.pass);
    updateInt("showCompressed", state->config.show_compressed);
    updateInt("preprocessing", state->config.preprocessing);
    updateInt("partitions", state->config.partitions);
    updateInt("partitionLimit", state->config.partition_limit);
    updateInt("emulateJpegSize", state->config.emulate_jpeg_size);
    updateInt("threadLevel", state->config.thread_level);
    updateInt("lowMemory", state->config.low_memory);
    updateInt("nearLossless", state->config.near_lossless);
    updateInt("exact", state->config.exact);

    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(intClass);
    env->DeleteLocalRef(floatClass);
    env->DeleteLocalRef(boolClass);
    if (hintClass != nullptr) env->DeleteLocalRef(hintClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("JNI exception while reading config.");
        destroyState(state);
        return 0;
    }

    if (!WebPValidateConfig(&state->config)) {
        LOGE("Invalid config");
        destroyState(state);
        return 0;
    }

    WebPAnimEncoderOptions anim_options;
    if (!WebPAnimEncoderOptionsInit(&anim_options)) {
        LOGE("Failed to init WebPAnimEncoderOptions.");
        destroyState(state);
        return 0;
    }
    state->anim_encoder = WebPAnimEncoderNew(width, height, &anim_options);
    if (state->anim_encoder == nullptr) {
        LOGE("Failed to create new WebPAnimEncoder.");
        destroyState(state);
        return 0;
    }

    LOGI("Native encoder initialized successfully for %dx%d.", width, height);
    return reinterpret_cast<jlong>(state);
}

/**
 * Adds one RGBA frame. Returns false if the frame could not be encoded.
 */
JNIEXPORT jboolean JNICALL
Java_de_loicezt_stickers_video_LibWebP_nativeAddFrame(
        JNIEnv *env,
        jobject /* this */,
        jlong handle,
        jobject frameBuffer,
        jint timestampMs) {

    auto *state = reinterpret_cast<EncoderState *>(handle);
    if (state == nullptr || state->anim_encoder == nullptr) {
        LOGE("Cannot add frame. Encoder not initialized.");
        return JNI_FALSE;
    }

    auto *pixels = static_cast<uint8_t *>(env->GetDirectBufferAddress(frameBuffer));
    if (pixels == nullptr) {
        LOGE("Failed to get direct buffer address.");
        return JNI_FALSE;
    }
    const jlong capacity = env->GetDirectBufferCapacity(frameBuffer);
    if (capacity < static_cast<jlong>(state->frame_width) * state->frame_height * 4) {
        LOGE("Frame buffer too small (%lld bytes).", static_cast<long long>(capacity));
        return JNI_FALSE;
    }

    WebPPicture pic;
    if (!WebPPictureInit(&pic)) {
        LOGE("Failed to init WebPPicture");
        return JNI_FALSE;
    }
    pic.width = state->frame_width;
    pic.height = state->frame_height;
    pic.use_argb = 1;

    if (!WebPPictureImportRGBA(&pic, pixels, state->frame_width * 4)) {
        LOGE("WebPPictureImportRGBA failed: %s (%d)", getWebPErrorString(pic.error_code),
             pic.error_code);
        WebPPictureFree(&pic);
        return JNI_FALSE;
    }

    bool ok = WebPAnimEncoderAdd(state->anim_encoder, &pic, timestampMs, &state->config);
    if (!ok) {
        LOGE("Failed to add frame at %d ms. Error: %s (%d)", timestampMs,
             getWebPErrorString(pic.error_code), pic.error_code);
    } else {
        LOGD("Added frame at timestamp %d", timestampMs);
    }
    WebPPictureFree(&pic);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Assembles the animation and frees the encoder. endTimestampMs is the time at
 * which the last frame stops being shown. Always invalidates the handle.
 */
JNIEXPORT jbyteArray JNICALL
Java_de_loicezt_stickers_video_LibWebP_nativeReleaseEncoder(
        JNIEnv *env,
        jobject /* this */,
        jlong handle,
        jint endTimestampMs) {

    auto *state = reinterpret_cast<EncoderState *>(handle);
    if (state == nullptr || state->anim_encoder == nullptr) {
        LOGE("nativeReleaseEncoder: encoder not initialized.");
        destroyState(state);
        return nullptr;
    }

    jbyteArray result = nullptr;
    WebPData webp_data;
    WebPDataInit(&webp_data);

    if (!WebPAnimEncoderAdd(state->anim_encoder, nullptr, endTimestampMs, nullptr)) {
        LOGE("Failed to flush WebPAnimEncoder.");
    } else if (!WebPAnimEncoderAssemble(state->anim_encoder, &webp_data)) {
        LOGE("Failed to assemble final WebP animation.");
    } else {
        LOGI("Assembled WebP data. Size: %zu bytes", webp_data.size);
        result = env->NewByteArray(static_cast<jsize>(webp_data.size));
        if (result == nullptr) {
            LOGE("Could not allocate result byte array.");
        } else {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(webp_data.size),
                                    reinterpret_cast<const jbyte *>(webp_data.bytes));
        }
    }

    WebPDataClear(&webp_data);
    destroyState(state);
    return result;
}

/**
 * Frees an encoder without assembling it (error and cancel paths).
 */
JNIEXPORT void JNICALL
Java_de_loicezt_stickers_video_LibWebP_nativeDestroyEncoder(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong handle) {
    destroyState(reinterpret_cast<EncoderState *>(handle));
}

} // extern "C"
