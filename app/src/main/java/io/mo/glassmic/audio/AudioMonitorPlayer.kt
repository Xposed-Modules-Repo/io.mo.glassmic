package io.mo.glassmic.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import io.mo.glassmic.log.GlassLog
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实时耳返 / 本地监听播放器。
 *
 * 当虚拟麦克风向目标应用推流时，同步把主格式（48kHz 单声道 PCM16）音频写入 AudioTrack，
 * 让用户通过扬声器或耳机实时听到当前替换的音源内容与进度。
 */
@Singleton
class AudioMonitorPlayer @Inject constructor() {

    private var audioTrack: AudioTrack? = null
    @Volatile private var isEnabled: Boolean = false
    @Volatile private var volume: Float = 1.0f
    private val isPlaying = AtomicBoolean(false)

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        if (!enabled) {
            pauseAndFlush()
        }
    }

    @Synchronized
    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        volume = clamped
        audioTrack?.let { track ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track.setVolume(clamped)
                } else {
                    @Suppress("DEPRECATION")
                    track.setStereoVolume(clamped, clamped)
                }
            }
        }
    }

    /**
     * 写入一帧 PCM16 数据并播放。
     * 如果未开启耳返，则直接跳过，零开销。
     */
    fun write(data: ByteArray) {
        if (!isEnabled || data.isEmpty()) return
        val track = getOrCreateTrack() ?: return
        try {
            if (!isPlaying.get()) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                isPlaying.set(true)
            }
            track.write(data, 0, data.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (t: Throwable) {
            GlassLog.b("AudioMonitor") { "write 异常: ${t.message}" }
        }
    }

    /**
     * 暂停并清空缓冲区（目标应用停止录音、暂停或切换音源时调用）。
     */
    @Synchronized
    fun pauseAndFlush() {
        if (isPlaying.compareAndSet(true, false)) {
            runCatching {
                audioTrack?.let { track ->
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                    track.flush()
                }
            }
        }
    }

    @Synchronized
    private fun getOrCreateTrack(): AudioTrack? {
        if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            return audioTrack
        }
        release()
        return try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufSize = (minBuf * 2).coerceAtLeast(1920 * 2)

            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .setEncoding(AUDIO_FORMAT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufSize,
                    AudioTrack.MODE_STREAM
                )
            }

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track.setVolume(volume)
                } else {
                    @Suppress("DEPRECATION")
                    track.setStereoVolume(volume, volume)
                }
                audioTrack = track
                track
            } else {
                track.release()
                null
            }
        } catch (t: Throwable) {
            GlassLog.b("AudioMonitor") { "AudioTrack 初始化失败: ${t.message}" }
            null
        }
    }

    @Synchronized
    fun release() {
        isPlaying.set(false)
        audioTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        }
        audioTrack = null
    }
}
