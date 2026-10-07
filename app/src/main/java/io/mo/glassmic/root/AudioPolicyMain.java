package io.mo.glassmic.root;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone app_process entry point. Uses only framework/Java classes, never the Application/Hilt.
 * The parent owns stdin: EOF or a missing heartbeat terminates this process and its Binder policy.
 * Arguments: comma-separated target app UIDs, FIFO path. All UIDs share one injection mix.
 * PCM FIFO protocol: big-endian int64 discontinuity epoch, int32 length, PCM16 LE mono 48 kHz.
 */
public final class AudioPolicyMain {
    private static volatile long lastHeartbeat = SystemClock.elapsedRealtime();
    private static volatile Context context;
    private static volatile Class<?> policyClass;
    private static volatile Object policy;
    private static volatile AudioTrack track;
    private static final AtomicBoolean exiting = new AtomicBoolean();

    public static void main(String[] args) {
        if (Process.myUid() != 0 || args.length != 2) {
            System.out.println("ERROR Root access and target UIDs are required");
            System.exit(1);
        }
        // Start BEFORE registration: a parent disappearing during startup must also be handled.
        new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if ("STOP".equals(line)) break;
                    if ("PING".equals(line)) lastHeartbeat = SystemClock.elapsedRealtime();
                }
            } catch (Exception ignored) { }
            exit(0);
        }, "glassmic-owner").start();
        new Thread(() -> {
            while (true) {
                SystemClock.sleep(1000);
                if (SystemClock.elapsedRealtime() - lastHeartbeat > 6000) exit(2);
            }
        }, "glassmic-lease").start();
        Runtime.getRuntime().addShutdownHook(new Thread(AudioPolicyMain::cleanup));
        try {
            int[] uids = parseUids(args[0]);
            Looper.prepareMainLooper();
            // Hidden framework APIs are used only in this explicitly root-launched process.
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getDeclaredMethod("getRuntime").invoke(null);
            vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[]{"L"});
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            register(uids);
            new Thread(() -> {
                try {
                    pump(args[1]);
                    exit(0);
                } catch (Throwable error) {
                    fail(error);
                }
            }, "glassmic-policy-pcm").start();
            System.out.println("READY");
            System.out.flush();
            Looper.loop();
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static int[] parseUids(String arg) {
        String[] parts = arg.split(",");
        int[] uids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            uids[i] = Integer.parseInt(parts[i].trim());
            if (uids[i] % 100000 < 10000) throw new IllegalArgumentException("System UIDs are not supported");
        }
        if (uids.length == 0) throw new IllegalArgumentException("No target UID");
        return uids;
    }

    private static void register(int[] uids) throws Exception {
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> builderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
        try {
            ruleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(ruleBuilder,
                    constant(ruleClass, "MIX_ROLE_INJECTOR"));
        } catch (NoSuchMethodException | NoSuchFieldException olderAndroid) {
            ruleBuilderClass.getMethod("setTargetMixType", int.class).invoke(ruleBuilder,
                    constant(mixClass, "MIX_TYPE_RECORDERS"));
        }
        // Criteria of the same type are OR-ed: the mix captures a recorder whose UID matches any target.
        Method addMixRule = ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
        int matchUid = constant(ruleClass, "RULE_MATCH_UID");
        for (int uid : uids) addMixRule.invoke(ruleBuilder, matchUid, uid);
        Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);
        Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
        AudioFormat format = new AudioFormat.Builder().setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder,
                constant(mixClass, "ROUTE_FLAG_LOOP_BACK"));
        Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);
        Object builder = builderClass.getConstructor(Context.class).newInstance(context);
        builderClass.getMethod("addMix", mixClass).invoke(builder, mix);
        Object candidate = builderClass.getMethod("build").invoke(builder);
        int result;
        try {
            Method method = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyClass);
            method.setAccessible(true);
            result = (Integer) method.invoke(null, candidate);
        } catch (NoSuchMethodException olderAndroid) {
            result = (Integer) AudioManager.class.getMethod("registerAudioPolicy", policyClass)
                    .invoke(context.getSystemService(Context.AUDIO_SERVICE), candidate);
        }
        if (result != 0) throw new IllegalStateException("AudioPolicy registration failed: " + result);
        policy = candidate;
        track = (AudioTrack) policyClass.getMethod("createAudioTrackSource", mixClass).invoke(policy, mix);
        if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("Injection AudioTrack is unavailable");
        }
        track.setBufferSizeInFrames(4800); // Bound stale audio to about 100 ms where supported.
        track.play();
    }

    private static void pump(String fifo) throws Exception {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        long epoch = Long.MIN_VALUE;
        byte[] pcm = new byte[16384];
        try (DataInputStream input = new DataInputStream(new FileInputStream(fifo))) {
            while (true) {
                long nextEpoch = input.readLong();
                int size = input.readInt();
                if (size <= 0 || size > pcm.length || (size & 1) != 0) {
                    throw new IllegalArgumentException("Invalid PCM frame size: " + size);
                }
                input.readFully(pcm, 0, size);
                if (nextEpoch != epoch) {
                    track.pause();
                    track.flush();
                    track.play();
                    epoch = nextEpoch;
                }
                int offset = 0;
                long deadline = SystemClock.elapsedRealtime() + 60;
                while (offset < size) {
                    int written = track.write(pcm, offset, size - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (written < 0) throw new IllegalStateException("AudioTrack.write: " + written);
                    offset += written;
                    if (offset == size) break;
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        // No recording client / stalled route: do not build an unbounded backlog.
                        track.pause();
                        track.flush();
                        track.play();
                        break;
                    }
                    SystemClock.sleep(2);
                }
            }
        }
    }

    private static int constant(Class<?> type, String name) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void cleanup() {
        if (track != null) {
            try { track.stop(); } catch (Throwable ignored) { }
            try { track.release(); } catch (Throwable ignored) { }
        }
        if (policy != null) {
            try {
                AudioManager.class.getMethod("unregisterAudioPolicy", policyClass)
                        .invoke(context.getSystemService(Context.AUDIO_SERVICE), policy);
            } catch (Throwable error) {
                try {
                    Method method = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass);
                    method.setAccessible(true);
                    method.invoke(null, policy);
                } catch (Throwable ignored) { /* Binder death also removes the registered policy. */ }
            }
        }
    }

    private static void fail(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        System.out.println("ERROR " + error);
        System.out.flush();
        exit(1);
    }

    private static void exit(int code) {
        if (!exiting.compareAndSet(false, true)) return;
        // A vendor AudioTrack release / Binder unregister must not strand a root process.
        new Thread(() -> {
            SystemClock.sleep(1500);
            Process.killProcess(Process.myPid());
        }, "glassmic-exit-deadline").start();
        System.exit(code);
    }
}
