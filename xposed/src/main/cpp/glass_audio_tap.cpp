#include "glass_audio_tap.h"
#include "glass_log.h"

#include <aaudio/AAudio.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <mutex>
#include <pthread.h>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

namespace glass {

namespace {

constexpr uint32_t kMagic = 0x50544D47;  // "GMTP"
constexpr uint16_t kVersion = 1;
constexpr size_t kRingBytes = 1 << 20;   // 16k 单声道约 32s 的写出余量
constexpr size_t kMaxStreams = 16;
constexpr uint16_t kOverflowSlot = 0xFFFF;

#pragma pack(push, 1)
struct RecordHeader {
    uint32_t magic;
    uint16_t version;
    uint16_t header_bytes;
    uint32_t seq;
    uint16_t stream_slot;
    uint16_t path;
    uint32_t sample_rate;
    uint16_t channels;
    uint16_t src_format;
    uint32_t frames;
    uint32_t missing_frames;
    int64_t realtime_ms;
    int64_t monotonic_ns;
    uint32_t payload_bytes;
    uint32_t reserved;
};
#pragma pack(pop)
static_assert(sizeof(RecordHeader) == 56, "tap header layout");

// fd 锁：只在写线程 send 与 set_tap_fd 之间使用。
std::mutex g_fd_mutex;
int g_fd = -1;

// 环形缓冲锁：音频线程只 try_lock，拿不到就记为抓取丢失（seq 跳号）。
std::mutex g_ring_mutex;
uint8_t g_ring[kRingBytes];
uint64_t g_head = 0;
uint64_t g_tail = 0;
uint64_t g_generation = 0;
const void* g_slots[kMaxStreams] = {};
size_t g_slot_count = 0;

std::atomic<bool> g_active{false};
std::atomic<uint32_t> g_seq{0};
std::atomic<bool> g_writer_started{false};

int64_t clock_ns(clockid_t id) {
    timespec ts{};
    clock_gettime(id, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;
}

// 调用方持有 g_ring_mutex 且已确认空间足够。
void ring_put(const void* src, size_t bytes) {
    const auto* p = static_cast<const uint8_t*>(src);
    const size_t index = g_head % kRingBytes;
    const size_t first = std::min(bytes, kRingBytes - index);
    std::memcpy(g_ring + index, p, first);
    std::memcpy(g_ring, p + first, bytes - first);
    g_head += bytes;
}

uint16_t slot_for(const void* stream_id) {
    for (size_t i = 0; i < g_slot_count; ++i) {
        if (g_slots[i] == stream_id) return static_cast<uint16_t>(i);
    }
    if (g_slot_count >= kMaxStreams) return kOverflowSlot;
    g_slots[g_slot_count] = stream_id;
    return static_cast<uint16_t>(g_slot_count++);
}

int16_t sample_to_i16(const uint8_t* p, int32_t fmt) {
    switch (fmt) {
        case AAUDIO_FORMAT_PCM_I16: {
            int16_t v;
            std::memcpy(&v, p, 2);
            return v;
        }
        case AAUDIO_FORMAT_PCM_FLOAT: {
            float f;
            std::memcpy(&f, p, 4);
            f = std::max(-1.0f, std::min(1.0f, f));
            return static_cast<int16_t>(f * 32767.0f);
        }
        case AAUDIO_FORMAT_PCM_I32: {
            int32_t v;
            std::memcpy(&v, p, 4);
            return static_cast<int16_t>(v >> 16);
        }
        case AAUDIO_FORMAT_PCM_I24_PACKED:
            return static_cast<int16_t>(p[1] | (p[2] << 8));
        default:
            return 0;
    }
}

int32_t src_sample_bytes(int32_t fmt) {
    switch (fmt) {
        case AAUDIO_FORMAT_PCM_FLOAT:
        case AAUDIO_FORMAT_PCM_I32:        return 4;
        case AAUDIO_FORMAT_PCM_I24_PACKED: return 3;
        default:                           return 2;
    }
}

void disable_if_generation(uint64_t generation, const char* reason) {
    int fd = -1;
    {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        std::lock_guard<std::mutex> ring_lock(g_ring_mutex);
        if (generation != g_generation) return;
        fd = g_fd;
        g_fd = -1;
        g_active.store(false, std::memory_order_relaxed);
        g_head = g_tail = 0;
    }
    if (fd >= 0) ::close(fd);
    LOGI("audio tap closed: %s", reason);
}

void writer_loop() {
    pthread_setname_np(pthread_self(), "GlassTapWriter");
    static uint8_t chunk[64 * 1024];
    size_t len = 0;
    size_t off = 0;
    uint64_t chunk_generation = 0;
    while (true) {
        if (off >= len) {
            len = off = 0;
            std::lock_guard<std::mutex> lock(g_ring_mutex);
            const size_t n = static_cast<size_t>(std::min<uint64_t>(g_head - g_tail, sizeof(chunk)));
            const size_t index = g_tail % kRingBytes;
            const size_t first = std::min(n, kRingBytes - index);
            std::memcpy(chunk, g_ring + index, first);
            std::memcpy(chunk + first, g_ring, n - first);
            g_tail += n;
            len = n;
            chunk_generation = g_generation;
        }
        if (len == 0) {
            usleep(20'000);
            continue;
        }

        ssize_t r = -1;
        int err = 0;
        {
            std::lock_guard<std::mutex> lock(g_fd_mutex);
            if (g_fd < 0 || chunk_generation != g_generation) {
                // 旧会话残留数据不写进新文件。
                len = off = 0;
                continue;
            }
            r = ::send(g_fd, chunk + off, len - off, MSG_NOSIGNAL | MSG_DONTWAIT);
            if (r < 0) err = errno;
        }
        if (r > 0) {
            off += static_cast<size_t>(r);
        } else if (err == EAGAIN || err == EWOULDBLOCK || err == EINTR) {
            usleep(5'000);
        } else {
            len = off = 0;
            disable_if_generation(chunk_generation, strerror(err));
        }
    }
}

} // namespace

void set_tap_fd(int fd) {
    int old_fd = -1;
    {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        std::lock_guard<std::mutex> ring_lock(g_ring_mutex);
        old_fd = g_fd;
        g_fd = fd;
        ++g_generation;
        g_head = g_tail = 0;
        g_slot_count = 0;
        g_seq.store(0, std::memory_order_relaxed);
        g_active.store(fd >= 0, std::memory_order_relaxed);
    }
    if (old_fd >= 0 && old_fd != fd) ::close(old_fd);
    if (fd >= 0) {
        bool expected = false;
        if (g_writer_started.compare_exchange_strong(expected, true)) {
            std::thread(writer_loop).detach();
        }
    }
    LOGI("set_tap_fd fd=%d", fd);
}

bool tap_active() {
    return g_active.load(std::memory_order_relaxed);
}

void tap_capture(const void* buffer, int32_t aaudio_format, int32_t channels,
                 int32_t sample_rate, int32_t frames, int32_t path,
                 const void* stream_id, int32_t missing_frames) {
    if (!g_active.load(std::memory_order_relaxed)) return;
    if (!buffer || frames <= 0 || channels <= 0) return;

    const uint32_t seq = g_seq.fetch_add(1, std::memory_order_relaxed);
    const size_t samples = static_cast<size_t>(frames) * channels;
    const size_t payload = samples * 2;
    const size_t need = sizeof(RecordHeader) + payload;
    if (need > kRingBytes / 2) return;

    const int64_t realtime_ms = clock_ns(CLOCK_REALTIME) / 1'000'000LL;
    const int64_t monotonic_ns = clock_ns(CLOCK_MONOTONIC);

    std::unique_lock<std::mutex> lock(g_ring_mutex, std::try_to_lock);
    if (!lock.owns_lock()) return;
    if (!g_active.load(std::memory_order_relaxed)) return;
    if (kRingBytes - (g_head - g_tail) < need) return;

    RecordHeader h{};
    h.magic = kMagic;
    h.version = kVersion;
    h.header_bytes = sizeof(RecordHeader);
    h.seq = seq;
    h.stream_slot = slot_for(stream_id);
    h.path = static_cast<uint16_t>(path);
    h.sample_rate = static_cast<uint32_t>(sample_rate);
    h.channels = static_cast<uint16_t>(channels);
    h.src_format = static_cast<uint16_t>(aaudio_format);
    h.frames = static_cast<uint32_t>(frames);
    h.missing_frames = static_cast<uint32_t>(std::max(0, missing_frames));
    h.realtime_ms = realtime_ms;
    h.monotonic_ns = monotonic_ns;
    h.payload_bytes = static_cast<uint32_t>(payload);
    ring_put(&h, sizeof(h));

    const auto* src = static_cast<const uint8_t*>(buffer);
    const int32_t step = src_sample_bytes(aaudio_format);
    int16_t tmp[512];
    size_t done = 0;
    while (done < samples) {
        const size_t n = std::min(samples - done, sizeof(tmp) / sizeof(tmp[0]));
        for (size_t i = 0; i < n; ++i) {
            tmp[i] = sample_to_i16(src + (done + i) * step, aaudio_format);
        }
        ring_put(tmp, n * 2);
        done += n;
    }
}

} // namespace glass
