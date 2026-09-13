#include "glass_aaudio.h"
#include "glass_ring_buffer.h"
#include "glass_log.h"

#include <aaudio/AAudio.h>
#include <shadowhook.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <numeric>
#include <thread>
#include <unistd.h>
#include <pthread.h>

namespace glass {

static std::atomic<int32_t> g_decision{static_cast<int32_t>(Decision::REAL_MIC)};
static std::atomic<bool> g_hook_installed{false};

struct PcmFdState {
    int fd = -1;
    int32_t sample_rate = 0;
    int32_t channels = 0;
};

static std::mutex g_fd_mutex;
static PcmFdState g_fd_state;

// 所有 ring 操作由 g_fd_mutex 串行化，避免切源 clear 与读写并发。
// 音频线程只 try_lock；后台 pipe 使用非阻塞 fd，不能持锁等待生产者。
static SpscRingBuffer<65536> g_ring_buffer;
static std::atomic<bool> g_worker_running{false};

static std::atomic<uint64_t> g_pending_reads{0};
static std::atomic<uint64_t> g_pending_bytes{0};
static std::atomic<int32_t> g_last_sr{0};
static std::atomic<int32_t> g_last_ch{0};
static std::atomic<uint64_t> g_pending_underruns{0};
static std::atomic<uint64_t> g_pending_missing_frames{0};
static std::atomic<uint64_t> g_pending_requested_frames{0};
static std::atomic<int32_t> g_last_path{0};

using AAudioStream_read_fn = aaudio_result_t(*)(AAudioStream*, void*, int32_t, int64_t);
static AAudioStream_read_fn g_orig_AAudioStream_read = nullptr;
static void* g_read_hook_stub = nullptr;

// ---- callback 模式 hook 用 ----
using AAudioStreamBuilder_setDataCallback_fn =
    void(*)(AAudioStreamBuilder*, AAudioStream_dataCallback, void*);
static AAudioStreamBuilder_setDataCallback_fn g_orig_setDataCallback = nullptr;
static void* g_setcb_hook_stub = nullptr;

static int32_t bytes_per_dst_sample(aaudio_format_t fmt) {
    switch (fmt) {
        case AAUDIO_FORMAT_PCM_I16:        return 2;
        case AAUDIO_FORMAT_PCM_FLOAT:      return 4;
        case AAUDIO_FORMAT_PCM_I32:        return 4;
        case AAUDIO_FORMAT_PCM_I24_PACKED: return 3;
        default:                           return 2;
    }
}

// 舒适噪声幅度：±8 LSB ≈ -72 dBFS，人耳不可闻，但足以让下游 App 的 VAD 判定"有信号"。
static constexpr int32_t kComfortAmplitude = 8;

// 轻量 xorshift32，仅用于生成极低电平噪声，无需密码学质量。每线程独立种子。
static inline int32_t next_comfort_sample() {
    static thread_local uint32_t s =
        0x9E3779B9u ^ static_cast<uint32_t>(reinterpret_cast<uintptr_t>(&s));
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return static_cast<int32_t>(s % (2u * kComfortAmplitude + 1u)) - kComfortAmplitude;
}

// 用舒适噪声填满输入缓冲，替代纯 0 静音
static void fill_comfort_noise(
    void* buffer, aaudio_format_t fmt, int32_t channels, int32_t numFrames
) {
    const int64_t samples = static_cast<int64_t>(numFrames) * channels;
    switch (fmt) {
        case AAUDIO_FORMAT_PCM_I16: {
            auto* d = static_cast<int16_t*>(buffer);
            for (int64_t i = 0; i < samples; ++i) *d++ = static_cast<int16_t>(next_comfort_sample());
            break;
        }
        case AAUDIO_FORMAT_PCM_FLOAT: {
            auto* d = static_cast<float*>(buffer);
            constexpr float kScale = 1.0f / 32768.0f;
            for (int64_t i = 0; i < samples; ++i) *d++ = next_comfort_sample() * kScale;
            break;
        }
        case AAUDIO_FORMAT_PCM_I32: {
            auto* d = static_cast<int32_t*>(buffer);
            for (int64_t i = 0; i < samples; ++i) *d++ = next_comfort_sample() << 16;
            break;
        }
        case AAUDIO_FORMAT_PCM_I24_PACKED: {
            auto* d = static_cast<uint8_t*>(buffer);
            for (int64_t i = 0; i < samples; ++i) {
                int32_t v = next_comfort_sample() << 8;
                *d++ = static_cast<uint8_t>(v & 0xFF);
                *d++ = static_cast<uint8_t>((v >> 8) & 0xFF);
                *d++ = static_cast<uint8_t>((v >> 16) & 0xFF);
            }
            break;
        }
        default:
            std::memset(buffer, 0,
                        static_cast<size_t>(numFrames) * channels * bytes_per_dst_sample(fmt));
            break;
    }
}

static inline float soft_limit_f(float sample) {
    constexpr float threshold = 30000.0f;
    constexpr float max_val = 32767.0f;
    float abs_val = sample < 0.0f ? -sample : sample;
    if (abs_val <= threshold) return sample;
    float over = abs_val - threshold;
    float range = max_val - threshold;
    float norm = over / range;
    float soft = (norm / (1.0f + norm)) * range;
    float res = threshold + soft;
    return sample < 0.0f ? -res : res;
}

/**
 * 转换 PCM 并写入目标 buffer：
 * 按采样率映射线性插值，不足部分补零；不能把短读数据拉伸到整块目标时长。
 */
static void convert_and_write(
    void* dst,
    aaudio_format_t fmt,
    int32_t dst_channels,
    int32_t dst_sample_rate,
    const int16_t* src,
    int32_t src_frames,
    int32_t src_channels,
    int32_t src_sample_rate,
    int32_t dst_frames
) {
    if (!dst || dst_frames <= 0 || dst_channels <= 0) return;

    // 计算实际可转换的有效目标帧数
    int32_t valid_dst_frames = 0;
    if (src && src_frames > 0 && src_channels > 0 && src_sample_rate > 0 && dst_sample_rate > 0) {
        valid_dst_frames = static_cast<int32_t>(
            (static_cast<int64_t>(src_frames) * dst_sample_rate) / src_sample_rate
        );
        if (valid_dst_frames > dst_frames) valid_dst_frames = dst_frames;
    }

    constexpr float kFloatScale = 1.0f / 32768.0f;

    switch (fmt) {
        case AAUDIO_FORMAT_PCM_I16: {
            auto* d = static_cast<int16_t*>(dst);
            for (int32_t f = 0; f < valid_dst_frames; ++f) {
                double src_pos = (static_cast<double>(f) * src_sample_rate) / dst_sample_rate;
                int32_t i0 = static_cast<int32_t>(src_pos);
                float frac = static_cast<float>(src_pos - i0);
                if (i0 >= src_frames) i0 = src_frames - 1;
                int32_t i1 = (i0 + 1 < src_frames) ? i0 + 1 : i0;

                float lv0 = static_cast<float>(src[i0 * src_channels]);
                float lv1 = static_cast<float>(src[i1 * src_channels]);
                int16_t lv = static_cast<int16_t>(soft_limit_f(lv0 + (lv1 - lv0) * frac));

                int16_t rv = lv;
                if (src_channels > 1) {
                    float rv0 = static_cast<float>(src[i0 * src_channels + 1]);
                    float rv1 = static_cast<float>(src[i1 * src_channels + 1]);
                    rv = static_cast<int16_t>(soft_limit_f(rv0 + (rv1 - rv0) * frac));
                }
                // 欠载时在有效末尾极短淡出（16 帧），消除硬截断导致的咔哒杂音
                if (valid_dst_frames < dst_frames && valid_dst_frames - f <= 16) {
                    float fade = static_cast<float>(valid_dst_frames - f) / 16.0f;
                    lv = static_cast<int16_t>(lv * fade);
                    rv = static_cast<int16_t>(rv * fade);
                }
                for (int32_t c = 0; c < dst_channels; ++c) {
                    *d++ = c == 0 ? lv : (c == 1 ? rv : 0);
                }
            }
            // 欠载不足部分静音填充，避免拼接白噪声切片
            for (int32_t f = valid_dst_frames; f < dst_frames; ++f) {
                for (int32_t c = 0; c < dst_channels; ++c) *d++ = 0;
            }
            break;
        }
        case AAUDIO_FORMAT_PCM_FLOAT: {
            auto* d = static_cast<float*>(dst);
            for (int32_t f = 0; f < valid_dst_frames; ++f) {
                double src_pos = (static_cast<double>(f) * src_sample_rate) / dst_sample_rate;
                int32_t i0 = static_cast<int32_t>(src_pos);
                float frac = static_cast<float>(src_pos - i0);
                if (i0 >= src_frames) i0 = src_frames - 1;
                int32_t i1 = (i0 + 1 < src_frames) ? i0 + 1 : i0;

                float lv0 = static_cast<float>(src[i0 * src_channels]);
                float lv1 = static_cast<float>(src[i1 * src_channels]);
                float lv = (lv0 + (lv1 - lv0) * frac) * kFloatScale;

                float rv = lv;
                if (src_channels > 1) {
                    float rv0 = static_cast<float>(src[i0 * src_channels + 1]);
                    float rv1 = static_cast<float>(src[i1 * src_channels + 1]);
                    rv = (rv0 + (rv1 - rv0) * frac) * kFloatScale;
                }
                if (valid_dst_frames < dst_frames && valid_dst_frames - f <= 16) {
                    float fade = static_cast<float>(valid_dst_frames - f) / 16.0f;
                    lv *= fade;
                    rv *= fade;
                }
                for (int32_t c = 0; c < dst_channels; ++c) {
                    *d++ = c == 0 ? lv : (c == 1 ? rv : 0.0f);
                }
            }
            for (int32_t f = valid_dst_frames; f < dst_frames; ++f) {
                for (int32_t c = 0; c < dst_channels; ++c) *d++ = 0.0f;
            }
            break;
        }
        case AAUDIO_FORMAT_PCM_I32: {
            auto* d = static_cast<int32_t*>(dst);
            for (int32_t f = 0; f < valid_dst_frames; ++f) {
                double src_pos = (static_cast<double>(f) * src_sample_rate) / dst_sample_rate;
                int32_t i0 = static_cast<int32_t>(src_pos);
                float frac = static_cast<float>(src_pos - i0);
                if (i0 >= src_frames) i0 = src_frames - 1;
                int32_t i1 = (i0 + 1 < src_frames) ? i0 + 1 : i0;

                float lv0 = static_cast<float>(src[i0 * src_channels]);
                float lv1 = static_cast<float>(src[i1 * src_channels]);
                int32_t lv = static_cast<int32_t>(soft_limit_f(lv0 + (lv1 - lv0) * frac)) << 16;

                int32_t rv = lv;
                if (src_channels > 1) {
                    float rv0 = static_cast<float>(src[i0 * src_channels + 1]);
                    float rv1 = static_cast<float>(src[i1 * src_channels + 1]);
                    rv = static_cast<int32_t>(soft_limit_f(rv0 + (rv1 - rv0) * frac)) << 16;
                }
                if (valid_dst_frames < dst_frames && valid_dst_frames - f <= 16) {
                    float fade = static_cast<float>(valid_dst_frames - f) / 16.0f;
                    lv = static_cast<int32_t>(lv * fade);
                    rv = static_cast<int32_t>(rv * fade);
                }
                for (int32_t c = 0; c < dst_channels; ++c) {
                    *d++ = c == 0 ? lv : (c == 1 ? rv : 0);
                }
            }
            for (int32_t f = valid_dst_frames; f < dst_frames; ++f) {
                for (int32_t c = 0; c < dst_channels; ++c) *d++ = 0;
            }
            break;
        }
        case AAUDIO_FORMAT_PCM_I24_PACKED: {
            auto* d = static_cast<uint8_t*>(dst);
            for (int32_t f = 0; f < valid_dst_frames; ++f) {
                double src_pos = (static_cast<double>(f) * src_sample_rate) / dst_sample_rate;
                int32_t i0 = static_cast<int32_t>(src_pos);
                float frac = static_cast<float>(src_pos - i0);
                if (i0 >= src_frames) i0 = src_frames - 1;
                int32_t i1 = (i0 + 1 < src_frames) ? i0 + 1 : i0;

                float lv0 = static_cast<float>(src[i0 * src_channels]);
                float lv1 = static_cast<float>(src[i1 * src_channels]);
                int32_t lv = static_cast<int32_t>(soft_limit_f(lv0 + (lv1 - lv0) * frac)) << 8;

                int32_t rv = lv;
                if (src_channels > 1) {
                    float rv0 = static_cast<float>(src[i0 * src_channels + 1]);
                    float rv1 = static_cast<float>(src[i1 * src_channels + 1]);
                    rv = static_cast<int32_t>(soft_limit_f(rv0 + (rv1 - rv0) * frac)) << 8;
                }
                if (valid_dst_frames < dst_frames && valid_dst_frames - f <= 16) {
                    float fade = static_cast<float>(valid_dst_frames - f) / 16.0f;
                    lv = static_cast<int32_t>(lv * fade);
                    rv = static_cast<int32_t>(rv * fade);
                }
                for (int32_t c = 0; c < dst_channels; ++c) {
                    int32_t v = c == 0 ? lv : (c == 1 ? rv : 0);
                    *d++ = static_cast<uint8_t>(v & 0xFF);
                    *d++ = static_cast<uint8_t>((v >> 8) & 0xFF);
                    *d++ = static_cast<uint8_t>((v >> 16) & 0xFF);
                }
            }
            for (int32_t f = valid_dst_frames; f < dst_frames; ++f) {
                for (int32_t c = 0; c < dst_channels; ++c) {
                    *d++ = 0; *d++ = 0; *d++ = 0;
                }
            }
            break;
        }
        default:
            std::memset(dst, 0, static_cast<size_t>(dst_frames) * dst_channels * bytes_per_dst_sample(fmt));
            break;
    }
}

// 后台异步管道读取循环
static void pcm_reader_worker_loop() {
    pthread_setname_np(pthread_self(), "GlassPcmWorker");
    uint8_t chunk[2048];
    while (g_worker_running.load(std::memory_order_relaxed)) {
        ssize_t r = -1;
        {
            std::lock_guard<std::mutex> lock(g_fd_mutex);
            const int fd = g_fd_state.fd;
            if (fd >= 0 && g_ring_buffer.available_write() >= sizeof(chunk)) {
                r = ::read(fd, chunk, sizeof(chunk));
                if (r > 0) g_ring_buffer.write(chunk, static_cast<size_t>(r));
            }
        }
        // 不在持锁期间睡眠；也不会 close/reuse 一个仍在 read 的 fd。
        if (r <= 0) usleep(1000);
    }
}

static void ensure_worker_started() {
    bool expected = false;
    if (g_worker_running.compare_exchange_strong(expected, true)) {
        std::thread t(pcm_reader_worker_loop);
        t.detach();
    }
}

/**
 * 把虚拟音源填进一个已知格式的输入缓冲。
 *
 * 返回值：
 *   FillResult::FILLED   —— buffer 已被虚拟数据覆盖
 *   FillResult::PASS     —— 当前应放行真实麦克风（REAL_MIC）
 */
enum class FillResult { FILLED, PASS };

static FillResult fill_pcm_impl(
    void* buffer,
    aaudio_format_t dst_fmt,
    int32_t dst_channels,
    int32_t dst_sample_rate,
    int32_t numFrames,
    CapturePath path
) {
    if (!buffer || numFrames <= 0 || dst_channels <= 0) return FillResult::PASS;

    Decision decision = static_cast<Decision>(g_decision.load(std::memory_order_relaxed));
    if (decision == Decision::REAL_MIC) return FillResult::PASS;

    if (dst_sample_rate <= 0) dst_sample_rate = 48'000;
    g_last_path.store(static_cast<int32_t>(path), std::memory_order_relaxed);

    if (decision == Decision::SILENCE) {
        fill_comfort_noise(buffer, dst_fmt, dst_channels, numFrames);
        g_pending_reads.fetch_add(1, std::memory_order_relaxed);
        g_pending_bytes.fetch_add(static_cast<uint64_t>(numFrames) * dst_channels * 2, std::memory_order_relaxed);
        g_last_sr.store(dst_sample_rate, std::memory_order_relaxed);
        g_last_ch.store(dst_channels, std::memory_order_relaxed);
        return FillResult::FILLED;
    }

    // decision == FILE: 回调不得等待 worker / 切源线程持有的锁。
    std::unique_lock<std::mutex> lock(g_fd_mutex, std::try_to_lock);
    g_pending_requested_frames.fetch_add(numFrames, std::memory_order_relaxed);
    int32_t src_channels = 1;
    int32_t src_sample_rate = 48'000;
    if (lock.owns_lock()) {
        src_channels = g_fd_state.channels > 0 ? g_fd_state.channels : 1;
        src_sample_rate = g_fd_state.sample_rate > 0 ? g_fd_state.sample_rate : 48'000;
    }

    // 大请求分块转换，不能把超过栈缓冲容量的整段音频直接补零。
    // 中间块按采样率公约数对齐，避免每块向上取整额外吞掉源帧。
    int16_t tmp[8192];
    const int32_t max_src_frames = 8192 / src_channels;
    const int32_t quantum = dst_sample_rate / std::gcd(src_sample_rate, dst_sample_rate);
    const int32_t max_dst_frames = static_cast<int32_t>(
        static_cast<int64_t>(max_src_frames) * dst_sample_rate / src_sample_rate);
    const int32_t block_frames = (max_dst_frames / quantum) * quantum;
    uint64_t got = 0;
    int32_t missing = 0;
    int32_t offset = 0;
    while (offset < numFrames) {
        const int32_t count = std::min(numFrames - offset,
            block_frames > 0 ? block_frames : max_dst_frames);
        if (count <= 0) break;
        const int32_t need = static_cast<int32_t>(
            (static_cast<int64_t>(count) * src_sample_rate + dst_sample_rate - 1) / dst_sample_rate);
        const size_t frame_bytes = static_cast<size_t>(src_channels) * 2;
        size_t bytes = 0;
        if (lock.owns_lock()) {
            const size_t available = g_ring_buffer.available_read() / frame_bytes * frame_bytes;
            bytes = g_ring_buffer.read(reinterpret_cast<uint8_t*>(tmp),
                std::min(available, static_cast<size_t>(need) * frame_bytes));
        }
        const int32_t frames = static_cast<int32_t>(bytes / frame_bytes);
        auto* dst = static_cast<uint8_t*>(buffer) +
            static_cast<size_t>(offset) * dst_channels * bytes_per_dst_sample(dst_fmt);
        convert_and_write(dst, dst_fmt, dst_channels, dst_sample_rate,
            tmp, frames, src_channels, src_sample_rate, count);
        const int32_t valid = static_cast<int32_t>(std::min<int64_t>(count,
            static_cast<int64_t>(frames) * dst_sample_rate / src_sample_rate));
        missing += count - valid;
        got += bytes;
        offset += count;
    }
    if (offset < numFrames) {
        auto* dst = static_cast<uint8_t*>(buffer) +
            static_cast<size_t>(offset) * dst_channels * bytes_per_dst_sample(dst_fmt);
        std::memset(dst, 0, static_cast<size_t>(numFrames - offset) * dst_channels * bytes_per_dst_sample(dst_fmt));
        missing += numFrames - offset;
    }
    if (missing > 0) {
        g_pending_underruns.fetch_add(1, std::memory_order_relaxed);
        g_pending_missing_frames.fetch_add(missing, std::memory_order_relaxed);
    }

    g_pending_reads.fetch_add(1, std::memory_order_relaxed);
    g_pending_bytes.fetch_add(static_cast<uint64_t>(got), std::memory_order_relaxed);
    g_last_sr.store(dst_sample_rate, std::memory_order_relaxed);
    g_last_ch.store(dst_channels, std::memory_order_relaxed);
    return FillResult::FILLED;
}

static FillResult fill_input_buffer(AAudioStream* stream, void* buffer, int32_t numFrames, CapturePath path) {
    if (!stream) return FillResult::PASS;
    int32_t ch = AAudioStream_getChannelCount(stream);
    if (ch <= 0) ch = 1;
    int32_t sr = AAudioStream_getSampleRate(stream);
    if (sr <= 0) sr = 48'000;
    aaudio_format_t fmt = AAudioStream_getFormat(stream);
    return fill_pcm_impl(buffer, fmt, ch, sr, numFrames, path);
}

// 导出给 OpenSL ES / AudioRecord 路径用
bool fill_pcm(void* buffer, SampleFmt sf, int32_t channels, int32_t sample_rate, int32_t frames, CapturePath path) {
    aaudio_format_t fmt = (sf == SampleFmt::FLOAT)
        ? AAUDIO_FORMAT_PCM_FLOAT
        : AAUDIO_FORMAT_PCM_I16;
    return fill_pcm_impl(buffer, fmt, channels, sample_rate, frames, path) == FillResult::FILLED;
}

// =================== 阻塞 read 路径 ===================

static aaudio_result_t my_AAudioStream_read(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanos
) {
    if (!stream || !buffer || numFrames <= 0) {
        return g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    }

    if (AAudioStream_getDirection(stream) != AAUDIO_DIRECTION_INPUT) {
        return g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    }

    // 保留系统录音时钟、超时、短读和错误语义，只覆盖实际返回的帧。
    // 否则紧循环会耗尽实时 PCM，并把大量补零帧发送给语音引擎。
    const bool was_reading = aaudio_read_in_progress;
    aaudio_read_in_progress = true;
    const aaudio_result_t frames = g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    aaudio_read_in_progress = was_reading;
    if (frames > 0) fill_input_buffer(stream, buffer, frames, CapturePath::AAUDIO_READ);
    return frames;
}

// =================== data-callback 路径 ===================

struct CbWrapper {
    AAudioStream_dataCallback orig_cb;
    void* orig_ud;
};

static aaudio_data_callback_result_t my_data_callback(
    AAudioStream* stream,
    void* userData,
    void* audioData,
    int32_t numFrames
) {
    auto* w = static_cast<CbWrapper*>(userData);

    if (stream && audioData && numFrames > 0 &&
        AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_INPUT) {
        fill_input_buffer(stream, audioData, numFrames, CapturePath::AAUDIO_CALLBACK);
    }

    if (w && w->orig_cb) {
        return w->orig_cb(stream, w->orig_ud, audioData, numFrames);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void my_setDataCallback(
    AAudioStreamBuilder* builder,
    AAudioStream_dataCallback callback,
    void* userData
) {
    if (callback == nullptr) {
        g_orig_setDataCallback(builder, nullptr, userData);
        return;
    }

    auto* w = new CbWrapper{callback, userData};
    g_orig_setDataCallback(builder, &my_data_callback, w);
}

bool install_aaudio_hook() {
    bool expected = false;
    if (!g_hook_installed.compare_exchange_strong(expected, true)) {
        return true;
    }

    g_read_hook_stub = shadowhook_hook_sym_name(
        "libaaudio.so",
        "AAudioStream_read",
        reinterpret_cast<void*>(my_AAudioStream_read),
        reinterpret_cast<void**>(&g_orig_AAudioStream_read)
    );
    if (!g_read_hook_stub) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        LOGE("hook AAudioStream_read failed: err=%d %s", err, msg ? msg : "?");
        g_hook_installed.store(false);
        return false;
    }
    LOGI("AAudioStream_read hook installed");

    g_setcb_hook_stub = shadowhook_hook_sym_name(
        "libaaudio.so",
        "AAudioStreamBuilder_setDataCallback",
        reinterpret_cast<void*>(my_setDataCallback),
        reinterpret_cast<void**>(&g_orig_setDataCallback)
    );
    if (!g_setcb_hook_stub) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        LOGW("hook AAudioStreamBuilder_setDataCallback failed: err=%d %s", err, msg ? msg : "?");
    } else {
        LOGI("AAudioStreamBuilder_setDataCallback hook installed");
    }

    ensure_worker_started();
    return true;
}

void set_decision(Decision decision) {
    std::lock_guard<std::mutex> lock(g_fd_mutex);
    int32_t new_d = static_cast<int32_t>(decision);
    int32_t old_d = g_decision.exchange(new_d, std::memory_order_relaxed);
    if (old_d != new_d) {
        g_ring_buffer.clear();
        LOGI("decision changed %d -> %d, ring buffer cleared", old_d, new_d);
    }
}

Decision get_decision() {
    return static_cast<Decision>(g_decision.load(std::memory_order_relaxed));
}

void set_pcm_fd(int fd, int32_t sample_rate, int32_t channels) {
    if (fd >= 0) {
        const int flags = fcntl(fd, F_GETFL);
        if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
            LOGW("cannot make PCM fd nonblocking: %s", strerror(errno));
            ::close(fd);
            fd = -1;
        }
    }
    if (fd >= 0) {
#if defined(F_SETPIPE_SZ)
        int ret = fcntl(fd, F_SETPIPE_SZ, 8192);
        if (ret < 0) {
            LOGW("fcntl F_SETPIPE_SZ 8192 failed on fd=%d: %s", fd, strerror(errno));
        } else {
            LOGI("pipe buffer size resized to %d on fd=%d", ret, fd);
        }
#endif
    }

