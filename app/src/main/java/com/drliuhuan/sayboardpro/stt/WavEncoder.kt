package com.drliuhuan.sayboardpro.stt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把裸 PCM（16kHz 单声道 16bit 小端）封装成 WAV。
 * 移植自 OpenTypeless whisper_compat.rs 的 build_wav。
 */
object WavEncoder {

    fun encode(pcm: ByteArray, sampleRate: Int): ByteArray {
        val dataLen = pcm.size
        val channels: Short = 1
        val bitsPerSample: Short = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val fileSize = 36 + dataLen

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(fileSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                      // fmt chunk size
            putShort(1)                     // PCM
            putShort(channels)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen)
        }.array()

        return header + pcm
    }
}
