package dev.avijit.orbittuner.tuner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

enum class MicError { PermissionDenied, InUse, Unavailable }

/** Thrown by an [AudioSource] to report a specific microphone failure. */
class MicException(val reason: MicError, cause: Throwable? = null) : Exception(reason.name, cause)

/**
 * Microphone abstraction. [start] runs the capture loop on the CALLING thread, invoking
 * [onFrames] with (buffer, validSampleCount) until [stop] is called (or the source is exhausted),
 * and throws [SecurityException] / [MicException] on failure. The buffer is reused between calls.
 * A [stop] that arrives before the loop begins must make [start] return without capturing.
 */
interface AudioSource {
    fun start(onFrames: (FloatArray, Int) -> Unit)
    fun stop()
    /** Clears any pending stop request. Called by the engine, under its lock, right before [start]. */
    fun reset() {}
}

/**
 * 48 kHz mono float AudioRecord, unprocessed when the device supports it.
 *
 * [stop] may arrive before, during or after construction of the AudioRecord: it sets a flag that is
 * checked after construction and on every loop pass, so a stop is never lost and the mic is always
 * released. The flag is consumed (cleared) when [start] returns.
 */
class AudioRecordSource(private val ctx: Context) : AudioSource {
    @Volatile private var record: AudioRecord? = null
    private val stopRequested = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    override fun start(onFrames: (FloatArray, Int) -> Unit) {
        try {
            if (stopRequested.get()) return
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("RECORD_AUDIO not granted")
            }
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (min <= 0) throw MicException(MicError.Unavailable)
            val unprocessed = (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val rec = try {
                AudioRecord(
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                    maxOf(min * 2, CHUNK * 4 * 4),
                )
            } catch (e: IllegalArgumentException) {
                throw MicException(MicError.Unavailable, e)
            }
            try {
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw MicException(MicError.InUse)
                record = rec
                // A stop that arrived while we were constructing: bail out before touching the mic.
                if (stopRequested.get()) return
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicException(MicError.InUse)
                val buf = FloatArray(CHUNK)
                while (!stopRequested.get()) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    when {
                        n > 0 -> onFrames(buf, n)
                        n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT ->
                            if (!stopRequested.get()) throw MicException(MicError.InUse)
                        n < 0 -> if (!stopRequested.get()) throw MicException(MicError.Unavailable)
                    }
                }
            } finally {
                record = null
                try { rec.stop() } catch (_: IllegalStateException) {}
                rec.release()
            }
        } finally {
            stopRequested.set(false)
        }
    }

    override fun reset() { stopRequested.set(false) }

    override fun stop() {
        stopRequested.set(true)
        try { record?.stop() } catch (_: IllegalStateException) {}
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val CHUNK = 1024
    }
}
