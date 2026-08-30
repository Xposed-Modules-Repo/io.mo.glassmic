#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <algorithm>

namespace glass {

/**
 * 专为 PCM 字节流设计的单生产者单消费者 (SPSC) 无锁环形缓冲区。
 *
 * 生产者：独立后台管道拉取线程 (PcmReaderWorker)。
 * 消费者：硬实时音频回调线程 (AAudio / OpenSL ES / AudioRecord Callback)。
 *
 * 保证音频回调线程读操作 O(1)、零锁、零内存分配、零系统调用。
 */
template <size_t Capacity = 65536> // 默认 64KB (~680ms @ 48kHz / ~2s @ 16kHz mono PCM16，提供充裕抗抖动水位)
class SpscRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of 2");

public:
    SpscRingBuffer() : head_(0), tail_(0) {}

    /**
     * 写入数据（仅生产者线程调用）。
     * 返回实际写入的字节数。
     */
    size_t write(const uint8_t* src, size_t count) {
        if (!src || count == 0) return 0;

        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t free_space = Capacity - (tail - head);
        const size_t to_write = std::min(count, free_space);

        if (to_write == 0) return 0;

        const size_t mask = Capacity - 1;
        const size_t idx = tail & mask;
        const size_t first_chunk = std::min(to_write, Capacity - idx);

        std::memcpy(buffer_ + idx, src, first_chunk);
        if (to_write > first_chunk) {
            std::memcpy(buffer_, src + first_chunk, to_write - first_chunk);
        }

        tail_.store(tail + to_write, std::memory_order_release);
        return to_write;
    }

    /**
     * 读取数据（仅消费者线程调用）。
     * 返回实际读取的字节数。
     */
    size_t read(uint8_t* dst, size_t count) {
        if (!dst || count == 0) return 0;

        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        const size_t available = tail - head;
        const size_t to_read = std::min(count, available);

        if (to_read == 0) return 0;

        const size_t mask = Capacity - 1;
        const size_t idx = head & mask;
        const size_t first_chunk = std::min(to_read, Capacity - idx);

        std::memcpy(dst, buffer_ + idx, first_chunk);
        if (to_read > first_chunk) {
            std::memcpy(dst + first_chunk, buffer_, to_read - first_chunk);
        }

        head_.store(head + to_read, std::memory_order_release);
        return to_read;
    }

    /** 当前可读字节数 */
    size_t available_read() const {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        return tail - head;
    }

    /** 当前可写剩余空间 */
    size_t available_write() const {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);
        return Capacity - (tail - head);
    }

    /** 重置缓冲区 */
    void clear() {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_release);
    }

private:
    uint8_t buffer_[Capacity];
    alignas(64) std::atomic<size_t> head_;
    alignas(64) std::atomic<size_t> tail_;
};

} // namespace glass
