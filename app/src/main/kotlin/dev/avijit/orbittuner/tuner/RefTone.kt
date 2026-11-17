package dev.avijit.orbittuner.tuner

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** Plays a reference pitch until stopped ([RefTone]; tests substitute a fake). */
interface TonePlayer {
    fun play(hz: Double)
    fun stop()
}

/** Streaming sine reference tone with 20 ms linear fades. [stop] is idempotent. */
class RefTone : TonePlayer {
    private val lock = Any()
    private var worker: Worker? = null

    override fun play(hz: Double) {
        synchronized(lock) {
            val w = worker
            if (w != null && w.alive) { w.hz = hz; w.fadeOut = false; return }
            worker = Worker(hz).also { it.start() }
        }
    }

    override fun stop() {
        synchronized(lock) { worker?.fadeOut = true; worker = null }
    }

    private class Worker(@Volatile var hz: Double) : Thread("OT-RefTone") {
        @Volatile var fadeOut = false
        @Volatile var alive = true

        override fun run() {
            var track: AudioTrack? = null
            try {
                val chunk = 480
                val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuf, chunk * 4 * 4))
                    .build()
                track.play()
                val buf = FloatArray(chunk)
                var phase = 0.0
                var gain = 0.0
                val step = 1.0 / (FADE_SECONDS * SR)
                while (true) {
                    val out = fadeOut
                    val inc = 2 * PI * hz / SR
                    for (i in buf.indices) {
                        gain = if (out) maxOf(0.0, gain - step) else minOf(1.0, gain + step)
                        buf[i] = (AMPLITUDE * gain * sin(phase)).toFloat()
                        phase += inc
                        if (phase > 2 * PI) phase -= 2 * PI
                    }
                    if (track.write(buf, 0, chunk, AudioTrack.WRITE_BLOCKING) < 0) break
                    if (out && gain <= 0.0) break
                }
            } catch (_: Exception) {
            } finally {
                alive = false
                try { track?.stop() } catch (_: IllegalStateException) {}
                track?.release()
            }
        }
    }

    companion object {
        private const val SR = 48_000
        private const val FADE_SECONDS = 0.020
        private const val AMPLITUDE = 0.4
    }
}
