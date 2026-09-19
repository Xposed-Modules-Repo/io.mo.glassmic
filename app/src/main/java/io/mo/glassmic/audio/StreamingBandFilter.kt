package io.mo.glassmic.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared by configuration, UI and DSP. Zero-valued old proto fields use the wide voice preset. */
internal data class BandSettings(val enabled: Boolean = false, val lowHz: Int = 100, val highHz: Int = 8000) {
    companion object {
        const val MIN_HZ = 20
        const val MAX_HZ = 20_000

        fun normalized(enabled: Boolean, lowHz: Int, highHz: Int): BandSettings {
            val low = (if (lowHz == 0) 100 else lowHz).coerceIn(MIN_HZ, MAX_HZ - 1)
            val high = (if (highHz == 0) 8000 else highHz).coerceIn(low + 1, MAX_HZ)
            return BandSettings(enabled, low, high)
        }
    }
}

/** 48 kHz mono, two Butterworth biquads (12 dB/oct per edge). Owned by the publisher loop. */
internal class StreamingBandFilter {
    private var active = Bank(BandSettings())
    private var next: Bank? = null
    private var fadePosition = 0

    fun beginFrame(settings: BandSettings, reset: Boolean = false) {
        if (reset) {
            active = Bank(BandSettings())
            next = null
            fadePosition = 0
        }
        // Finish the current fade before accepting another change; rapid updates coalesce per frame.
        if (next == null && settings != active.settings) {
            next = Bank(settings)
            fadePosition = 0
        }
    }

    fun process(input: Float): Float {
        val old = active.process(input)
        val target = next ?: return old
        val new = target.process(input)
        val mix = (++fadePosition).toFloat() / FADE_SAMPLES
        if (fadePosition >= FADE_SAMPLES) {
            active = target
            next = null
        }
        return old + (new - old) * mix
    }

    private class Bank(val settings: BandSettings) {
        private val highPass = Biquad(settings.lowHz, highPass = true)
        private val lowPass = Biquad(settings.highHz, highPass = false)
        fun process(input: Float): Float =
            if (settings.enabled) lowPass.process(highPass.process(input)) else input
    }

    private class Biquad(frequency: Int, highPass: Boolean) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var z1 = 0.0
        private var z2 = 0.0

        init {
            val omega = 2 * PI * frequency / SAMPLE_RATE
            val c = cos(omega)
            val alpha = sin(omega) / sqrt(2.0)
            val a0 = 1 + alpha
            b0 = (if (highPass) 1 + c else 1 - c) / (2 * a0)
            b1 = (if (highPass) -(1 + c) else 1 - c) / a0
            b2 = b0
            a1 = -2 * c / a0
            a2 = (1 - alpha) / a0
        }

        fun process(input: Float): Float {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output.toFloat()
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val FADE_SAMPLES = SAMPLE_RATE / 50 // 20 ms; no coefficient interpolation instability.
    }
}

/** Unity gain below the knee, smooth saturation above it; never wraps PCM16. */
internal object PcmOutputLimiter {
    fun apply(sample: Float, enabled: Boolean): Int {
        if (!sample.isFinite()) return 0
        val magnitude = kotlin.math.abs(sample)
        val limited = if (enabled && magnitude > 28_000f) {
            val excess = magnitude - 28_000f
            val compressed = 28_000f + 4_767f * excess / (4_767f + excess)
            if (sample < 0) -compressed else compressed
        } else sample
        return limited.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
}
