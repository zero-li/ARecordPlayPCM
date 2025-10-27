/*
 * RNNoise JNI bridge implemented in C.
 *
 * Processing chain (48 kHz mono PCM16 input -> 8 kHz mono PCM16 output):
 *   int16 (480) -> float (480) -> [optional] rnnoise -> decimate by 6 -> int16 (160 every 2 calls)
 *
 * The JNI surface maintains backwards-compatible entry points used by the Java wrapper:
 *   nativeCreate, nativeProcessFrame, nativeDestroy.
 */

#include <jni.h>

#include <android/log.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>

#include "resample/decimate6.h"
#include "rnnoise/include/rnnoise.h"

#define LOG_TAG "RnnoiseJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------------- */
/* Utility helpers                                                           */
/* ------------------------------------------------------------------------- */

#define FRAME_SIZE 480
#define DECIMATED_FRAME_TARGET 160  /* 20 ms of 8 kHz samples; produced every 2 frames */

static inline short float_to_pcm16(float v) {
    /* RNNoise demo treats samples as float in int16 range. Clamp and round. */
    if (v > 32767.0f) v = 32767.0f;
    if (v < -32768.0f) v = -32768.0f;
    return (short) lrintf(v);
}

static inline float pcm16_to_float(short s) {
    return (float)s;
}

/* Per-instance native handle stored as a jlong on the Java side */
typedef struct {
    DenoiseState *st;           /* NULL when denoiser disabled */
    int denoiser_enabled;       /* boolean flag */

    Decimate6State decimator;   /* stateful decimator (x6) */

    /* Accumulator for returning 160 8 kHz samples every two 10 ms frames */
    float decim_accum[DECIMATED_FRAME_TARGET];
    size_t decim_count;         /* number of valid samples currently buffered */
} RNHandle;

/* ------------------------------------------------------------------------- */
/* JNI helpers                                                               */
/* ------------------------------------------------------------------------- */

static RNHandle *handle_from_jlong(jlong h) {
    return (RNHandle *)(uintptr_t)h;
}

/* ------------------------------------------------------------------------- */
/* JNI methods                                                               */
/* ------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL
Java_com_zgo_recordplayer_audio_RnnoiseProcessor_nativeCreate(JNIEnv *env, jclass clazz, jboolean enableDenoiser) {
    (void)env; (void)clazz;  /* unused */

    RNHandle *handle = (RNHandle *)calloc(1, sizeof(RNHandle));
    if (!handle) {
        LOGE("Failed to allocate RNHandle");
        return (jlong)0;
    }

    handle->denoiser_enabled = (enableDenoiser == JNI_TRUE) ? 1 : 0;

    if (handle->denoiser_enabled) {
        handle->st = rnnoise_create(NULL);
        if (!handle->st) {
            LOGE("rnnoise_create returned NULL");
            free(handle);
            return (jlong)0;
        }
    } else {
        handle->st = NULL;
    }

    decimate6_init(&handle->decimator);
    handle->decim_count = 0;

    LOGI("nativeCreate: denoiser %s", handle->denoiser_enabled ? "enabled" : "disabled");
    return (jlong)(uintptr_t)handle;
}

