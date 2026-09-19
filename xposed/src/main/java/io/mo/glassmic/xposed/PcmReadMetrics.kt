package io.mo.glassmic.xposed

import kotlin.math.abs
import kotlin.math.sqrt

/** Counts pipe PCM16 bytes, before any PCM_FLOAT expansion. No audio is retained. */
internal class PcmReadMetrics {
    data class Snapshot(
        val reads: Long, val requested: Long, val source: Long, val zeroFill: Long,
        val shortReads: Long, val errors: Long, val rms: Double, val peak: Int
    )
    private var reads = 0L
    private var requested = 0L
    private var source = 0L
    private var zeroFill = 0L
    private var shortReads = 0L
    private var errors = 0L
    private var squares = 0.0
    private var samples = 0L
    private var peak = 0
    private var pendingLow = -1

    @Synchronized fun samples(bytes: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            if (pendingLow < 0) pendingLow = bytes[i].toInt() and 0xff
            else {
                val sample = ((bytes[i].toInt() shl 8) or pendingLow).toShort().toInt()
                squares += sample.toDouble() * sample
                samples++
                peak = maxOf(peak, abs(sample))
                pendingLow = -1
            }
        }
    }

    @Synchronized fun read(wanted: Int, actual: Int, failed: Boolean): Long {
        reads++
        requested += wanted
        source += actual
        if (failed) {
            errors++ // Hook falls back to the real read; this is not zero padding.
            pendingLow = -1
        } else if (actual < wanted) {
            shortReads++
            zeroFill += wanted - actual
            pendingLow = -1
        }
        return reads
    }

    @Synchronized fun drain(): Snapshot? {
        if (reads == 0L) return null
        val result = Snapshot(reads, requested, source, zeroFill, shortReads, errors,
            if (samples > 0) sqrt(squares / samples) else 0.0, peak)
        reads = 0; requested = 0; source = 0; zeroFill = 0; shortReads = 0; errors = 0
        squares = 0.0; samples = 0; peak = 0
        return result
    }
}
