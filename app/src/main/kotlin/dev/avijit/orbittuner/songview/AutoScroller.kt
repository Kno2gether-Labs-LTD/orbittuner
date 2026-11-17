package dev.avijit.orbittuner.songview

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Hands-free scrolling of the song sheet. */
object AutoScroller {
    const val MIN_SPEED = 5f
    const val MAX_SPEED = 200f
    private const val BARS_PER_LINE = 2
    private const val NO_TEMPO_SECONDS = 180f

    /**
     * Speed (px/s) that scrolls the overflow ([contentHeightPx] − [viewportPx]) over the song's estimated duration:
     * [lineCount] × beats per bar × 2 bars ÷ [tempoBpm] × 60 s, or 3 minutes without a tempo. Clamped to 5–200 px/s.
     */
    fun defaultSpeed(tempoBpm: Int?, timeSig: String?, lineCount: Int, contentHeightPx: Int, viewportPx: Int): Float {
        val overflow = (contentHeightPx - viewportPx).coerceAtLeast(0)
        val seconds = if (tempoBpm != null && tempoBpm > 0 && lineCount > 0) {
            lineCount * beatsPerBar(timeSig) * BARS_PER_LINE / tempoBpm.toFloat() * 60f
        } else {
            NO_TEMPO_SECONDS
        }
        return (overflow / seconds).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /** "3/4" -> 3, "6/8" -> 6; anything unparseable is 4. */
    fun beatsPerBar(timeSig: String?): Int =
        timeSig?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it in 1..32 } ?: 4

    /**
     * Scrolls [state] at [speedPxPerSec] (read every frame, so slider moves apply at once) until cancelled or the end is
     * reached; then [onEnd] runs. Sub-pixel steps are accumulated so slow speeds stay smooth. Frames where the user is
     * scrolling (drag or fling) are skipped.
     */
    suspend fun run(state: ScrollState, speedPxPerSec: () -> Float, onEnd: () -> Unit) {
        var last = -1L
        var carry = 0f
        while (true) {
            val now = withFrameNanos { it }
            if (state.isScrollInProgress) {
                // The user is dragging or flinging: let them, and resume from wherever they leave it.
                last = now
                carry = 0f
                continue
            }
            if (last >= 0) {
                val dt = (now - last).coerceAtMost(100_000_000L) / 1_000_000_000f // ignore long stalls
                carry += speedPxPerSec() * dt
                if (carry >= 1f) {
                    val step = carry.toInt().toFloat()
                    carry -= step
                    try {
                        state.scrollBy(step)
                    } catch (e: CancellationException) {
                        // A finger on the sheet outranks us: skip this frame and resume when it lets go.
                        if (!currentCoroutineContext().isActive) throw e
                        carry = 0f
                    }
                }
                if (state.value >= state.maxValue) {
                    onEnd()
                    return
                }
            }
            last = now
        }
    }
}