JNIEXPORT jint JNICALL
Java_com_zgo_recordplayer_audio_RnnoiseProcessor_nativeProcessFrame(JNIEnv *env,
                                                                    jclass clazz,
                                                                    jlong h,
                                                                    jshortArray inputFrame,
                                                                    jshortArray denoisedOutput,
                                                                    jshortArray decimatedOutput) {
    (void)clazz;

    RNHandle *handle = handle_from_jlong(h);
    if (!handle) {
        LOGE("nativeProcessFrame called with null handle");
        return (jint)-1;
    }

    if (inputFrame == NULL) {
        LOGE("nativeProcessFrame: inputFrame is null");
        return (jint)-2;
    }

    jsize in_len = (*env)->GetArrayLength(env, inputFrame);
    if (in_len <= 0) {
        LOGE("nativeProcessFrame: inputFrame length invalid: %d", (int)in_len);
        return (jint)-3;
    }

    const jsize frame_len = FRAME_SIZE;

    jboolean is_copy_in = JNI_FALSE;
    jshort *in_ptr = (*env)->GetShortArrayElements(env, inputFrame, &is_copy_in);
    if (!in_ptr) {
        LOGE("nativeProcessFrame: failed to get input elements");
        return (jint)-4;
    }

    float inF[FRAME_SIZE];
    float outF[FRAME_SIZE];

    /* Convert input to float. If input shorter than frame, zero-pad. */
    jsize copy = (in_len < frame_len) ? in_len : frame_len;
    for (jsize i = 0; i < copy; ++i) inF[i] = pcm16_to_float(in_ptr[i]);
    for (jsize i = copy; i < frame_len; ++i) inF[i] = 0.0f;

    /* Process through RNNoise if enabled, otherwise passthrough. */
    if (handle->st) {
        (void)rnnoise_process_frame(handle->st, outF, inF);
    } else {
        memcpy(outF, inF, sizeof(outF));
    }

    /* Optional denoised 48 kHz output */
    if (denoisedOutput != NULL) {
        jsize out_len = (*env)->GetArrayLength(env, denoisedOutput);
        jsize out_copy = (out_len < frame_len) ? out_len : frame_len;
        if (out_copy > 0) {
            jshort tmp[FRAME_SIZE];
            for (jsize i = 0; i < out_copy; ++i) tmp[i] = float_to_pcm16(outF[i]);
            (*env)->SetShortArrayRegion(env, denoisedOutput, 0, out_copy, tmp);
        }
    }

    /* Decimate to 8 kHz and buffer until we have 160 samples */
    float decim_tmp[FRAME_SIZE]; /* upper bound; function will produce floor(N/6) */
    size_t produced = decimate6_process(&handle->decimator, outF, FRAME_SIZE, decim_tmp);

    if (produced > 0) {
        size_t to_copy = produced;
        if (to_copy > DECIMATED_FRAME_TARGET - handle->decim_count) {
            to_copy = DECIMATED_FRAME_TARGET - handle->decim_count;
        }
        memcpy(handle->decim_accum + handle->decim_count, decim_tmp, to_copy * sizeof(float));
        handle->decim_count += to_copy;
    }

    if (handle->decim_count >= DECIMATED_FRAME_TARGET) {
        /* We have 160 samples ready */
        if (decimatedOutput != NULL) {
            jsize decim_out_len = (*env)->GetArrayLength(env, decimatedOutput);
            if (decim_out_len >= DECIMATED_FRAME_TARGET) {
                jshort out_short[DECIMATED_FRAME_TARGET];
                for (int i = 0; i < DECIMATED_FRAME_TARGET; ++i) {
                    out_short[i] = float_to_pcm16(handle->decim_accum[i]);
                }
                (*env)->SetShortArrayRegion(env, decimatedOutput, 0, DECIMATED_FRAME_TARGET, out_short);
            } else {
                LOGE("decimatedOutput length too small: %d", (int)decim_out_len);
            }
        }
        /* Reset accumulator (drop any excess) */
        handle->decim_count = 0;

        /* Release input (no need to copy back) */
        (*env)->ReleaseShortArrayElements(env, inputFrame, in_ptr, JNI_ABORT);
        return (jint)DECIMATED_FRAME_TARGET;
    }

    /* Release input (no need to copy back) */
    (*env)->ReleaseShortArrayElements(env, inputFrame, in_ptr, JNI_ABORT);
    return (jint)0;
}

JNIEXPORT void JNICALL
Java_com_zgo_recordplayer_audio_RnnoiseProcessor_nativeDestroy(JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    RNHandle *handle = handle_from_jlong(h);
    if (!handle) return;

    if (handle->st) {
        rnnoise_destroy(handle->st);
        handle->st = NULL;
    }

    free(handle);
}
