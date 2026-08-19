package io.mo.glassmic.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 高保真流式 PCM16（小端、交错）重采样 + 声道混合器。
 *
 * 特性：
 * 1. 自动抗混叠低通滤波（Anti-Aliasing Windowed-Sinc FIR Filter）：
 *    在下采样（如 48k -> 16k）时滤除高于目标奈奎斯特极限的高频成分，彻底杜绝微信等
 *    语音 App 中的金属破音、高频嘶嘶杂音和齿音混叠。
 * 2. 亚样本线性平滑插值（Sub-sample Linear Interpolation）：
 *    消除非整数倍采样转换中的阶梯时域伪影。
 * 3. 跨分块流式状态连续性（Streaming State Preservation）：
 *    保留滤波器历史样本和时钟小数偏移，保证块间波形严格连续、零爆音。
 * 4. 软限幅器（Soft Knee Limiter）：
 *    防止滤波引起的峰值过冲导致的方波削波破音。
 */
internal class Pcm16Converter(
    private val sourceSampleRate: Int,
    private val sourceChannels: Int,
    private val targetSampleRate: Int,
    private val targetChannels: Int
) {
    private val isPassThrough = sourceSampleRate == targetSampleRate && sourceChannels == targetChannels

    // 重采样比例：每个目标样本对应多少个源样本
    private val ratio = sourceSampleRate.toDouble() / targetSampleRate.toDouble()

    // FIR 滤波器参数
    private val filterTaps: Int
    private val filterHalf: Int
    private val firCoeffs: FloatArray
    private val needsFilter: Boolean

    // 历史样本环形/滑动缓冲（单通道浮点，用于滤波与插值）
    // 每个源通道独立维护历史缓冲
    private var sourceHistory: Array<FloatArray>
    private var historyCount: Int = 0

    // 源时间小数游标（相对于 history 中有效源样本的偏移）
    private var sourceCursor: Double = 0.0

    // 未消费完的待转换字节（用于处理输入字节不足一帧的情况）
    private var pendingBytes = ByteArray(0)

    init {
        val safeSourceRate = sourceSampleRate.coerceAtLeast(8000)
        val safeTargetRate = targetSampleRate.coerceAtLeast(8000)

        // 当需要降采样或者源目标采样率不一致时设计抗混叠低通滤波器
        if (safeTargetRate < safeSourceRate) {
            needsFilter = true
            // 截止频率设为目标奈奎斯特频率的 0.45 倍（例如 16kHz 时为 7.2kHz）
            val cutoff = 0.45 * safeTargetRate
            val normalizedCutoff = (cutoff / safeSourceRate).coerceIn(0.01, 0.49)
            // 27 阶对称 FIR 滤波器，兼顾极高声音保真度与超低 CPU 消耗
            filterTaps = 27
            filterHalf = filterTaps / 2
            firCoeffs = designLowPassFir(filterTaps, normalizedCutoff)
        } else if (safeTargetRate > safeSourceRate) {
            // 升采样时（如 44.1k -> 48k）进行抗镜像平滑滤波
            needsFilter = true
            val cutoff = 0.45 * safeSourceRate
            val normalizedCutoff = (cutoff / safeSourceRate).coerceIn(0.01, 0.49)
            filterTaps = 21
            filterHalf = filterTaps / 2
            firCoeffs = designLowPassFir(filterTaps, normalizedCutoff)
        } else {
            needsFilter = false
            filterTaps = 1
            filterHalf = 0
            firCoeffs = floatArrayOf(1.0f)
        }

        sourceHistory = Array(sourceChannels.coerceAtLeast(1)) { FloatArray(0) }
    }

    /**
     * 将输入的 PCM16 小端字节数据转换到目标规格。
     */
    fun convert(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(0)
        if (isPassThrough) return bytes

        val totalBytes = if (pendingBytes.isNotEmpty()) {
            val combined = ByteArray(pendingBytes.size + bytes.size)
            System.arraycopy(pendingBytes, 0, combined, 0, pendingBytes.size)
            System.arraycopy(bytes, 0, combined, pendingBytes.size, bytes.size)
            pendingBytes = ByteArray(0)
            combined
        } else {
            bytes
        }

        val bytesPerSourceFrame = sourceChannels * 2
        val availableFrames = totalBytes.size / bytesPerSourceFrame
        if (availableFrames <= 0) {
            pendingBytes = totalBytes
            return ByteArray(0)
        }

        val consumedBytes = availableFrames * bytesPerSourceFrame
        val remainderBytes = totalBytes.size - consumedBytes
        if (remainderBytes > 0) {
            pendingBytes = ByteArray(remainderBytes)
            System.arraycopy(totalBytes, consumedBytes, pendingBytes, 0, remainderBytes)
        }

        // 解码输入为每个声道的浮点数组
        val newSourceSamples = Array(sourceChannels) { FloatArray(availableFrames) }
        var byteOffset = 0
        for (f in 0 until availableFrames) {
            for (ch in 0 until sourceChannels) {
                val lo = totalBytes[byteOffset].toInt() and 0xFF
                val hi = totalBytes[byteOffset + 1].toInt()
                val sampleShort = ((hi shl 8) or lo).toShort()
                newSourceSamples[ch][f] = sampleShort.toFloat()
                byteOffset += 2
            }
        }

        // 将新样本追加到历史缓冲中
        val oldHistoryCount = historyCount
        val totalSourceFrames = oldHistoryCount + availableFrames
        val combinedHistory = Array(sourceChannels) { ch ->
            val buf = FloatArray(totalSourceFrames)
            if (oldHistoryCount > 0) {
                System.arraycopy(sourceHistory[ch], 0, buf, 0, oldHistoryCount)
            }
            System.arraycopy(newSourceSamples[ch], 0, buf, oldHistoryCount, availableFrames)
            buf
        }

        // 估计输出帧数并预分配
        val estimatedOutFrames = ((totalSourceFrames - filterTaps - sourceCursor) / ratio).toInt().coerceAtLeast(0) + 16
        val outChannelsData = Array(targetChannels) { FloatArray(estimatedOutFrames) }
        var outFrameCount = 0

        // 只要游标加上滤波半窗仍在有效历史范围内，就可以持续产生目标帧
        while (true) {
            val centerIndex = sourceCursor.toInt()
            // 判断是否已超出当前可用源样本的滤波右边界
            if (centerIndex + filterHalf + 1 >= totalSourceFrames) {
                break
            }

            // 确保输出数组容量充足
            if (outFrameCount >= outChannelsData[0].size) {
                val newCap = outChannelsData[0].size * 2 + 32
                for (ch in 0 until targetChannels) {
                    val expanded = FloatArray(newCap)
                    System.arraycopy(outChannelsData[ch], 0, expanded, 0, outFrameCount)
                    outChannelsData[ch] = expanded
                }
            }

            // 逐个源通道计算滤波与亚样本插值后的值
            val filteredSource = FloatArray(sourceChannels)
            val frac = (sourceCursor - centerIndex).toFloat()

            for (ch in 0 until sourceChannels) {
                val chHistory = combinedHistory[ch]
                if (needsFilter) {
                    // 对 centerIndex 和 centerIndex + 1 分别做 FIR 滤波，再按 frac 进行线性插值
                    var sum0 = 0.0f
                    var sum1 = 0.0f
                    val base0 = centerIndex - filterHalf
                    val base1 = base0 + 1

                    for (k in 0 until filterTaps) {
                        val coeff = firCoeffs[k]
                        val idx0 = (base0 + k).coerceIn(0, totalSourceFrames - 1)
                        val idx1 = (base1 + k).coerceIn(0, totalSourceFrames - 1)
                        sum0 += chHistory[idx0] * coeff
                        sum1 += chHistory[idx1] * coeff
                    }
                    filteredSource[ch] = sum0 + (sum1 - sum0) * frac
                } else {
                    // 无滤波时直接线性插值
                    val s0 = chHistory[centerIndex.coerceIn(0, totalSourceFrames - 1)]
                    val s1 = chHistory[(centerIndex + 1).coerceIn(0, totalSourceFrames - 1)]
                    filteredSource[ch] = s0 + (s1 - s0) * frac
                }
            }

            // 声道映射
            for (dstCh in 0 until targetChannels) {
                val sampleValue = when {
                    sourceChannels == 1 -> filteredSource[0]
                    targetChannels == 1 -> (filteredSource[0] + filteredSource[1]) * 0.5f
                    dstCh < sourceChannels -> filteredSource[dstCh]
                    else -> 0.0f
                }
                outChannelsData[dstCh][outFrameCount] = sampleValue
            }

            outFrameCount++
            sourceCursor += ratio
        }

        // 保留尾部未消费完的源样本作为下一次的 history
        val consumedSourceFrames = sourceCursor.toInt() - filterHalf
        val retainStart = consumedSourceFrames.coerceAtLeast(0).coerceAtMost(totalSourceFrames)
        val retainedCount = totalSourceFrames - retainStart

        sourceHistory = Array(sourceChannels) { ch ->
            val retained = FloatArray(retainedCount)
            if (retainedCount > 0) {
                System.arraycopy(combinedHistory[ch], retainStart, retained, 0, retainedCount)
            }
            retained
        }
        historyCount = retainedCount
        sourceCursor -= retainStart

        if (outFrameCount <= 0) return ByteArray(0)

        // 软限幅 + 打包为 PCM16 小端字节数组
        val outBytes = ByteArray(outFrameCount * targetChannels * 2)
        var outByteIndex = 0
        for (f in 0 until outFrameCount) {
            for (ch in 0 until targetChannels) {
                val raw = outChannelsData[ch][f]
                val limited = softLimit(raw)
                val s = limited.toInt().toShort()
                outBytes[outByteIndex++] = (s.toInt() and 0xFF).toByte()
                outBytes[outByteIndex++] = ((s.toInt() ushr 8) and 0xFF).toByte()
            }
        }

        return outBytes
    }

    /**
     * 重置内部状态（用于 seek 或源切换）
     */
    fun reset() {
        historyCount = 0
        sourceCursor = 0.0
        pendingBytes = ByteArray(0)
        sourceHistory = Array(sourceChannels.coerceAtLeast(1)) { FloatArray(0) }
    }

    companion object {
        /**
         * 设计对称 Windowed-Sinc 低通 FIR 滤波器（Blackman-Harris 窗）
         * @param taps 抽头数（奇数）
         * @param normalizedCutoff 归一化截止频率（0 < cutoff < 0.5）
         */
        fun designLowPassFir(taps: Int, normalizedCutoff: Double): FloatArray {
            val coeffs = FloatArray(taps)
            val m = taps - 1
            val center = m / 2.0
            var sum = 0.0

            for (i in 0 until taps) {
                val n = i - center
                // Sinc 函数
                val sinc = if (n == 0.0) {
                    2.0 * PI * normalizedCutoff
                } else {
                    sin(2.0 * PI * normalizedCutoff * n) / n
                }
                // Blackman-Harris 窗，提供 >60dB 的旁瓣衰减，抗混叠能力极强
                val a0 = 0.35875
                val a1 = 0.48829
                val a2 = 0.14128
                val a3 = 0.01168
                val w = a0 - a1 * cos(2.0 * PI * i / m) + a2 * cos(4.0 * PI * i / m) - a3 * cos(6.0 * PI * i / m)

                val val_i = sinc * w
                coeffs[i] = val_i.toFloat()
                sum += val_i
            }

            // 归一化直流增益为 1.0 (0 dB)
            if (sum > 1e-6) {
                val invSum = (1.0 / sum).toFloat()
                for (i in 0 until taps) {
                    coeffs[i] *= invSum
                }
            }

            return coeffs
        }

        /**
         * 平滑软限幅器（Soft Knee Limiter）：
         * 在 |x| <= 30000 范围保持 100% 线性；
         * 在 |x| > 30000 范围使用双曲正切软过渡到 32767，消除峰值方波削波失真。
         */
        fun softLimit(sample: Float): Float {
            val threshold = 30000.0f
            val maxVal = 32767.0f
            val absVal = if (sample < 0f) -sample else sample

            if (absVal <= threshold) {
                return sample
            }

            val over = absVal - threshold
            val range = maxVal - threshold
            // 快速软拐点近似：tanh(over / range)
            val normalizedOver = over / range
            val softOver = (normalizedOver / (1.0f + normalizedOver)) * range
            val limited = threshold + softOver

            return if (sample < 0f) -limited else limited
        }
    }
}
