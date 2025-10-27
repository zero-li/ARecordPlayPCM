/*
 * RNNoise JNI bridge implemented in C.
 *
 * Processing chain (48 kHz mono PCM16 input -> 8 kHz mono PCM16 output):
 *
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

#include "resample/decimate6.h"
#include "rnnoise.h"

#define LOG_TAG "RnnoiseJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------------- */
/* Utility helpers                                                           */
/* ------------------------------------------------------------------------- */
