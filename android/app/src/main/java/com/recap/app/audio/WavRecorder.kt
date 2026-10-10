package com.recap.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Records 16 kHz / 16-bit mono PCM from the phone mic straight into a WAV file. */
class WavRecorder(private val outFile: File, private val level: MutableStateFlow<Float>) {
    private var rec: AudioRecord? = null
    private var job: Job? = null
    var startedAt = 0L
        private set

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE),
        )
        rec = r
        r.startRecording()
        startedAt = System.currentTimeMillis()

        job = scope.launch(Dispatchers.IO) {
            RandomAccessFile(outFile, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                val samples = ShortArray(1024)
                val bytes = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
                var total = 0L
                while (isActive && total < MAX_BYTES) {
                    val n = r.read(samples, 0, samples.size)
                    if (n <= 0) continue
                    bytes.clear()
                    var sum = 0.0
                    for (i in 0 until n) {
                        bytes.putShort(samples[i])
                        sum += samples[i].toDouble() * samples[i]
                    }
                    raf.write(bytes.array(), 0, n * 2)
                    total += n * 2
                    level.value = min(1f, (sqrt(sum / n) / 5000.0).toFloat())
                }
                raf.seek(0)
                raf.write(header(total))
            }
        }
    }

    suspend fun stop(): File {
        job?.cancelAndJoin()
        runCatching { rec?.stop() }
        rec?.release()
        rec = null
        level.value = 0f
        return outFile
    }

    private fun header(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen.toInt())
        return b.array()
    }

    companion object {
        const val RATE = 16000
        const val MAX_SECONDS = 300 // phone notes auto-stop at 5 minutes
        private const val MAX_BYTES = RATE * 2L * MAX_SECONDS
    }
}
