// Compile the actual implementation against inert host platform declarations.
// No Android device, hook installation, or third-party process is used.
#include "../../xposed/src/main/cpp/glass_aaudio.cpp"
#include <cassert>
#include <chrono>
#include <iostream>
#include <vector>

using namespace glass;

static void prepare(int frames, int channels = 1) {
    set_decision(Decision::FILE);
    std::lock_guard<std::mutex> lock(g_fd_mutex);
    g_fd_state.sample_rate = 48000;
    g_fd_state.channels = channels;
    g_ring_buffer.clear();
    std::vector<int16_t> pcm(frames * channels);
    for (int f = 0; f < frames; ++f)
        for (int ch = 0; ch < channels; ++ch) pcm[f * channels + ch] = 1000 + f % 1000;
    assert(g_ring_buffer.write(reinterpret_cast<uint8_t*>(pcm.data()), pcm.size() * 2) == pcm.size() * 2);
}

static int readCalls = 0, readResult = 0;
static int64_t seenTimeout = -1;
static aaudio_result_t system_read(AAudioStream*, void*, int32_t, int64_t timeout) {
    ++readCalls;
    seenTimeout = timeout;
    assert(aaudio_read_in_progress);
    if (timeout > 0) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    return readResult;
}

static void read_contract() {
    AAudioStream stream;
    g_orig_AAudioStream_read = system_read;
    for (int result : {0, -899, 120, 480}) {
        prepare(480);
        readResult = result;
        std::vector<int16_t> out(481, -123);
        const int before = readCalls;
        assert(my_AAudioStream_read(&stream, out.data(), 480, 0) == result);
        assert(readCalls == before + 1 && seenTimeout == 0);
        assert(!aaudio_read_in_progress);
        const int filled = std::max(result, 0);
        for (int i = 0; i < filled; ++i) assert(out[i] == 1000 + i);
        for (int i = filled; i <= 480; ++i) assert(out[i] == -123);
        assert(g_ring_buffer.available_read() == static_cast<size_t>(480 - filled) * 2);
    }
    prepare(480);
    readResult = 480;
    int16_t out[480];
    const auto start = std::chrono::steady_clock::now();
    assert(my_AAudioStream_read(&stream, out, 480, 50000000) == 480);
    assert(seenTimeout == 50000000);
    assert(std::chrono::steady_clock::now() - start >= std::chrono::milliseconds(5));
    std::cout << "PASS: system read, timeout, short/zero/error results and buffer boundaries\n";
}

static void large_buffers() {
    for (int sr : {8000, 16000, 44100, 48000, 96000}) {
        for (int channels : {1, 2}) {
            const int frames = sr / 4; // 250 ms needs 24 KB of mono source, above old 16 KB cap.
            prepare(12000, channels);
            std::vector<int16_t> out(frames * channels + 1, -123);
            assert(fill_pcm(out.data(), SampleFmt::S16, channels, sr, frames, CapturePath::OPENSL));
            for (int f = 0; f < frames; ++f) {
                const double pos = static_cast<double>(f) * 48000 / sr;
                const int i = static_cast<int>(pos);
                const double frac = pos - i;
                const int s0 = 1000 + i % 1000;
                const int s1 = 1000 + std::min(i + 1, 11999) % 1000;
                const int expected = static_cast<int>(s0 + (s1 - s0) * frac);
                for (int ch = 0; ch < channels; ++ch)
                    assert(std::abs(out[f * channels + ch] - expected) <= 1);
            }
            assert(out.back() == -123);
            assert(g_ring_buffer.available_read() == 0);
        }
    }
    for (auto format : {AAUDIO_FORMAT_PCM_FLOAT, AAUDIO_FORMAT_PCM_I32, AAUDIO_FORMAT_PCM_I24_PACKED}) {
        prepare(12000);
        const int bps = bytes_per_dst_sample(format);
        std::vector<uint32_t> aligned((12000 * bps + 7) / 4, 0);
        auto* bytes = reinterpret_cast<uint8_t*>(aligned.data());
        bytes[12000 * bps] = 0x7e;
        assert(fill_pcm_impl(bytes, format, 1, 48000, 12000, CapturePath::AAUDIO_CALLBACK) == FillResult::FILLED);
        assert(bytes[12000 * bps] == 0x7e);
        assert(std::any_of(bytes + 10000 * bps, bytes + 12000 * bps, [](uint8_t v) { return v != 0; }));
        assert(g_ring_buffer.available_read() == 0);
    }
    std::cout << "PASS: 250 ms buffers, five rates, mono/stereo and four PCM formats\n";
}

static void underruns() {
    uint64_t reads, bytes, missing, requested, underrun;
    int32_t sr, ch, path;
    auto drain = [&] { drain_stats(&reads, &bytes, &sr, &ch, &underrun, &missing, &requested, &path); };
    drain();
    prepare(120);
    int16_t out[480];
    fill_pcm(out, SampleFmt::S16, 1, 48000, 480, CapturePath::AUDIORECORD);
    drain();
    assert(reads == 1 && bytes == 240 && underrun == 1 && missing == 360 && requested == 480 && path == 3);
    assert(std::all_of(out + 120, out + 480, [](int16_t v) { return v == 0; }));
    fill_pcm(out, SampleFmt::S16, 1, 48000, 480, CapturePath::AUDIORECORD);
    drain();
    assert(reads == 1 && bytes == 0 && underrun == 1 && missing == 480 && requested == 480);
    drain();
    assert(reads == 0 && bytes == 0 && underrun == 0 && missing == 0 && requested == 0);
    std::cout << "PASS: partial/complete underrun accounting, including zero source bytes\n";
}

static void contention_and_reset() {
    prepare(480);
    std::atomic<bool> locked{false}, done{false};
    std::thread holder([&] {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        locked.store(true);
        while (!done.load()) std::this_thread::yield();
    });
    while (!locked.load()) std::this_thread::yield();
    int16_t out[480];
    const auto start = std::chrono::steady_clock::now();
    fill_pcm(out, SampleFmt::S16, 1, 48000, 480, CapturePath::OPENSL);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    done.store(true);
    holder.join();
    assert(elapsed < std::chrono::milliseconds(100));
    assert(std::all_of(out, out + 480, [](int16_t v) { return v == 0; }));
    assert(g_ring_buffer.available_read() == 960);
    std::thread writer([] {
        const uint8_t data[1920] = {};
        for (int i = 0; i < 3000; ++i) {
            std::lock_guard<std::mutex> lock(g_fd_mutex);
            g_ring_buffer.write(data, sizeof(data));
        }
    });
    std::thread resetter([] {
        for (int i = 0; i < 3000; ++i) {
            set_decision(Decision::REAL_MIC);
            set_decision(Decision::FILE);
        }
    });
    for (int i = 0; i < 3000; ++i) {
        fill_pcm(out, SampleFmt::S16, 1, 48000, 480, CapturePath::OPENSL);
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        assert(g_ring_buffer.available_read() <= 65536);
    }
    writer.join();
    resetter.join();
    std::cout << "PASS: nonblocking contention and concurrent decision/reset/read/write\n";
}

int main() {
    read_contract();
    large_buffers();
    underruns();
    contention_and_reset();
}
