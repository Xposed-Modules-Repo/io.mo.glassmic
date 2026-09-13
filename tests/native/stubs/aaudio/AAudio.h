#pragma once
#include <cstdint>
using aaudio_result_t = int32_t;
using aaudio_format_t = int32_t;
using aaudio_data_callback_result_t = int32_t;
constexpr int AAUDIO_FORMAT_PCM_I16 = 1, AAUDIO_FORMAT_PCM_FLOAT = 2;
constexpr int AAUDIO_FORMAT_PCM_I32 = 4, AAUDIO_FORMAT_PCM_I24_PACKED = 3;
constexpr int AAUDIO_DIRECTION_INPUT = 1, AAUDIO_CALLBACK_RESULT_CONTINUE = 0;
struct AAudioStream { int channels = 1, rate = 48000, format = 1, direction = 1; };
struct AAudioStreamBuilder {};
using AAudioStream_dataCallback = aaudio_data_callback_result_t (*)(AAudioStream*, void*, void*, int32_t);
inline int AAudioStream_getChannelCount(AAudioStream* s) { return s->channels; }
inline int AAudioStream_getSampleRate(AAudioStream* s) { return s->rate; }
inline int AAudioStream_getFormat(AAudioStream* s) { return s->format; }
inline int AAudioStream_getDirection(AAudioStream* s) { return s->direction; }
