#include "glass_aaudio.h"
#include "glass_opensl.h"
#include "glass_audiorecord.h"
#include "glass_log.h"

#include <cerrno>
#include <cstring>
#include <jni.h>
#include <unistd.h>

using namespace glass;

extern "C" {

JNIEXPORT jint JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeInstall(JNIEnv* env, jclass) {
    // AAudio 是主路径——失败才算装载失败。OpenSL ES 作为补充路径，失败不致命
    // （例如某些机型/进程未用到 OpenSL 录音）。
    bool aaudio_ok = install_aaudio_hook();
    bool opensl_ok = install_opensl_hook();
    bool arecord_ok = install_audiorecord_hook();
    LOGI("native hooks installed: aaudio=%d opensl=%d audiorecord=%d",
         aaudio_ok, opensl_ok, arecord_ok);
    return aaudio_ok ? 0 : -1;
}

JNIEXPORT void JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeSetDecision(JNIEnv* env, jclass, jint d) {
    Decision dec = Decision::REAL_MIC;
    switch (d) {
        case 0: dec = Decision::REAL_MIC; break;
        case 1: dec = Decision::FILE; break;
        case 2: dec = Decision::SILENCE; break;
        default: dec = Decision::REAL_MIC; break;
    }
    set_decision(dec);
}

/**
 * Kotlin 侧打开 ContentProvider 的 pipe 后把 fd 通过 ParcelFileDescriptor.detachFd() 取出来再传给我们。
 * native 持有该 fd 的所有权——上一次设置的 fd 会在这里被 close。
 * 传 -1 == 清空。
 */
JNIEXPORT void JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeSetPcmFd(
    JNIEnv* env, jclass, jint fd, jint sample_rate, jint channels
) {
    set_pcm_fd(fd, sample_rate, channels);
}

/**
 * dup() a FileDescriptor and return the new fd number.
 * Used on API < 33 where ParcelFileDescriptor.detachFd() is unavailable.
 */
JNIEXPORT jint JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeDupFd(JNIEnv* env, jclass, jobject fdObj) {
    jclass fdClass = env->FindClass("java/io/FileDescriptor");
    jfieldID fidField = env->GetFieldID(fdClass, "descriptor", "I");
    jint rawFd = env->GetIntField(fdObj, fidField);
    jint newFd = ::dup(rawFd);
    if (newFd < 0) {
        LOGE("dup(%d) failed: %s", rawFd, strerror(errno));
    }
    return newFd;
}

/**
 * 返回 long[8]：读取次数、源字节数、目标采样率/声道、欠载次数、缺失帧、请求帧、接口。
 */
JNIEXPORT jlongArray JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeDrainStats(JNIEnv* env, jclass) {
    uint64_t reads = 0, bytes = 0;
    int32_t  sr = 0, ch = 0;
    uint64_t underruns = 0, missing = 0, requested = 0;
    int32_t path = 0;
    drain_stats(&reads, &bytes, &sr, &ch, &underruns, &missing, &requested, &path);

    jlong out[8];
    out[0] = static_cast<jlong>(reads);
    out[1] = static_cast<jlong>(bytes);
    out[2] = static_cast<jlong>(sr);
    out[3] = static_cast<jlong>(ch);
    out[4] = static_cast<jlong>(underruns);
    out[5] = static_cast<jlong>(missing);
    out[6] = static_cast<jlong>(requested);
    out[7] = static_cast<jlong>(path);

    jlongArray arr = env->NewLongArray(8);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 8, out);
    return arr;
}

} // extern "C"
