import io.mo.glassmic.data.diag.TapTimingStats;

/** Exercises the production export timing calculator without Android or audio devices. */
public final class TapTimingRegression {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        TapTimingStats regular = new TapTimingStats(16000);
        check(regular.getIntervals() == 0, "empty capture");
        check(regular.observe(0, 192, 0) == null, "first record has no interval");
        check(regular.observe(12_000_000, 320, 0).getErrorNs() == 0,
            "12 ms interval uses previous 192 frames, not current 320 frames");
        check(regular.observe(32_000_000, 192, 0).getErrorNs() == 0, "variable block sizes");
        check(regular.getIntervals() == 2 && regular.getMaxLateNs() == 0, "regular summary");
        check(regular.getMeanIntervalNs() == 16_000_000, "mean interval");

        // Continuous PCM and average rate, but alternating late/burst delivery.
        // The old >50 ms stall detector would miss both intervals.
        TapTimingStats burst = new TapTimingStats(16000);
        burst.observe(1_000_000_000, 320, 0);
        burst.observe(1_040_000_000, 320, 0);
        burst.observe(1_040_000_000, 320, 0);
        check(burst.getMeanIntervalNs() == 20_000_000, "average rate hides bursts");
        check(burst.getMaxLateNs() == 20_000_000 && burst.getMaxEarlyNs() == 20_000_000,
            "both late and burst delivery are visible");
        check(burst.getDeviationOver10Ms() == 2 && burst.getDeviationOver50Ms() == 0,
            "sub-50 ms jitter is retained");
        check(burst.getMeanAbsoluteErrorNs() == 20_000_000, "absolute error does not cancel out");

        TapTimingStats loss = new TapTimingStats(16000);
        loss.observe(0, 320, 0);
        check(loss.observe(100_000_000, 320, 4) == null, "tap loss is not a delivery stall");
        check(loss.observe(120_000_000, 320, 4).getErrorNs() == 0, "resume after lost capture");
        check(loss.getExcludedIntervals() == 1 && loss.getMaxLateNs() == 0, "loss excluded");
        check(loss.observe(110_000_000, 320, 4) == null, "invalid clock interval excluded");
        check(loss.observe(130_000_000, 320, 4).getErrorNs() == 0, "recover after clock discontinuity");

        TapTimingStats precision = new TapTimingStats(44100);
        precision.observe(0, 1, 0);
        check(precision.observe(22_675, 1, 0).getExpectedNs() == 22_675,
            "sub-millisecond frame durations are not rounded to zero");

        // Independent instances represent interleaved streams/format segments.
        TapTimingStats other = new TapTimingStats(48000);
        other.observe(500_000_000, 480, 4);
        check(other.observe(510_000_000, 480, 4).getErrorNs() == 0, "independent stream clock");
        check(regular.getIntervals() == 2, "other stream cannot change prior summary");
        System.out.println("PASS: variable frames, timing bursts, capture loss, precision and independent streams");
    }
}
