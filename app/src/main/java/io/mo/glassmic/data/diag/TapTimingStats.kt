package io.mo.glassmic.data.diag

import kotlin.math.abs

/** Export-time statistics only. PCM continuity does not imply timely delivery. */
class TapTimingStats(private val sampleRate: Int) {
    data class Interval(val actualNs: Long, val expectedNs: Long) {
        val errorNs: Long get() = actualNs - expectedNs
    }

    var intervals = 0L
        private set
    var excludedIntervals = 0L
        private set
    var minIntervalNs = Long.MAX_VALUE
        private set
    var maxIntervalNs = 0L
        private set
    var maxLateNs = 0L
        private set
    var maxEarlyNs = 0L
        private set
    var deviationOver5Ms = 0L
        private set
    var deviationOver10Ms = 0L
        private set
    var deviationOver20Ms = 0L
        private set
    var deviationOver50Ms = 0L
        private set
    private var intervalSumNs = 0L
    private var absoluteErrorSumNs = 0L
    private var lastNs = 0L
    private var lastFrames = 0
    private var lastLostRecords = 0L

    val meanIntervalNs: Double get() = if (intervals == 0L) 0.0 else intervalSumNs.toDouble() / intervals
    val meanAbsoluteErrorNs: Double get() = if (intervals == 0L) 0.0 else absoluteErrorSumNs.toDouble() / intervals

    init { require(sampleRate > 0) }

    /**
     * Expected spacing comes from the PREVIOUS block, including variable-sized callbacks.
     * A capture loss anywhere between this stream's records makes the interval ambiguous:
     * exclude it rather than reporting missing tap records as late audio delivery.
     */
    fun observe(monotonicNs: Long, frames: Int, totalLostRecords: Long): Interval? {
        require(frames > 0)
        val interval = if (lastFrames > 0) {
            if (monotonicNs >= lastNs && totalLostRecords == lastLostRecords) {
                Interval(monotonicNs - lastNs, lastFrames * 1_000_000_000L / sampleRate)
            } else {
                excludedIntervals++
                null
            }
        } else null
        lastNs = monotonicNs
        lastFrames = frames
        lastLostRecords = totalLostRecords

        if (interval != null) {
            intervals++
            minIntervalNs = minOf(minIntervalNs, interval.actualNs)
            maxIntervalNs = maxOf(maxIntervalNs, interval.actualNs)
            intervalSumNs += interval.actualNs
            val error = interval.errorNs
            maxLateNs = maxOf(maxLateNs, error)
            maxEarlyNs = maxOf(maxEarlyNs, -error)
            val deviation = abs(error)
            absoluteErrorSumNs += deviation
            if (deviation > 5_000_000L) deviationOver5Ms++
            if (deviation > 10_000_000L) deviationOver10Ms++
            if (deviation > 20_000_000L) deviationOver20Ms++
            if (deviation > 50_000_000L) deviationOver50Ms++
        }
        return interval
    }
}
