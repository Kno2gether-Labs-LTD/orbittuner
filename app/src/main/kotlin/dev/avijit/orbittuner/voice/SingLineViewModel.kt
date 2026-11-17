package dev.avijit.orbittuner.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbittuner.music.ShiftGuess
import dev.avijit.orbittuner.songview.SongDest
import dev.avijit.orbittuner.songview.SungShift
import dev.avijit.orbittuner.tuner.MicError
import dev.avijit.orbittuner.tuner.MicException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Where the Sing a line sheet is: record → find → results, or a problem to explain. */
sealed interface SingState {
    data object Idle : SingState
    /** Recording; [stopping] once Stop was tapped (the button is then disabled). */
    data class Listening(val secondsLeft: Int, val stopping: Boolean = false) : SingState
    data object Finding : SingState
    /** [opening]: the song being prepared (its shift worked out) after a tap. */
    data class Results(val outcome: VoiceOutcome.Results, val opening: String? = null) : SingState
    data class Problem(val problem: SingProblem) : SingState
}

sealed interface SingProblem {
    data object NoWords : SingProblem
    data object TooShort : SingProblem
    /** Today's tries are used: [limit] a day on [plan] ("anonymous", "free", "plus"). */
    data class Limit(val limit: Int?, val plan: String?) : SingProblem
    data object Offline : SingProblem
    data object Unavailable : SingProblem
    data object NoAccount : SingProblem
    data object MicBusy : SingProblem
    /** No mic permission; [permanent] when Android won't ask again (only Settings can turn it on). */
    data class MicDenied(val permanent: Boolean) : SingProblem
}

/**
 * Runs one Sing a line search at a time. The recording follows A2's contract: the session is armed with
 * [VoiceRecorder.begin] synchronously in [start], so a Stop tapped at once is never lost, and [stop] ends only that
 * session (once). Levels arrive on the capture thread and are posted, throttled, to [level].
 *
 * Opening a result ([open]) works out the move to the sung key only when the clip was sung ([VoiceOutcome.Results.singing]):
 * a confident shift opens moved, a close call opens with both keys offered, anything else opens in the song's own key.
 */
