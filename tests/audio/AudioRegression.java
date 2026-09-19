import io.mo.glassmic.audio.BandSettings;
import io.mo.glassmic.audio.PcmOutputLimiter;
import io.mo.glassmic.audio.StreamingBandFilter;
import io.mo.glassmic.xposed.PcmReadMetrics;
import java.util.Random;

/** Runs the production DSP and metering classes on the JVM, without an Android device. */
public final class AudioRegression {
    private static final BandSettings NARROW = new BandSettings(true, 300, 3400);
    private static final BandSettings OFF = new BandSettings(false, 100, 8000);

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static double gain(double frequency) {
        StreamingBandFilter filter = new StreamingBandFilter();
        double inputPower = 0, outputPower = 0;
        for (int i = 0; i < 48000; i++) {
            if (i % 960 == 0) filter.beginFrame(NARROW, false);
            float input = (float)(10000 * Math.sin(2 * Math.PI * frequency * i / 48000));
            float output = filter.process(input);
            if (i >= 24000) {
                inputPower += input * (double)input;
                outputPower += output * (double)output;
            }
        }
        return Math.sqrt(outputPower / inputPower);
    }

    private static void frequencyResponse() {
        double low = gain(60), mid = gain(1000), high = gain(14000);
        check(low < 0.05, "Low frequency attenuation: " + low);
        check(mid > 0.98 && mid < 1.01, "Passband gain: " + mid);
        check(high < 0.04, "High frequency attenuation: " + high);
        System.out.printf("Band response: 60 Hz %.4f, 1 kHz %.4f, 14 kHz %.4f%n", low, mid, high);
    }

    private static void bypassAndContinuity() {
        StreamingBandFilter bypass = new StreamingBandFilter();
        StreamingBandFilter whole = new StreamingBandFilter();
        StreamingBandFilter chunks = new StreamingBandFilter();
        whole.beginFrame(NARROW, false);
        Random random = new Random(7);
        for (int i = 0; i < 48000; i++) {
            float input = random.nextInt(65536) - 32768;
            check(bypass.process(input) == input, "Disabled filter changed PCM");
            if (i % 137 == 0) chunks.beginFrame(NARROW, false);
            check(whole.process(input) == chunks.process(input), "Chunk boundary changed filter state");
        }
        chunks.beginFrame(NARROW, true);
        for (int i = 0; i < 2000; i++) check(chunks.process(0) == 0, "Reset leaked old source audio");
    }

    private static void transitionsAndExtremes() {
        StreamingBandFilter filter = new StreamingBandFilter();
        filter.beginFrame(NARROW, false);
        for (int i = 0; i < 10000; i++) filter.process(10000);
        filter.beginFrame(OFF, false);
        float last = 0;
        for (int i = 0; i < 1200; i++) {
            float value = filter.process(10000);
            check(Math.abs(value - last) < 11, "Bypass transition clicked");
            last = value;
        }
        check(last == 10000, "Transition did not reach exact bypass");
        BandSettings[] bands = {
            new BandSettings(true, 20, 21), new BandSettings(true, 19999, 20000),
            new BandSettings(true, 20, 20000), OFF, NARROW
        };
        for (int i = 0; i < 200000; i++) {
            if (i % 80 == 0) filter.beginFrame(bands[(i / 80) % bands.length], false);
            float value = filter.process((i & 1) == 0 ? 32767 : -32768);
            check(Float.isFinite(value) && Math.abs(value) < 100000, "Unstable rapid parameter changes");
        }
        BandSettings migrated = BandSettings.Companion.normalized(false, 0, 0);
        check(!migrated.getEnabled() && migrated.getLowHz() == 100 && migrated.getHighHz() == 8000,
            "Old configuration defaults changed");
        BandSettings invalid = BandSettings.Companion.normalized(true, Integer.MAX_VALUE, -100);
        check(invalid.getLowHz() < invalid.getHighHz() && invalid.getHighHz() <= 20000,
            "Invalid stored configuration was not normalized");
    }

    private static void limiter() {
        check(PcmOutputLimiter.INSTANCE.apply(12345, true) == 12345, "Limiter changed quiet samples");
        check(PcmOutputLimiter.INSTANCE.apply(40000, false) == 32767, "Disabled limiter must still prevent overflow");
        int soft = PcmOutputLimiter.INSTANCE.apply(40000, true);
        check(soft > 28000 && soft < 32767, "Soft limiter did not compress peaks");
        check(PcmOutputLimiter.INSTANCE.apply(-40000, true) == -soft, "Limiter is not symmetric");
        int previous = 0;
        for (int i = 0; i < 1000000; i += 101) {
            int sample = PcmOutputLimiter.INSTANCE.apply(i, true);
            check(sample >= previous && sample <= 32767, "Limiter is non-monotonic or wraps");
            previous = sample;
        }
        check(PcmOutputLimiter.INSTANCE.apply(Float.NaN, true) == 0, "Nonfinite sample escaped");
    }

    private static void metering() {
        PcmReadMetrics meter = new PcmReadMetrics();
        short[] values = {1000, -2000, -32768, 32767};
        byte[] bytes = new byte[values.length * 2];
        double squares = 0;
        for (int i = 0; i < values.length; i++) {
            bytes[i * 2] = (byte)values[i];
            bytes[i * 2 + 1] = (byte)(values[i] >> 8);
            squares += values[i] * (double)values[i];
        }
        meter.samples(bytes, 0, 1); // pipe reads can split a PCM16 sample
        meter.samples(bytes, 1, 7);
        meter.read(12, 8, false);
        PcmReadMetrics.Snapshot snapshot = meter.drain();
        check(snapshot.getSource() == 8 && snapshot.getRequested() == 12 && snapshot.getZeroFill() == 4,
            "Source bytes and padded bytes were conflated");
        check(snapshot.getShortReads() == 1 && snapshot.getErrors() == 0, "Short read counters");
        check(snapshot.getPeak() == 32768 && Math.abs(snapshot.getRms() - Math.sqrt(squares / 4)) < 1e-8,
            "PCM16 level or odd-byte boundary decoding failed");
        check(meter.drain() == null, "Draining counted the same samples twice");
        meter.read(8, 2, true);
        snapshot = meter.drain();
        check(snapshot.getErrors() == 1 && snapshot.getZeroFill() == 0 && snapshot.getShortReads() == 0,
            "Fallback to real mic was counted as zero padding");
    }

    public static void main(String[] args) {
        frequencyResponse();
        bypassAndContinuity();
        transitionsAndExtremes();
        limiter();
        metering();
        System.out.println("PASS: response, bypass, chunk continuity, resets, transitions, limits, limiter and pipe metrics");
    }
}
