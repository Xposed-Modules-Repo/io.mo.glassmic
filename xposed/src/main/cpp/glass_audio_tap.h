#pragma once

#include <cstdint>

namespace glass {

/**
 * 调试用"回放抓取"：把 FILE 决策下最终交给目标 App 的 PCM 原样（转 PCM16）写出，
 * 用来区分"GlassMic 交付的音频本身有断"还是"目标 App 后处理/网络吞音"。
 *
 * fd 由 Kotlin 侧从 PcmStreamProvider 的 /tap 打开（socketpair 写端），native 接管所有权。
 * 传 -1 关闭。音频线程只做 try_lock + 内存复制，send 在独立线程完成。
 *
 * 记录格式（小端）：56 字节头 + frames*channels 个 int16。
 *   u32 magic 'GMTP' | u16 version | u16 header_bytes | u32 seq | u16 stream_slot | u16 path
 *   u32 sample_rate | u16 channels | u16 src_format | u32 frames | u32 missing_frames
 *   i64 realtime_ms | i64 monotonic_ns | u32 payload_bytes | u32 reserved
 * seq 对每次回调都递增（含抓取侧丢弃的记录），seq 跳号 = 抓取丢失，不是交付断音。
 */
void set_tap_fd(int fd);

/** 当前是否持有可用的抓取 fd；写端出错（GlassMic 侧关闭）后变为 false。 */
bool tap_active();

void tap_capture(const void* buffer, int32_t aaudio_format, int32_t channels,
                 int32_t sample_rate, int32_t frames, int32_t path,
                 const void* stream_id, int32_t missing_frames);

} // namespace glass
