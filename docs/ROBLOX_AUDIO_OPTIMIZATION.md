# Roblox 及重载在线游戏虚拟麦克风音频卡顿与中断问题 —— 深度代码优化规划方案

## 1. 现状与根因技术定位 (Root Cause Analysis)

通过对音频生成端 ([`SharedPcmPublisher`](file:///E:/Users/limo2/Desktop/project/Github/io.mo.glassmic/app/src/main/java/io/mo/glassmic/audio/SharedPcmPublisher.kt))、跨进程 IPC 管道 ([`PcmStreamProvider`](file:///E:/Users/limo2/Desktop/project/Github/io.mo.glassmic/app/src/main/java/io/mo/glassmic/provider/PcmStreamProvider.kt)) 以及目标进程原生注入端 ([`glass_aaudio.cpp`](file:///E:/Users/limo2/Desktop/project/Github/io.mo.glassmic/xposed/src/main/cpp/glass_aaudio.cpp), [`glass_opensl.cpp`](file:///E:/Users/limo2/Desktop/project/Github/io.mo.glassmic/xposed/src/main/cpp/glass_opensl.cpp), [`glass_audiorecord.cpp`](file:///E:/Users/limo2/Desktop/project/Github/io.mo.glassmic/xposed/src/main/cpp/glass_audiorecord.cpp)) 的源码排查，导致在《Roblox》等重载在线/直播游戏中音频断断续续和中断的技术根因如下：

### 关键瓶颈点：
1. **实时音频回调线程中执行阻塞式 IPC I/O**：
   - `glass_aaudio.cpp` 和 `glass_opensl.cpp` 在游戏的 Real-Time 音频线程回调 (`my_AAudioStream_read` / `my_data_callback` / `my_bq_callback`) 中直接调用 `::read(fd, ...)`。
   - 当游戏高负载导致 GlassMic 主进程产生几毫秒的调度延迟时，管道立即干涸，硬实时线程阻塞超时，触发音频欠载（Underrun / XRun）。
2. **欠载处理机制粗糙导致声音畸变与回退**：
   - 当 `read_full` 读取的字节少于目标帧需求时，`convert_and_write` 使用 `map_src_frame` 将不完整数据线性拉伸覆盖整帧，导致音频音调和语速发生抖动变调；
   - 当读取返回 `<= 0` 时，直接返回 `FillResult::PASS` 回退到真实麦克风，造成虚拟声音断开、真麦杂音漏入。
3. **音频广播线程优先级不足与 4096 字节大块突发推流**：
   - `SharedPcmPublisher` 运行在默认优先级的 `Dispatchers.IO` 协程上，缺乏 `THREAD_PRIORITY_URGENT_AUDIO` 调度保障。
   - 每次推流 4096 字节（~42.6ms），呈突发式（Burst）写入，而游戏端是以 5~10ms 小块消费，缓冲余量极脆弱。
4. **游戏端原生采样率/声道固定盲写 (48kHz/Mono) 与粗糙重采样**：
   - `NativeAAudioHook` 向 Provider 索取管道时固定请求 `sr=48000&ch=1`，若游戏内实际使用 16kHz/24kHz/44.1kHz 或立体声，C++ 层的最近邻/线性降采样缺乏抗混叠滤波，声音发干发刺，易触发游戏 VoIP 引擎的降噪切除。

---

## 2. 改造目标 (Goals)

1. **零阻塞硬实时回调**：Native 音频回调从无锁环形缓冲区（Lock-Free RingBuffer）零等待读取，杜绝任何 IPC 阻塞导致的 XRun。
2. **抗抖动自适应 Jitter Buffer**：在目标进程 Native 端维护 100ms~200ms 的平滑缓冲区，彻底吸收主进程由于 GC 或 CPU 调度引起的毫秒级抖动。
3. **丢包/欠载平滑补偿 (PLC - Packet Loss Concealment)**：管道缺数据时，优雅过渡到舒适噪声或淡出，绝不发生音频拉伸变调或突然泄露真实麦克风。
4. **主进程音频推流高优先级化**：推流线程提升为 `THREAD_PRIORITY_URGENT_AUDIO`，配合微小切片匀速写入与 partial wakelock 保活。
5. **动态格式协商与高质量重采样**：支持目标进程 Native 端根据实际 Stream 参数按需开启格式协商。

---

## 3. 详细分阶段改造规划 (Phased Implementation Plan)

### 阶段一：目标进程 Native 端重构 —— 引入后台读取线程与无锁环形缓冲 (Lock-Free RingBuffer)

> **目标**：将硬实时 Audio Callback 与 Linux Pipe IPC 完全解耦。

#### 1.1 实现 C++ 无锁 SPSC (Single Producer Single Consumer) 环形缓冲区
* **新增文件**：`xposed/src/main/cpp/glass_ring_buffer.h`
* **设计方案**：
  - 基于 C++11 `std::atomic<size_t>` 实现无锁环形队列，容量预设为 192KB（约 1 秒 48kHz 16-bit PCM 数据）。
  - 生产者：独立的 Native 管道拉取线程 (`PcmReaderWorker`)。
  - 消费者：游戏的 Real-Time Audio Callback。
  - 保证读取操作为 $O(1)$ 且零内存分配、零系统调用阻塞。

#### 1.2 重构 `glass_aaudio.cpp` 与 `glass_opensl.cpp` 的消费逻辑
* **修改逻辑**：
  - 当 `set_pcm_fd` 传入新 fd 时，启动或重置 `PcmReaderWorker` 线程，持续在后台以 `read_full` 将数据从 Pipe 搬运至 RingBuffer 中。
  - `fill_pcm_impl` 不再直接调用 `read(fd, ...)`，而是从 RingBuffer 中批量弹出所需的 PCM 帧。
  - **欠载平滑处理**：
    - 若 RingBuffer 中数据充足 $\ge$ 请求量：正常转换写入并返回 `FillResult::FILLED`；
    - 若 RingBuffer 发生短暂欠载（数据不足）：优先将已有数据写出，不足部分**填充舒适噪声（Comfort Noise）或平滑淡出（Fade Out）**，绝不调用 `map_src_frame` 进行时间拉伸，且不返回 `PASS`（防止漏真麦）。

---

### 阶段二：主进程音频广播管线优化 —— 提高线程优先级与切片匀速推流

> **目标**：提高 GlassMic 主进程推流的时钟精度与系统调度抗压能力。

#### 2.1 引入专有音频 HandlerThread 与 Native 音频线程优先级
* **修改文件**：[`SharedPcmPublisher.kt`](file:///E:/Users/limo2/Desktop/project/Github/io.mo.glassmic/app/src/main/java/io/mo/glassmic/audio/SharedPcmPublisher.kt)
* **设计方案**：
  - 将推流循环由默认的 `Dispatchers.IO` 协程迁移到独立的 `HandlerThread("GlassAudioPublisher")` 或专用线程。
  - 启动时调用 `android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)`。
  - 将推流分片从 4096 字节（~42.6ms）减小到 960 字节（10ms @ 48kHz mono）或 1920 字节（20ms），缩短推流周期的突发性，实现更加平滑匀速的水流式推流。

#### 2.2 广播协程与消费者管道写入优化
* **设计方案**：
  - 将消费者管道写入协程的 Channel 队列由 `capacity = 3` 提升至 `capacity = 16`，防止在偶发 I/O 阻塞时丢弃数据。
  - 采用基于纳秒高精度时钟 (`System.nanoTime()`) 的时间轴对齐算法，消除协程 `delay()` 毫秒级的累计漂移误差。

---

### 阶段三：服务保活与电源管理增强

> **目标**：防止 Android 系统和各 OEM 游戏助手在游戏前台高负载时挂起 GlassMic。

#### 3.1 增加播放中的 CPU Partial WakeLock 与低延迟音频标记
* **修改文件**：[`GlassForegroundService.kt`](file:///E:/Users/limo2/Desktop/project/Github/io.mo.glassmic/app/src/main/java/io/mo/glassmic/service/GlassForegroundService.kt)
* **设计方案**：
  - 当有活跃消费者且处于 `FILE` 播放状态时，持有 `PowerManager.PARTIAL_WAKE_LOCK`，离开播放时释放。
  - 在通知中增加正在提供虚拟音频的动态状态指示，确保系统进程调度器将其作为活跃前台音视频服务对待。

---

### 阶段四：音频处理与重采样优化 (Resampling & Audio Processing)

> **目标**：提高音频与游戏 VoIP 系统的兼容度，避免触发游戏 VAD 门限斩波与 AEC 误判。

#### 4.1 Native 端高质量抗混叠重采样
* **修改文件**：[`glass_aaudio.cpp`](file:///E:/Users/limo2/Desktop/project/Github/io.mo.glassmic/xposed/src/main/cpp/glass_aaudio.cpp)
* **设计方案**：
  - 优化采样率转换算法：对于常见的 48kHz -> 16kHz/24kHz 下采样，引入轻量带通滤波（或 3 阶/5 阶多相滤波），消除高频混叠引起的刺耳噪声和数字失真。
  - 优化音量增益与动态范围控制（DRC/Limiter），防止音频信号削顶失真触发游戏端的语音门限切断。

---

## 4. 架构优化前后对比

| 模块 / 特性 | 当前实现 (Current) | 优化后规划 (Proposed) | 带来的收益 |
| :--- | :--- | :--- | :--- |
| **Native 音频回调读取方式** | 在硬实时 Audio Callback 中直接 `read(fd)` 阻塞读取 | 独立线程从 Pipe 读入 **Lock-Free RingBuffer**，Audio Callback 零等待读取 | **彻底消除游戏音频欠载（Underrun）与卡顿** |
| **管道数据欠载处理** | 缩放拉伸变调 (`map_src_frame`) / 回退真实麦克风 (`PASS`) | 保持正常音调，欠载部分平滑补齐舒适噪声 | **消除音调抽搐变调，防止真实麦克风声音泄露** |
| **主进程推流线程调度** | `Dispatchers.IO` 协程 + 42.6ms 大包突发 | 专有 `URGENT_AUDIO` 优先级线程 + 10~20ms 匀速切片 | **抗高 CPU 负载抢占，降低管道突发抖动** |
| **Pipe 写入通道容量** | `Channel(capacity = 3)` | `Channel(capacity = 16)` + 动态弹性缓冲 | **防止突发丢包** |
| **电源与保活机制** | 普通 Foreground Service | Foreground Service + 播放态 Partial WakeLock | **防止游戏模式激进冻结后台** |
| **采样率转换** | 简单线性坐标映射 (`map_src_frame`) | 抗混叠多相/带通滤波转换 | **提升音质，避免触发 Roblox 降噪滤波器** |

---

## 5. 实施与验证步骤 (Verification Strategy)

1. **无锁环形缓冲单元测试**：编写多线程压测工具，模拟高频 192 采样（4ms）读取与低频粗粒度写入，验证在写入延迟 50ms 场景下的抗抖动与平滑补齐表现。
2. **重载联机游戏实机验证**：
   - 在高画质《Roblox》多人在线语音场景下压测运行 30 分钟；
   - 监控 Native 端 `stats[0]`（读取次数）与 `stats[1]`（字节数），观察是否有 Underrun 或丢帧日志；
   - 对比录音回放，验证人声连贯性、语速稳定性及无漏真麦现象。
