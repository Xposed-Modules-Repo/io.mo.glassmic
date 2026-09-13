#pragma once
constexpr int ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6, ANDROID_LOG_DEBUG = 3;
inline int __android_log_print(int, const char*, const char*, ...) { return 0; }