    int old_fd = -1;
    {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        old_fd = g_fd_state.fd;
        g_fd_state.fd = fd;
        g_fd_state.sample_rate = sample_rate;
        g_fd_state.channels = channels > 0 ? channels : 1;
        g_ring_buffer.clear();
        if (old_fd >= 0 && old_fd != fd) ::close(old_fd);
    }
    ensure_worker_started();
    LOGI("set_pcm_fd fd=%d sr=%d ch=%d (ring buffer cleared)", fd, sample_rate, channels);
}

void drain_stats(
    uint64_t* out_reads,
    uint64_t* out_bytes,
    int32_t* out_last_sr,
    int32_t* out_last_ch,
    uint64_t* out_underruns,
    uint64_t* out_missing_frames,
    uint64_t* out_requested_frames,
    int32_t* out_path
) {
    if (out_reads) *out_reads = g_pending_reads.exchange(0, std::memory_order_relaxed);
    if (out_bytes) *out_bytes = g_pending_bytes.exchange(0, std::memory_order_relaxed);
    if (out_last_sr) *out_last_sr = g_last_sr.load(std::memory_order_relaxed);
    if (out_last_ch) *out_last_ch = g_last_ch.load(std::memory_order_relaxed);
    if (out_underruns) *out_underruns = g_pending_underruns.exchange(0, std::memory_order_relaxed);
    if (out_missing_frames) *out_missing_frames = g_pending_missing_frames.exchange(0, std::memory_order_relaxed);
    if (out_requested_frames) *out_requested_frames = g_pending_requested_frames.exchange(0, std::memory_order_relaxed);
    if (out_path) *out_path = g_last_path.load(std::memory_order_relaxed);
}

} // namespace glass
