package dev.avijit.orbittuner.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbittuner.music.KeyFinder
import dev.avijit.orbittuner.songs.SongsIcons
import dev.avijit.orbittuner.songview.SongDest
import dev.avijit.orbittuner.tuner.rememberReduceMotion
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import kotlin.math.cos
import kotlin.math.sin

/** The privacy promise shown under the mic, word for word from the spec. */
const val SING_PRIVACY = "Your clip is sent to be turned into words, then deleted. We never keep it."

/**
 * "Sing a line" (Beta) as a bottom sheet over Songs. Closing it drops the recording. A tapped result arrives through
 * [onOpenSong] already carrying its sung-key shift; [onSearchWeb] gets the service's web query; [onSignIn] goes to the
 * account screen; [onAllowMic] asks for the microphone again (permanent: only the system settings can).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingLineSheet(
    vm: SingLineViewModel,
    onDismiss: () -> Unit,
    onOpenSong: (SongDest) -> Unit,
    onSearchWeb: (String) -> Unit,
    onSignIn: () -> Unit,
    onAllowMic: (permanent: Boolean) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val level = vm.level.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpenSong)
    LaunchedEffect(vm) { vm.opened.collect { open(it) } }
    val close = { vm.cancel(); onDismiss() }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 2.dp).size(36.dp, 4.dp).clip(CircleShape).background(OrbitTunerTheme.colors.line))
        },
    ) {
        SingLineContent(
            state, level = { level.value },
            onStop = vm::stop, onRetry = vm::start, onOpen = vm::open, onSearchWeb = onSearchWeb,
            onSignIn = onSignIn, onAllowMic = onAllowMic, onClose = close,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        )
    }
}

/** The sheet's body for one [state]; stateless so each state can be shown (and rendered) on its own. */
@Composable
fun SingLineContent(
    state: SingState,
    level: () -> Float,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (RankedHit) -> Unit,
    onSearchWeb: (String) -> Unit,
    onSignIn: () -> Unit,
    onAllowMic: (permanent: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Its own ink, so the sheet reads the same whatever hosts it.
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("sing-sheet")) {
            Header(onClose)
            when (state) {
                SingState.Idle, is SingState.Listening -> Listening(state as? SingState.Listening ?: SingState.Listening(VoiceRecorder.MAX_SECONDS), level, onStop)
                SingState.Finding -> Finding()
                is SingState.Results -> Results(state, onOpen, onSearchWeb, onRetry)
                is SingState.Problem -> Problem(state.problem, onRetry, onSignIn, onAllowMic, onClose)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun Header(onClose: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "SING A LINE",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.1.em), color = oc.muted,
            modifier = Modifier.semantics { heading(); contentDescription = "Sing a line" },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Beta",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 1.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center,
        ) { Icon(SongsIcons.Clear, contentDescription = null, tint = oc.muted, modifier = Modifier.size(20.dp)) }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Listening and finding
// ---------------------------------------------------------------------------------------------------------------------

private val PromptStyle = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)

@Composable
private fun Listening(state: SingState.Listening, level: () -> Float, onStop: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Sing a line, or say the song name", style = PromptStyle, modifier = Modifier.padding(top = 10.dp).widthIn(max = 320.dp))
        Text(
            "A few seconds is plenty. Tap the circle when you're done.",
            style = MaterialTheme.typography.bodyMedium, color = oc.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Ring(secondsLeft = state.secondsLeft, level = level, finding = false, Modifier.padding(top = 18.dp)) {
            val enabled = !state.stopping
            Box(
                Modifier.size(92.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .alpha(if (enabled) 1f else 0.55f)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onStop)
                    .semantics { contentDescription = "Stop"; if (!enabled) disabled() },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onPrimary)) }
        }
        val left = state.secondsLeft
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurface)) {
                    append(left.toString())
                }
                withStyle(SpanStyle(fontSize = 15.sp, color = oc.muted)) { append(if (left == 1) "  second left" else "  seconds left") }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 14.dp)
                .semantics { contentDescription = if (left == 1) "1 second left" else "$left seconds left" },
        )
        Privacy(Modifier.padding(top = 22.dp))
    }
}

