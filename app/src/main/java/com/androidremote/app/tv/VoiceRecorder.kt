package com.androidremote.app.tv

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable

/**
 * Records the phone microphone in the format the TV expects: 16-bit mono PCM at 8 kHz.
 * Recording starts as soon as the recorder is opened, so nothing is lost while the TV
 * sets up its voice session.
 */
class VoiceRecorder private constructor(private val record: AudioRecord) : Closeable {

    /**
     * Blocks until one full chunk has been recorded.
     *
     * @return the chunk, or null if recording failed or was stopped.
     */
    fun readChunk(): ByteArray? {
        val chunk = ByteArray(RemoteClient.VOICE_CHUNK_MIN_SIZE)
        var filled = 0
        while (filled < chunk.size) {
            val n = record.read(chunk, filled, chunk.size - filled)
            if (n <= 0) return null
            filled += n
        }
        return chunk
    }

    override fun close() {
        runCatching { record.stop() }
        record.release()
    }

    companion object {
        private const val SAMPLE_RATE = 8_000

        /** Room for about 4 s of audio while the voice session is being set up. */
        private const val BUFFER_BYTES = 64 * 1024

        /** Starts recording. Needs the RECORD_AUDIO permission. Returns null if the microphone is unavailable. */
        @SuppressLint("MissingPermission")
        fun open(): VoiceRecorder? = try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                BUFFER_BYTES,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
                VoiceRecorder(record)
            } else {
                record.release()
                null
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}
