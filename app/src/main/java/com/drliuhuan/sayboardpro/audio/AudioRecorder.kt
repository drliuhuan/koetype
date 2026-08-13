package com.drliuhuan.sayboardpro.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.drliuhuan.sayboardpro.Constants
import com.drliuhuan.sayboardpro.CrashLogger
import java.io.IOException

/**
 * 麦克风录音：16kHz 单声道 PCM 16bit，独立线程读取。
 * 参考 Sayboard 的 MySpeechService / opentypeless 的 audio/capture。
 *
 * 数据通过 [Listener.onData] 以 ShortArray 回调出来，由上层喂给 STT provider；
 * 同时计算 RMS 音量通过 [Listener.onVolume] 回调，供"静音自动结束"使用。
 */
class AudioRecorder(private val listener: Listener) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    // ── 调试统计（仅供录音线程读写，无需加锁） ──────────────────────
    /** 本次 start() 以来累计读到的数据块数（一块 = 0.2s PCM） */
    private var readBlocks = 0L

    /** 累计读到的字节数（short 数 × 2） */
    private var readBytes = 0L

    /** 节流日志：上一次打点的块数/时间（onData 高频回调，不能每条都打） */
    private var lastLogBlocks = 0L
    private var lastLogMs = 0L

    private val bufferSize: Int
        get() {
            val min = AudioRecord.getMinBufferSize(
                Constants.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 0.2 秒一个缓冲，至少为系统最小值
            return maxOf(min, (Constants.SAMPLE_RATE * 0.2).toInt())
        }

    val isRecording: Boolean
        get() = running

    /** 开始录音，返回是否成功 */
    fun start(): Boolean {
        if (running) {
            CrashLogger.w(TAG, "AUDIO: recorder start FAILED (already running)")
            return false
        }
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Constants.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .build()
        } catch (e: Exception) {
            CrashLogger.e(TAG, "AUDIO: recorder start FAILED (init: ${e.message})", e)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            CrashLogger.w(TAG, "AUDIO: recorder start FAILED (state=${rec.state})")
            return false
        }

        recorder = rec
        running = true
        readBlocks = 0
        readBytes = 0
        lastLogBlocks = 0
        lastLogMs = SystemClock.elapsedRealtime()
        thread = Thread { recordLoop(rec) }.also { it.start() }
        CrashLogger.d(TAG, "AUDIO: recorder start ok (bufferSize=$bufferSize)")
        return true
    }

    private fun recordLoop(rec: AudioRecord) {
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                CrashLogger.w(TAG, "AUDIO: recorder start FAILED (recordingState=${rec.recordingState})")
                mainHandler.post { listener.onError(IOException("Failed to start recording. Microphone might be already in use.")) }
                running = false
                return
            }
            val buffer = ShortArray(bufferSize)
            while (running && !Thread.currentThread().isInterrupted) {
                val nread = rec.read(buffer, 0, buffer.size)
                if (nread < 0) {
                    CrashLogger.w(TAG, "AUDIO: recorder read error code=$nread")
                    mainHandler.post { listener.onError(IOException("error reading audio buffer")) }
                    break
                }
                if (nread > 0) {
                    readBlocks++
                    readBytes += nread * 2L
                    listener.onData(buffer, nread)
                    listener.onVolume(computeRms(buffer, nread))
                    maybeLogProgress()
                }
            }
        } catch (e: Exception) {
            CrashLogger.e(TAG, "AUDIO: recorder loop failed", e)
            mainHandler.post { listener.onError(e) }
        } finally {
            // 可能已被 release() 抢先释放，全部防御式处理
            try {
                rec.stop()
            } catch (_: Exception) {
                // recorder already stopped/released
            }
            try {
                rec.release()
            } catch (_: Exception) {
                // already released
            }
            running = false
            CrashLogger.d(TAG, "AUDIO: recorder stopped (blocks=$readBlocks totalBytes=$readBytes)")
            // 通知上层录音线程已完全结束（此时才可以安全地读取 final 结果）
            mainHandler.post { listener.onStopped() }
        }
    }

    /** onData 节流打点：每 [LOG_INTERVAL_MS] 或距上次打点累计 [LOG_BLOCK_INTERVAL] 块打一次，避免高频回调刷爆日志 */
    private fun maybeLogProgress() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogMs >= LOG_INTERVAL_MS || readBlocks - lastLogBlocks >= LOG_BLOCK_INTERVAL) {
            CrashLogger.d(TAG, "AUDIO: onData blocks=$readBlocks totalBytes=$readBytes")
            lastLogBlocks = readBlocks
            lastLogMs = now
        }
    }

    /** 停止录音（非阻塞，回调线程结束后自然退出） */
    fun stop() {
        running = false
    }

    /** 立即释放（销毁时调用） */
    fun release() {
        running = false
        thread?.interrupt()
        thread = null
        recorder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
                // ignore
            }
            it.release()
        }
        recorder = null
        CrashLogger.d(TAG, "AUDIO: recorder released (blocks=$readBlocks)")
    }

    private fun computeRms(samples: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return (Math.sqrt(sum / length)).toFloat().coerceIn(0f, 1f)
    }

    interface Listener {
        /** PCM 数据，sampleRate 由 [com.drliuhuan.sayboardpro.Constants.SAMPLE_RATE] 决定 */
        fun onData(samples: ShortArray, length: Int)

        /** RMS 音量 0.0~1.0 */
        fun onVolume(level: Float)

        fun onError(e: Exception)

        /** 录音线程已完全结束（主线程回调），此时可安全读取最终识别结果 */
        fun onStopped()
    }

    companion object {
        private const val TAG = "AudioRecorder"

        /** onData 节流日志间隔：5 秒 */
        private const val LOG_INTERVAL_MS = 5_000L

        /** onData 节流日志：距上次打点累计 50 块（50×0.2s=10s 音频）也打一次 */
        private const val LOG_BLOCK_INTERVAL = 50L
    }
}