class SingLineViewModel(
    private val recorder: () -> VoiceRecorder,
    private val search: suspend (Recording) -> VoiceOutcome,
    private val shiftFor: suspend (songId: String) -> ShiftGuess?,
    private val nanos: () -> Long = System::nanoTime,
) : ViewModel() {
    private val _state = MutableStateFlow<SingState>(SingState.Idle)
    val state: StateFlow<SingState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Mic level 0..1 while listening (about 30 updates a second at most). */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _opened = Channel<SongDest>(Channel.BUFFERED)
    /** A result to open, with its sung-key shift when there is one. */
    val opened: Flow<SongDest> = _opened.receiveAsFlow()

    private var job: Job? = null
    /** Working out a tapped result's shift; cancelled with the sheet so a late answer never navigates. */
    private var openJob: Job? = null
    private var session: VoiceRecorder.Session? = null
    private val lastLevel = AtomicLong(Long.MIN_VALUE)

    /** Starts listening (also "Try again"). Needs RECORD_AUDIO; the caller asks first. */
    fun start() {
        job?.cancel()
        dropPendingOpen()
        session?.stop()
        val rec = recorder()
        val s = rec.begin() // armed before launching: an immediate Stop still reaches this recording
        session = s
        _level.value = 0f
        _state.value = SingState.Listening(VoiceRecorder.MAX_SECONDS)
        job = viewModelScope.launch {
            val ticker = launch {
                for (left in VoiceRecorder.MAX_SECONDS - 1 downTo 0) {
                    delay(1_000)
                    _state.update { if (it is SingState.Listening) it.copy(secondsLeft = left) else it }
                }
            }
            val clip = try {
                rec.record(s, VoiceRecorder.MAX_SECONDS, ::postLevel)
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                null.also { _state.value = SingState.Problem(SingProblem.MicDenied(permanent = false)) }
            } catch (e: MicException) {
                null.also {
                    _state.value = SingState.Problem(
                        if (e.reason == MicError.PermissionDenied) SingProblem.MicDenied(permanent = false) else SingProblem.MicBusy,
                    )
                }
            } finally {
                ticker.cancel()
                _level.value = 0f
            }
            if (clip == null) return@launch
            // Cancelled (sheet closed, app left) while the clip was finishing: it is never sent.
            ensureActive()
            _state.value = SingState.Finding
            val outcome = try { search(clip) } catch (e: CancellationException) { throw e } catch (_: Exception) { VoiceOutcome.Unavailable }
            _state.value = when (outcome) {
                is VoiceOutcome.Results -> SingState.Results(outcome)
                VoiceOutcome.NoWords -> SingState.Problem(SingProblem.NoWords)
                VoiceOutcome.TooShort -> SingState.Problem(SingProblem.TooShort)
                is VoiceOutcome.LimitReached -> SingState.Problem(SingProblem.Limit(outcome.limit, outcome.plan))
                VoiceOutcome.Offline -> SingState.Problem(SingProblem.Offline)
                VoiceOutcome.NoAccount -> SingState.Problem(SingProblem.NoAccount)
                VoiceOutcome.Unavailable -> SingState.Problem(SingProblem.Unavailable)
            }
        }
    }

    /** Stop: ends this recording once; later taps do nothing (the button is disabled too). */
    fun stop() {
        val st = _state.value as? SingState.Listening ?: return
        if (st.stopping) return
        _state.value = st.copy(stopping = true)
        session?.stop()
    }

    /** The sheet closed: drop the recording or search and release the mic. */
    fun cancel() {
        job?.cancel()
        dropPendingOpen()
        session?.stop()
        job = null
        session = null
        _level.value = 0f
        _state.value = SingState.Idle
    }

    fun micDenied(permanent: Boolean) {
        cancel()
        _state.value = SingState.Problem(SingProblem.MicDenied(permanent))
    }

    fun open(hit: RankedHit) {
        val st = _state.value as? SingState.Results ?: return
        if (st.opening != null) return
        val id = hit.hit.id
        if (!st.outcome.singing) { _opened.trySend(SongDest(id)); return }
        _state.value = st.copy(opening = id)
        openJob = viewModelScope.launch {
            val guess = try { shiftFor(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            // Still on these results? (The sheet may have closed or listening restarted meanwhile.)
            val still = _state.value.let { it is SingState.Results && it.opening == id }
            if (!still) return@launch
            _state.update { if (it is SingState.Results) it.copy(opening = null) else it }
            _opened.send(SongDest(id, shift = guess?.toSungShift()))
        }
    }

    /** Forgets a result being opened: its job, and any destination not yet taken by the sheet. */
    private fun dropPendingOpen() {
        openJob?.cancel()
        openJob = null
        while (_opened.tryReceive().isSuccess) Unit
    }

    /** Called on the capture thread: keeps at most one level per [LEVEL_EVERY_NS]. MutableStateFlow is thread-safe. */
    private fun postLevel(level: Float) {
        val t = nanos()
        val last = lastLevel.get()
        if (last != Long.MIN_VALUE && t - last < LEVEL_EVERY_NS) return
        if (lastLevel.compareAndSet(last, t)) _level.value = level
    }

    override fun onCleared() {
        session?.stop()
    }

    companion object {
        private const val LEVEL_EVERY_NS = 33_000_000L
    }
}

/**
 * A confident shift moves the song, 0 included (a saved transpose may differ from the song's own key; the song view
 * decides whether that is a move); a close call offers both; anything else opens as saved.
 */
internal fun ShiftGuess.toSungShift(): SungShift? = when {
    autoTranspose != null -> autoTranspose?.let { SungShift(it) }
    alternative != null -> SungShift(semitones, alternative)
    else -> null
}