@Composable
private fun Finding() {
    val oc = OrbitTunerTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Finding your song…", style = PromptStyle, modifier = Modifier.padding(top = 10.dp))
        Text(
            "Turning your clip into words",
            style = MaterialTheme.typography.bodyMedium, color = oc.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Ring(secondsLeft = 0, level = { 0f }, finding = true, Modifier.padding(top = 18.dp)) {
            Box(Modifier.size(92.dp).clip(CircleShape).background(oc.raised), contentAlignment = Alignment.Center) {
                Icon(SongsIcons.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
        }
        // Keeps the layout the same height as Listening, so the sheet doesn't jump.
        Spacer(Modifier.height(50.dp))
        Privacy(Modifier.padding(top = 22.dp))
    }
}

/**
 * The listening ring: a dot for each of the 15 seconds around the mic (the ones still to come are lit), and a soft
 * halo that breathes with the voice. While finding, one bright arc travels round (still with reduce motion).
 */
@Composable
private fun Ring(secondsLeft: Int, level: () -> Float, finding: Boolean, modifier: Modifier, center: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val oc = OrbitTunerTheme.colors
    val reduceMotion = rememberReduceMotion()
    val glow = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        snapshotFlow { level() }.collect { l ->
            if (reduceMotion) glow.snapTo(l) else glow.animateTo(l, spring(dampingRatio = 1f, stiffness = 300f))
        }
    }
    val spin = if (finding && !reduceMotion) {
        rememberInfiniteTransition(label = "finding").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "spin",
        ).value
    } else 0f
    Box(modifier.size(232.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(232.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val g = glow.value
            val disc = 46.dp.toPx()
            // Halo: two soft rings that swell with the level.
            drawCircle(accent.copy(alpha = 0.04f + 0.07f * g), radius = disc + 12.dp.toPx() + 40.dp.toPx() * g, center = c)
            drawCircle(accent.copy(alpha = 0.07f + 0.11f * g), radius = disc + 5.dp.toPx() + 16.dp.toPx() * g, center = c)
            // Seconds: dot 0 at the top, clockwise; elapsed ones fade to the line colour.
            val r = size.minDimension / 2 - 8.dp.toPx()
            val n = VoiceRecorder.MAX_SECONDS
            for (i in 0 until n) {
                val a = Math.toRadians(-90.0 + 360.0 * i / n)
                val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                val lit = !finding && i < secondsLeft
                drawCircle(if (lit) accent else oc.line, radius = if (lit) 3.5.dp.toPx() else 2.5.dp.toPx(), center = p)
            }
            if (finding) {
                drawArc(
                    accent, startAngle = spin - 90f, sweepAngle = 64f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        center()
    }
}

@Composable
private fun Privacy(modifier: Modifier = Modifier) {
    Text(
        SING_PRIVACY,
        style = MaterialTheme.typography.bodySmall, color = OrbitTunerTheme.colors.faint, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------------------------------------------------

/** The web search box takes up to 120 characters; a long transcript is cut at a word. */
internal fun webQueryFor(q: String): String {
    val t = q.trim().replace(Regex("\\s+"), " ")
    if (t.length <= 120) return t
    val cut = t.take(120)
    return cut.substringBeforeLast(' ', cut).trim()
}

@Composable
private fun Results(state: SingState.Results, onOpen: (RankedHit) -> Unit, onSearchWeb: (String) -> Unit, onRetry: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val r = state.outcome
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        Text(
            if (r.hits.isEmpty()) "We couldn't find it here" else "Is it one of these?",
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp).semantics { heading() },
        )
        if (r.transcript.isNotBlank()) {
            Text(
                "We heard “${r.transcript.trim()}”",
                fontFamily = Literata, fontStyle = FontStyle.Italic, fontSize = 16.sp, lineHeight = 22.sp, color = oc.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
        } else Spacer(Modifier.height(10.dp))
        for (h in r.hits) HitRow(h, opening = state.opening == h.hit.id, enabled = state.opening == null, onClick = { onOpen(h) })
        r.webQuery?.let { q ->
            val query = webQueryFor(q)
            if (r.hits.isNotEmpty()) Box(Modifier.padding(start = 62.dp, top = 4.dp, bottom = 4.dp).fillMaxWidth().height(1.dp).background(oc.line))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClick = { onSearchWeb(query) }).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tile { Icon(SongsIcons.Globe, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Search the web for '$query'", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Find chords on other sites", style = MaterialTheme.typography.bodyMedium, color = oc.muted)
                }
            }
        }
        TextButton(
            onClick = onRetry, modifier = Modifier.padding(top = 6.dp).height(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
        ) {
            Text("Sing again", fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun Tile(content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(OrbitTunerTheme.colors.raised), contentAlignment = Alignment.Center) { content() }
}

/** A server key as the app spells keys ("D#m" reads "Ebm"); unreadable keys show as sent, none as a note. */
internal fun keyLabel(raw: String?): String {
    val k = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return "♪"
    return KeyFinder.sheetKey(k, emptyList())?.displayName() ?: k
}

@Composable
private fun HitRow(h: RankedHit, opening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val accent = MaterialTheme.colorScheme.primary
    val why = h.reason ?: when (h.hit.match) {
        VoiceHit.LIBRARY -> "In your library"
        "title" -> "Matches the title"
        else -> "Matches the words"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Open", onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile {
            if (opening) CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
            else Text(keyLabel(h.hit.originalKey), fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = accent, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(h.hit.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            h.hit.artist?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = oc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                why, style = MaterialTheme.typography.labelMedium,
                color = if (h.reason != null) accent else oc.faint, maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Problems
// ---------------------------------------------------------------------------------------------------------------------

private data class Copy(val title: String, val body: String, val action: String?, val onAction: (() -> Unit)?)

private fun limitBody(p: SingProblem.Limit): String {
    val n = p.limit
    return when {
        n == null -> "You've used today's tries. You can sing again tomorrow."
        p.plan == "anonymous" -> "Without an account you get $n tries a day. Sign in for more, or try again tomorrow."
        p.plan == "free" -> "The free plan allows $n tries a day. You can sing again tomorrow."
        p.plan == "plus" -> "Plus allows $n tries a day. You can sing again tomorrow."
        else -> "Your plan allows $n tries a day. You can sing again tomorrow."
    }
}

@Composable
private fun Problem(p: SingProblem, onRetry: () -> Unit, onSignIn: () -> Unit, onAllowMic: (Boolean) -> Unit, onClose: () -> Unit) {
    val retry = "Try again"
    val c = when (p) {
        SingProblem.NoWords -> Copy("We didn't hear any words", "Sing a bit louder, or say the song's name.", retry, onRetry)
        SingProblem.TooShort -> Copy("That was a bit short", "Give it a few seconds before you tap stop.", retry, onRetry)
        is SingProblem.Limit -> Copy(
            "That's today's tries", limitBody(p),
            if (p.plan == "anonymous") "Sign in" else null, if (p.plan == "anonymous") onSignIn else null,
        )
        SingProblem.Offline -> Copy(
            "You're offline", "Sing a line needs the internet to turn your clip into words. Your saved songs still open.", retry, onRetry,
        )
        SingProblem.Unavailable -> Copy("We couldn't listen just now", "Something went wrong on our side. Please try again in a minute.", retry, onRetry)
        SingProblem.NoAccount -> Copy("Sign in to use Sing a line", "It needs an account to turn your clip into words.", "Sign in", onSignIn)
        SingProblem.MicBusy -> Copy("The microphone is busy", "Another app is using it. Close that app, then try again.", retry, onRetry)
        is SingProblem.MicDenied -> if (p.permanent) {
            Copy("Sing a line needs the microphone", "Turn on the microphone for OrbitTuner in your phone's settings.", "Open settings") { onAllowMic(true) }
        } else {
            Copy("Sing a line needs the microphone", "Allow it so we can hear you sing.", "Allow microphone") { onAllowMic(false) }
        }
    }
    val oc = OrbitTunerTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(oc.raised), contentAlignment = Alignment.Center) {
            Icon(
                if (p == SingProblem.Offline) SongsIcons.Offline else SongsIcons.Mic, contentDescription = null,
                tint = oc.muted, modifier = Modifier.size(26.dp),
            )
        }
        Text(c.title, style = PromptStyle, modifier = Modifier.padding(top = 18.dp).widthIn(max = 320.dp).semantics { heading() })
        Text(
            c.body, style = MaterialTheme.typography.bodyLarge, color = oc.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 340.dp),
        )
        Row(Modifier.padding(top = 26.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.action != null && c.onAction != null) {
                Box(
                    Modifier.height(52.dp).widthIn(min = 160.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .clickable(role = Role.Button, onClick = c.onAction).padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(c.action, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                Box(
                    Modifier.height(52.dp).widthIn(min = 160.dp).clip(CircleShape).border(1.dp, oc.line, CircleShape)
                        .clickable(role = Role.Button, onClick = onClose).padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Close", fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Host: mic permission + the sheet
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Hosts the Sing a line sheet and returns what the mic button does: ask for RECORD_AUDIO when needed (the tuner's
 * request path: the system dialog, then Settings once Android stops asking), open the sheet and start listening.
 * The sheet stays open across rotation (the view model keeps the recording).
 */
@Composable
fun singLineHost(
    vm: SingLineViewModel,
    onOpenSong: (SongDest) -> Unit,
    onSearchWeb: (String) -> Unit,
    onSignIn: () -> Unit,
): () -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    fun granted() = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok ->
        open = true
        if (ok) vm.start()
        else vm.micDenied(permanent = ctx.findActivity()?.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO) != true)
    }
    // Leaving the app (home, another app, screen off) releases the mic and drops the clip: nothing is sent. A rotation
    // or Fold unfold also stops the activity, but that isn't leaving: keep listening (as the metronome does).
    val activity = ctx.findActivity()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        if (open && activity?.isChangingConfigurations != true) { vm.cancel(); open = false }
    }
    // Back from process death with nothing to show: close rather than show an empty sheet.
    LaunchedEffect(open) { if (open && vm.state.value == SingState.Idle) open = false }
    if (open) {
        SingLineSheet(
            vm,
            onDismiss = { open = false },
            onOpenSong = { open = false; vm.cancel(); onOpenSong(it) },
            onSearchWeb = { q -> open = false; vm.cancel(); onSearchWeb(q) },
            onSignIn = { open = false; vm.cancel(); onSignIn() },
            onAllowMic = { permanent ->
                if (permanent) {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", ctx.packageName, null),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else launcher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
        )
    }
    return {
        if (granted()) { open = true; vm.start() } else launcher.launch(android.Manifest.permission.RECORD_AUDIO)
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
