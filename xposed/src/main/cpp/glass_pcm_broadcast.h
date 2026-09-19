#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>

namespace glass {

// Bounded PCM history with independent read positions. The caller serializes access.
// A slow/stopped recorder must neither consume another recorder's samples nor block
// the producer. Old history is overwritten; lagging readers rejoin the live window.
template <size_t Capacity = 65536, size_t Readers = 32>
class PcmBroadcastBuffer {
public:
    size_t write(const uint8_t* src, size_t count) {
        if (!src || count == 0) return 0;
        const size_t keep = std::min(count, Capacity);
        copy_in(tail_ + count - keep, src + count - keep, keep);
        tail_ += count;
        return count;
    }

    size_t read(uint8_t* dst, size_t count, const void* stream, int32_t path,
                size_t frame_bytes, size_t join_bytes, uint64_t* skipped_bytes = nullptr) {
        if (!dst || frame_bytes == 0) return 0;
        Cursor* cursor = nullptr;
        for (auto& c : cursors_) {
            if (c.used && c.stream == stream && c.path == path) {
                cursor = &c;
                break;
            }
        }
        const uint64_t end = tail_ / frame_bytes * frame_bytes;
        const uint64_t oldest = tail_ > Capacity
            ? (tail_ - Capacity + frame_bytes - 1) / frame_bytes * frame_bytes : 0;
        const uint64_t join = std::max(oldest,
            end - std::min<uint64_t>(end, join_bytes / frame_bytes * frame_bytes));
        if (!cursor) {
            cursor = &*std::min_element(cursors_.begin(), cursors_.end(),
                [](const Cursor& a, const Cursor& b) { return a.touched < b.touched; });
            *cursor = {stream, path, join, 0, true};
        }
        cursor->touched = ++sequence_;
        if (cursor->position < oldest) {
            if (skipped_bytes) *skipped_bytes += join - cursor->position;
            cursor->position = join;
        }
        const size_t bytes = std::min<uint64_t>(count / frame_bytes * frame_bytes,
                                               end - cursor->position);
        copy_out(cursor->position, dst, bytes);
        cursor->position += bytes;
        return bytes;
    }

    void clear() {
        tail_ = 0;
        sequence_ = 0;
        cursors_ = {};
    }

    size_t available_read(const void* stream, int32_t path) const {
        for (const auto& c : cursors_) {
            if (c.used && c.stream == stream && c.path == path)
                return std::min<uint64_t>(Capacity, tail_ - c.position);
        }
        return 0;
    }

private:
    struct Cursor {
        const void* stream = nullptr;
        int32_t path = 0;
        uint64_t position = 0;
        uint64_t touched = 0;
        bool used = false;
    };
    void copy_in(uint64_t position, const uint8_t* src, size_t bytes) {
        const size_t index = position % Capacity;
        const size_t first = std::min(bytes, Capacity - index);
        std::memcpy(data_.data() + index, src, first);
        std::memcpy(data_.data(), src + first, bytes - first);
    }
    void copy_out(uint64_t position, uint8_t* dst, size_t bytes) const {
        const size_t index = position % Capacity;
        const size_t first = std::min(bytes, Capacity - index);
        std::memcpy(dst, data_.data() + index, first);
        std::memcpy(dst + first, data_.data(), bytes - first);
    }
    std::array<uint8_t, Capacity> data_{};
    std::array<Cursor, Readers> cursors_{};
    uint64_t tail_ = 0;
    uint64_t sequence_ = 0;
};

} // namespace glass
