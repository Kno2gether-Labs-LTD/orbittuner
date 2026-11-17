package dev.avijit.orbittuner.tuner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.settings.MeterStyle
import dev.avijit.orbittuner.settings.Prefs
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import dev.avijit.orbittuner.ui.theme.SheetType
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Microphone-permission states the route detects before the engine runs. */
enum class MicProblem { Rationale, Denied }

/**
 * Tune destination: owns the mic lifecycle (listening only while visible and resumed) and the RECORD_AUDIO
 * request, then draws [TunerScreen].
 */
@Composable
fun TunerRoute(graph: AppGraph) {
    val vm: TunerViewModel = viewModel { TunerViewModel(graph.tuner, graph.prefs, graph.instruments, RefTone()) }
    val ctx = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<MicProblem?>(null) }

    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun denialKind() =
        if (ctx.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true) MicProblem.Rationale
        else MicProblem.Denied

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // The permission dialog pauses the activity, so ON_RESUME follows and starts the engine (no double start).
        if (ok) problem = null else problem = denialKind()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        when {
            granted() -> { problem = null; vm.start() }
            !asked -> { asked = true; launcher.launch(Manifest.permission.RECORD_AUDIO) }
            else -> problem = denialKind()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.stop() }
    // Leaving the Tune destination (composition disposed) also releases the mic.
    DisposableEffect(vm) { onDispose { vm.stop() } }

    TunerScreen(
        vm,
        problem = problem,
        onRetry = {
            if (granted()) { problem = null; vm.start() } else launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onOpenSettings = {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private sealed interface SheetPage {
    data class Tunings(val instrumentId: String) : SheetPage
    data object Instruments : SheetPage
    data class Custom(val instrumentId: String) : SheetPage
    data object Reference : SheetPage
}

/** Keeps the open sheet across rotation and process death. */
private val SheetPageSaver = Saver<SheetPage?, String>(
    save = { p ->
        when (p) {
            is SheetPage.Tunings -> "tunings:" + p.instrumentId
            is SheetPage.Custom -> "custom:" + p.instrumentId
            SheetPage.Instruments -> "instruments"
            SheetPage.Reference -> "reference"
            null -> ""
        }
    },
    restore = { k ->
        when {
            k.startsWith("tunings:") -> SheetPage.Tunings(k.removePrefix("tunings:"))
            k.startsWith("custom:") -> SheetPage.Custom(k.removePrefix("custom:"))
            k == "instruments" -> SheetPage.Instruments
            k == "reference" -> SheetPage.Reference
            else -> null
        }
    },
)

@Composable
fun TunerScreen(
    vm: TunerViewModel,
    modifier: Modifier = Modifier,
    problem: MicProblem? = null,
    onRetry: () -> Unit = vm::start,
    onOpenSettings: () -> Unit = {},
    /** Confirm tick when a string becomes tuned; tests count calls instead. */
    onTunedHaptic: (() -> Unit)? = null,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Only rarely-changing fields are read here; the live reading is read inside Reading and the meter.
    val micError by vm.micError.collectAsStateWithLifecycle()
    val stringIndex by vm.stringIndex.collectAsStateWithLifecycle()
    val tuned by vm.tuned.collectAsStateWithLifecycle()
    val toneString by vm.toneString.collectAsStateWithLifecycle()
    var sheet by rememberSaveable(stateSaver = SheetPageSaver) { mutableStateOf<SheetPage?>(null) }

    val view = LocalView.current
    val haptic = onTunedHaptic ?: {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }
    LaunchedEffect(vm) {
        var prev = vm.tuned.value
        vm.tuned.collect { now ->
            if ((now - prev).isNotEmpty() && vm.ui.value?.haptics != false) haptic()
            prev = now
        }
    }

    val u = ui
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (u == null) return@Box
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tablet = maxWidth >= 600.dp
            val minH = maxHeight
            // Short windows (the Fold cover screen above its bottom nav, landscape phones): a smaller meter and
            // tighter spacing, so the string row fits without scrolling.
            val compact = maxHeight < 640.dp
            val guide = u.meterStyle == MeterStyle.Guide
            val side = if (tablet) 40.dp else 20.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minH)
                    .statusBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Header(
                        u, Modifier.padding(start = side, end = side - 8.dp, top = if (compact) 6.dp else if (tablet) 22.dp else 10.dp),
                        onPick = { sheet = SheetPage.Tunings(u.instrument.id) },
                        onReference = { sheet = SheetPage.Reference },
                        onCustom = { sheet = SheetPage.Custom(u.instrument.id) },
                    )
                    if (!u.instrument.chromatic) {
                        ModeToggle(
                            u.mode, vm::setMode,
                            Modifier.padding(horizontal = side).padding(top = if (compact) 12.dp else if (tablet) 20.dp else 18.dp)
                                .then(if (tablet) Modifier.widthIn(max = 520.dp) else Modifier.fillMaxWidth()),
                        )
                    }
                }
                // Three blocks spread top / centre / bottom: the reading and meter sit centred between the mode
                // toggle and the strings, so spare height splits evenly instead of pooling on one side (idle).
                Column(Modifier.fillMaxWidth()) {
                    if (problem != null || micError != null) {
                        MicPanel(
                            problem, micError, onRetry, onOpenSettings,
                            Modifier.padding(horizontal = side).padding(top = if (tablet) 44.dp else 30.dp)
                                .then(if (tablet) Modifier.widthIn(max = 720.dp).align(Alignment.CenterHorizontally) else Modifier),
                        )
                    } else {
                        Reading(
                            u, vm.readout, tablet, guide, compact,
                            Modifier.fillMaxWidth().padding(top = if (compact) 10.dp else if (tablet) 16.dp else 22.dp),
                        )
                        val meter = Modifier
                            .padding(top = if (compact) 6.dp else 12.dp, start = 16.dp, end = 16.dp)
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = if (tablet) 720.dp else 420.dp)
                            .fillMaxWidth()
                        if (guide) {
                            Guide(u, vm.readout, vm.live, tuned, meter.height(if (compact) GuideMeterHeightCompact else MeterHeight))
                        } else {
                            DialMeter(vm.live, vm.readout, meter.height(if (compact) DialMeterHeightCompact else MeterHeight))
                        }
                    }
                }
                Column {
                    if (u.strings.isNotEmpty()) {
                        StringRow(
                            u, stringIndex, tuned, toneString, tablet, vm::tapString,
                            Modifier.padding(
                                top = if (compact) 12.dp else if (tablet) 28.dp else 20.dp,
                                bottom = if (compact) 14.dp else if (tablet) 28.dp else 22.dp,
                            ),
                        )
                    }
                    if (tablet) {
                        Row(
                            Modifier.padding(start = side, end = side, bottom = 28.dp, top = if (u.strings.isEmpty()) 28.dp else 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            TuningsCard(u, Modifier.weight(1f), onSelect = { vm.selectTuning(u.instrument.id, it) }, onAll = {
                                sheet = SheetPage.Tunings(u.instrument.id)
                            })
                            Card(Modifier.weight(1f)) {
                                CardTitle("Reference")
                                ReferenceControls(u, vm)
                            }
                        }
                    }
                }
            }
        }
    }

    }
    if (u != null && sheet != null) {
        TunerSheet(sheet!!, u, vm, onPage = { sheet = it }, onDismiss = { sheet = null })
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(u: TunerUi, modifier: Modifier, onPick: () -> Unit, onReference: () -> Unit, onCustom: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Change instrument or tuning", role = Role.Button, onClick = onPick)
                .testTag("tuning-header")
                .padding(vertical = 4.dp),
        ) {
            Text(
                u.instrument.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildAnnotatedString {
                    append(u.baseTuning?.name ?: "Any note")
                    if (u.offset != 0) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                        append("  ${signed(u.offset)}")
                    }
                    if (u.strings.isNotEmpty()) append("  " + u.stringsText)
                },
                style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.04.em),
                color = oc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "A4 ${fmtA4(u.a4)} Hz",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = oc.muted,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .border(1.dp, oc.line, CircleShape)
                .clickable(onClickLabel = "Reference pitch and tuning shift", role = Role.Button, onClick = onReference)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
        Box {
            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }, modifier = Modifier.testTag("more")) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More tuner options")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (!u.instrument.chromatic) {
                    DropdownMenuItem(text = { Text("Create custom tuning") }, onClick = { open = false; onCustom() })
                }
                DropdownMenuItem(text = { Text("Reference and tuning shift") }, onClick = { open = false; onReference() })
            }
        }
    }
}

@Composable
private fun ModeToggle(mode: ModeChoice, onMode: (ModeChoice) -> Unit, modifier: Modifier) {
    val oc = OrbitTunerTheme.colors
    Row(
        modifier.selectableGroup().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(4.dp),
    ) {
        ModeChoice.entries.forEach { m ->
            val on = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) oc.raised else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onMode(m) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.name, style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onBackground else oc.muted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- reading

/**
 * The Guide meter for this screen: its words follow the reading, the mode's target string and the ticks. Only a
 * change of words recomposes the meter, not every cent.
 */
@Composable
private fun Guide(u: TunerUi, readout: StateFlow<Readout>, live: StateFlow<TunerState>, tuned: Set<Int>, modifier: Modifier) {
    val r = readout.collectAsStateWithLifecycle()
    val chromatic = u.mode == ModeChoice.Chromatic || u.strings.isEmpty()
    val line by remember(u, tuned) {
        derivedStateOf {
            val now = r.value
            // With nothing heard, Manual asks for its picked string.
            val target = if (!now.hasPitch && u.mode == ModeChoice.Manual) now.copy(stringIndex = u.manualString) else now
            guideLine(target, u.strings, tuned, chromatic)
        }
    }
    GuideMeter(live, readout, line, modifier)
}

/**
 * The note, large. With the Guide meter only a small line of exact numbers follows (the Guide speaks in words);
 * with the Dial, the Hz line and the cents with a flat/sharp hint.
 */
@Composable
private fun Reading(u: TunerUi, readout: StateFlow<Readout>, tablet: Boolean, guide: Boolean, compact: Boolean, modifier: Modifier) {
    val r by readout.collectAsStateWithLifecycle()
    val oc = OrbitTunerTheme.colors
    val tc = OrbitTunerTheme.tunerColors
    val idle = r.note == null
    // Between plucks the last note heard stays, faint, so the screen doesn't jump on every pluck.
    var last by remember(u.tuning, u.mode) { mutableStateOf<NoteLabel?>(null) }
    LaunchedEffect(r.note) { r.note?.let { last = it } }
    val shown = r.note ?: when {
        u.mode == ModeChoice.Manual && u.strings.isNotEmpty() -> u.strings[u.manualString]
        else -> last
    }
    val reduceMotion = rememberReduceMotion()
    val base = (if (tablet) SheetType.Tablet else SheetType.Phone).tunerNote
    // Solfège names are up to three letters ("Sol"): scale down so the note always fits on one line.
    val letters = (shown?.name ?: "").count { it.isLetter() }
    // Short windows take a smaller note: it is the tallest thing on the screen.
    val scale = when { letters >= 3 -> 0.66f; letters == 2 -> 0.8f; else -> 1f } * (if (compact) 0.66f else 1f)
    val noteStyle = base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale)
    // Nothing heard yet and no target: no placeholder note, so the meter sits up under the mode toggle (the
    // meter or the hint below already says what to do). The first note opens the space once.
    Column(
        modifier.then(if (reduceMotion) Modifier else Modifier.animateContentSize(tween(260))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (shown != null) Row(verticalAlignment = Alignment.Bottom) {
            Text(
                accidentalSmall(shown.name),
                style = noteStyle.copy(
                    letterSpacing = (-0.04).em, lineHeight = noteStyle.fontSize * 0.9f,
                    // Trim the font's ascent/descent padding so the glyph sits as tight as the approved frame.
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                maxLines = 1, softWrap = false,
                color = if (idle) oc.faint else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    // Literata's ascent leaves room for accents above capitals; trim it so the note sits tight.
                    .layout { m, c ->
                        val p = m.measure(c)
                        val top = (p.height * 0.16f).roundToInt()
                        val bottom = (p.height * 0.04f).roundToInt()
                        layout(p.width, p.height - top - bottom) { p.place(0, -top) }
                    }
                    .alignByBaseline()
                    .testTag("note"),
            )
            Text(
                shown.octave.toString(),
                style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Medium, fontSize = 34.sp),
                color = if (idle) oc.faint else oc.muted,
                modifier = Modifier.alignByBaseline().padding(start = 4.dp),
            )
        }
        if (shown != null) Text(
            when {
                idle -> " "
                guide -> "${signed(r.cents)} cents · ${fmtHz(r.hz)} Hz"
                else -> "${fmtHz(r.hz)} Hz, target ${fmtHz(r.targetHz)}"
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = oc.muted, modifier = Modifier.padding(top = if (compact) 6.dp else 10.dp),
        )
        if (guide) return@Column
        Row(
            Modifier.padding(top = if (compact) 8.dp else 14.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (idle) {
                Text(
                    when {
                        u.mode == ModeChoice.Chromatic || u.strings.isEmpty() -> "Play a note"
                        u.mode == ModeChoice.Manual -> "Play ${shown?.name ?: "the string"}, or tap it to hear it"
                        else -> "Play any string"
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp),
                    color = oc.muted,
                )
            } else {
                val col = when { r.inTune -> tc.inTune; r.cents < 0 -> tc.flat; else -> tc.sharp }
                Text(
                    buildAnnotatedString {
                        append(signed(r.cents))
                        withStyle(SpanStyle(fontSize = 0.6.em, fontWeight = FontWeight.SemiBold)) { append(" cents") }
                    },
                    style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 30.sp, fontFeatureSettings = "tnum"),
                    color = col,
                )
                Text(
                    when { r.inTune -> "In tune"; r.cents < 0 -> "Flat, tune up"; else -> "Sharp, tune down" },
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp),
                    color = oc.muted,
                )
            }
        }
    }
}

/** The accidental sits small and high, like engraved music, so the letter keeps the full size. */
private fun accidentalSmall(name: String) = buildAnnotatedString {
    val acc = name.indexOfFirst { it == '♯' || it == '♭' }
    if (acc < 0) { append(name); return@buildAnnotatedString }
    append(name.substring(0, acc))
    withStyle(SpanStyle(fontSize = 0.42.em, baselineShift = BaselineShift(1.15f))) { append(name.substring(acc)) }
}

@Composable
private fun MicPanel(problem: MicProblem?, error: MicError?, onRetry: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier) {
    val oc = OrbitTunerTheme.colors
    val (title, body, settings) = when {
        problem == MicProblem.Rationale -> Triple(
            "OrbitTuner listens to tune",
            "It needs the microphone to hear your instrument. Sound is analysed on this device and never recorded or sent anywhere.",
            true,
        )
        problem == MicProblem.Denied || error == MicError.PermissionDenied -> Triple(
            "Microphone access is off",
            "Allow the microphone for OrbitTuner in Settings, then come back to tune. Sound stays on this device.",
            true,
        )
        error == MicError.InUse -> Triple(
            "Microphone busy",
            "The microphone is in use by another app. Close it or end the call, then try again.",
            false,
        )
        else -> Triple("Microphone unavailable", "The microphone couldn't be started. Try again in a moment.", false)
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(24.dp),
    ) {
        MicGlyph(Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp), color = oc.muted, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Retry", onRetry)
            if (settings) SecondaryButton("Open settings", onOpenSettings)
        }
    }
}

@Composable
private fun MicGlyph(modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val raised = OrbitTunerTheme.colors.raised
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(raised)) {
        val s = size.minDimension / 44f
        val st = Stroke(2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(17f * s, 13f * s); lineTo(17f * s, 21f * s)
            cubicTo(17f * s, 27f * s, 27f * s, 27f * s, 27f * s, 21f * s); lineTo(27f * s, 13f * s)
            cubicTo(27f * s, 8f * s, 17f * s, 8f * s, 17f * s, 13f * s)
        }
        drawPath(p, accent, style = st)
        val arc = Path().apply { moveTo(13f * s, 20f * s); cubicTo(13f * s, 32f * s, 31f * s, 32f * s, 31f * s, 20f * s) }
        drawPath(arc, accent, style = st)
        drawLine(accent, Offset(22f * s, 29f * s), Offset(22f * s, 34f * s), strokeWidth = 2.4f * s, cap = StrokeCap.Round)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = OrbitTunerTheme.colors.raised, disabledContentColor = OrbitTunerTheme.colors.faint,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = OrbitTunerTheme.colors.raised, contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
}

// ---------------------------------------------------------------- strings

@Composable
private fun StringRow(
    u: TunerUi, stringIndex: Int?, tuned: Set<Int>, toneString: Int?, tablet: Boolean, onTap: (Int) -> Unit, modifier: Modifier,
) {
    val current = when (u.mode) {
        ModeChoice.Manual -> u.manualString
        ModeChoice.Auto -> stringIndex
        ModeChoice.Chromatic -> null
    }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Phones: chips narrow (down to a 48 dp touch target) and close up so six strings fit a 360 dp screen;
        // more strings than that still scroll.
        val n = u.strings.size
        val pad = 16.dp
        fun fit(gap: Dp) = (maxWidth - pad * 2 - gap * (n - 1)) / n
        val (pegW, gap) = when {
            tablet -> 84.dp to 16.dp
            fit(10.dp) >= 52.dp -> 52.dp to 10.dp
            fit(8.dp) >= 48.dp -> minOf(52.dp, fit(8.dp)) to 8.dp
            else -> 52.dp to 10.dp
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = pad),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            u.strings.forEachIndexed { i, label ->
                Peg(
                    index = i, label = label, current = i == current, done = i in tuned, playing = toneString == i,
                    width = pegW, height = if (tablet) 84.dp else 64.dp,
                    big = tablet, onClick = { onTap(i) },
                )
            }
        }
    }
}

@Composable
private fun Peg(
    index: Int, label: NoteLabel, current: Boolean, done: Boolean, playing: Boolean,
    width: Dp, height: Dp, big: Boolean, onClick: () -> Unit,
) {
    val oc = OrbitTunerTheme.colors
    val tc = OrbitTunerTheme.tunerColors
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(width, height)
            .clip(shape)
            .background(if (current) oc.raised else MaterialTheme.colorScheme.surface)
            .then(if (current) Modifier.border(1.5.dp, accent, shape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = "Play reference tone", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("String ${index + 1}, ${label.full}")
                    if (done) append(", tuned")
                    if (current) append(", current")
                    if (playing) append(", playing reference tone")
                }
            }
            .testTag("peg"),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label.name,
                style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = if (big) 26.sp else 20.sp),
                color = if (done) tc.inTune else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                (if (playing) "♪ " else "") + label.full,
                style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                color = if (current || playing) accent else oc.faint,
                maxLines = 1,
            )
        }
        if (done) {
            val bg = MaterialTheme.colorScheme.background
            Canvas(Modifier.align(Alignment.TopEnd).offset((-6).dp, 6.dp).size(13.dp)) {
                drawCircle(tc.inTune)
                val s = size.width / 13f
                val p = Path().apply { moveTo(3.6f * s, 6.8f * s); lineTo(5.6f * s, 8.8f * s); lineTo(9.4f * s, 4.6f * s) }
                drawPath(p, bg, style = Stroke(1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ---------------------------------------------------------------- tablet cards

@Composable
private fun Card(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 18.dp)) {
        content()
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text, style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
    )
}

@Composable
private fun TuningsCard(u: TunerUi, modifier: Modifier, onSelect: (String) -> Unit, onAll: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Card(modifier) {
        CardTitle("Tunings for ${u.instrument.name.substringBefore(" (").lowercase(Locale.ROOT)}")
        val list = u.instrument.tunings.take(5).let { top ->
            if (u.baseTuning != null && u.baseTuning !in top) top.dropLast(1) + u.baseTuning else top
        }
        list.forEachIndexed { i, t ->
            TuningLine(t, u, selected = t.id == u.baseTuning?.id, onClick = { onSelect(t.id) })
            if (i < list.lastIndex) Spacer(Modifier.fillMaxWidth().height(1.dp).background(oc.line))
        }
        run {
            Text(
                "All tunings and custom",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onAll).padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TuningLine(t: Tuning, u: TunerUi, selected: Boolean, onClick: () -> Unit, sub: String? = null, custom: Boolean = false) {
    val oc = OrbitTunerTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (custom) {
                    Text(
                        "Custom", style = MaterialTheme.typography.labelSmall, color = oc.muted,
                        modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(oc.raised).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = oc.muted)
        }
        Text(
            t.notes.joinToString(" ") { NoteLabels.label(it, u.naming).name },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, letterSpacing = 0.05.em),
            color = oc.muted, maxLines = 1, modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferenceControls(u: TunerUi, vm: TunerViewModel) {
    val oc = OrbitTunerTheme.colors
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(u.a4) { drag = null }
    val a4 = drag ?: u.a4.toFloat()
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.onBackground, activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = oc.line, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
    )
    RefRow("A4 pitch") { RefValue("${fmtA4(a4.toDouble())} Hz") }
    Slider(
        value = a4,
        onValueChange = { drag = it.roundToInt().toFloat() },
        onValueChangeFinished = { drag?.let { vm.setA4(it.toDouble()) } },
        valueRange = Prefs.MIN_A4.toFloat()..Prefs.MAX_A4.toFloat(),
        steps = (Prefs.MAX_A4 - Prefs.MIN_A4).toInt() - 1,
        colors = colors,
        // The approved rail: a thin line with a round thumb ringed in the card colour.
        thumb = {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(4.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.onBackground),
            )
        },
        track = { st ->
            SliderDefaults.Track(
                sliderState = st, colors = colors, modifier = Modifier.height(4.dp),
                drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 2.dp,
            )
        },
        modifier = Modifier.semantics { contentDescription = "A4 reference pitch" },
    )
    RefRow("Whole tuning shift") {
        Stepper(
            value = signed(u.offset),
            onDown = { vm.setOffset(u.offset - 1) }, onUp = { vm.setOffset(u.offset + 1) },
            downEnabled = u.offset > Prefs.MIN_OFFSET, upEnabled = u.offset < Prefs.MAX_OFFSET,
            downLabel = "Lower whole tuning a semitone", upLabel = "Raise whole tuning a semitone",
            downTag = "offset-down", upTag = "offset-up",
        )
    }
    RefRow("Haptic tick when in tune") {
        Switch(
            checked = u.haptics, onCheckedChange = vm::setHaptics,
            colors = SwitchDefaults.colors(
                checkedTrackColor = OrbitTunerTheme.tunerColors.inTune, checkedThumbColor = MaterialTheme.colorScheme.background,
                uncheckedTrackColor = oc.raised, uncheckedBorderColor = oc.line, uncheckedThumbColor = oc.muted,
            ),
            modifier = Modifier.semantics { contentDescription = "Haptic tick when in tune" },
        )
    }
}

@Composable
private fun RefRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OrbitTunerTheme.colors.muted, modifier = Modifier.weight(1f))
        value()
    }
}

@Composable
private fun RefValue(text: String) {
    Text(text, style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 22.sp, fontFeatureSettings = "tnum"))
}

@Composable
private fun Stepper(
    value: String, onDown: () -> Unit, onUp: () -> Unit, downEnabled: Boolean, upEnabled: Boolean,
    downLabel: String, upLabel: String, downTag: String, upTag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", downLabel, downEnabled, downTag, onDown)
        Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.Center) { RefValue(value) }
        StepButton("+", upLabel, upEnabled, upTag, onUp)
    }
}

@Composable
private fun StepButton(glyph: String, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(oc.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph, style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else oc.faint,
        )
    }
}

// ---------------------------------------------------------------- sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunerSheet(page: SheetPage, u: TunerUi, vm: TunerViewModel, onPage: (SheetPage) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(OrbitTunerTheme.colors.line))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            when (page) {
                is SheetPage.Tunings -> TuningsPage(page.instrumentId, u, vm, onPage, onDismiss)
                SheetPage.Instruments -> InstrumentsPage(u, vm, onPage, onDismiss)
                is SheetPage.Custom -> CustomPage(page.instrumentId, u, vm, onDismiss)
                SheetPage.Reference -> Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                    SheetTitle("Reference")
                    ReferenceControls(u, vm)
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
        Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = OrbitTunerTheme.colors.muted)
    }
}

@Composable
private fun TuningsPage(instrumentId: String, u: TunerUi, vm: TunerViewModel, onPage: (SheetPage) -> Unit, onDismiss: () -> Unit) {
    val inst = u.instruments.firstOrNull { it.id == instrumentId } ?: u.instrument
    val oc = OrbitTunerTheme.colors
    Row(
        Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { onPage(SheetPage.Instruments) }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text("All instruments", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
    }
    Column(Modifier.padding(horizontal = 24.dp)) {
        SheetTitle(inst.name, "${inst.tunings.size} tunings")
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        // The user's own tunings first, then the bundled set.
        val (mine, bundled) = inst.tunings.partition { it.id in u.customIds }
        listOf("Your tunings" to mine, "Tunings" to bundled).forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            if (mine.isNotEmpty()) item(key = "h-$title") {
                Text(
                    title, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = oc.faint,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
                )
            }
            items(list, key = { it.id }) { t ->
                TuningLine(
                    t, u, selected = inst.id == u.instrument.id && t.id == u.baseTuning?.id,
                    onClick = { vm.selectTuning(inst.id, t.id); onDismiss() },
                )
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(oc.line))
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onPage(SheetPage.Custom(inst.id)) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Create custom tuning", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

private val CATEGORY_ORDER = listOf("guitar", "bass", "ukulele", "banjo", "mandolin", "orchestral", "other", "chromatic")
private fun categoryTitle(c: String) = when (c) {
    "guitar" -> "Guitars"; "bass" -> "Basses"; "ukulele" -> "Ukuleles"; "banjo" -> "Banjos"
    "mandolin" -> "Mandolins"; "orchestral" -> "Orchestral strings"; "other" -> "World and folk"
    "chromatic" -> "Keyboard and voice"; else -> c.replaceFirstChar { it.uppercase() }
}

@Composable
private fun InstrumentsPage(u: TunerUi, vm: TunerViewModel, onPage: (SheetPage) -> Unit, onDismiss: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 24.dp)) {
        SheetTitle("Instrument and tuning")
        SearchField(query, { query = it }, "Search instruments and tunings")
    }
    val q = query.trim().lowercase(Locale.ROOT)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)) {
        if (q.isEmpty()) {
            val groups = u.instruments.groupBy { it.category }.toList()
                .sortedBy { (c, _) -> CATEGORY_ORDER.indexOf(c).let { if (it < 0) 99 else it } }
            groups.forEach { (cat, list) ->
                item(key = "h-$cat") {
                    Text(
                        categoryTitle(cat), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = oc.faint,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
                items(list, key = { "i-" + it.id }) { inst ->
                    InstrumentLine(inst, selected = inst.id == u.instrument.id) {
                        if (inst.chromatic) { vm.selectTuning(inst.id, null); onDismiss() } else onPage(SheetPage.Tunings(inst.id))
                    }
                }
            }
        } else {
            val instHits = u.instruments.filter { TuningSearch.matches(it, null, q) }
            val tuningHits = u.instruments.filterNot { it.chromatic }.flatMap { i ->
                i.tunings.filter { t -> TuningSearch.matches(null, t, q) }.map { i to it }
            }
            items(instHits, key = { "i-" + it.id }) { inst ->
                InstrumentLine(inst, selected = inst.id == u.instrument.id) {
                    if (inst.chromatic) { vm.selectTuning(inst.id, null); onDismiss() } else onPage(SheetPage.Tunings(inst.id))
                }
            }
            items(tuningHits, key = { "t-" + it.first.id + "/" + it.second.id }) { (inst, t) ->
                TuningLine(
                    t, u, selected = inst.id == u.instrument.id && t.id == u.baseTuning?.id, sub = inst.name,
                    custom = t.id in u.customIds, onClick = { vm.selectTuning(inst.id, t.id); onDismiss() },
                )
            }
            if (instHits.isEmpty() && tuningHits.isEmpty()) {
                item { Text("Nothing matches “${query.trim()}”", color = oc.muted, modifier = Modifier.padding(vertical = 16.dp)) }
            }
        }
    }
}

@Composable
private fun InstrumentLine(inst: Instrument, selected: Boolean, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) oc.raised else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            inst.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (inst.chromatic) "Any note" else "${inst.tunings.size} ${if (inst.tunings.size == 1) "tuning" else "tunings"}",
            style = MaterialTheme.typography.bodyMedium, color = oc.faint,
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val oc = OrbitTunerTheme.colors
    Row(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(oc.raised).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = oc.muted)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = oc.faint)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().testTag("picker-search").semantics { contentDescription = placeholder },
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = oc.muted) }
        }
    }
}

@Composable
private fun CustomPage(instrumentId: String, u: TunerUi, vm: TunerViewModel, onDismiss: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val inst = u.instruments.firstOrNull { it.id == instrumentId } ?: u.instrument
    val notes = rememberSaveable(instrumentId, saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        vm.customStartMidi(instrumentId).toMutableStateList()
    }
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
        SheetTitle("New tuning", "For ${inst.name}. Set each string, then name it.")
        notes.forEachIndexed { i, midi ->
            val label = vm.noteFor(midi)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("String ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = oc.muted, modifier = Modifier.width(76.dp))
                StepButton("−", "Lower string ${i + 1}", midi > MIN_MIDI, "custom-down-$i") { notes[i] = midi - 1 }
                Text(
                    label.full, textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                    modifier = Modifier.weight(1f),
                )
                StepButton("+", "Raise string ${i + 1}", midi < MAX_MIDI, "custom-up-$i") { notes[i] = midi + 1 }
            }
        }
        Text("Name", style = MaterialTheme.typography.labelMedium, color = oc.faint, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(oc.raised).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (name.isEmpty()) Text("e.g. Open C6", style = MaterialTheme.typography.bodyLarge, color = oc.faint)
            BasicTextField(
                name, { name = it.take(40) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("custom-name").semantics { contentDescription = "Tuning name" },
            )
        }
        PrimaryButton(
            "Save tuning", enabled = name.isNotBlank(),
            onClick = { vm.saveCustomTuning(inst.id, name, notes.toList()); onDismiss() },
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        )
    }
}

private const val MIN_MIDI = 12
private const val MAX_MIDI = 108

private fun signed(n: Int) = when { n > 0 -> "+$n"; n < 0 -> "−${-n}"; else -> "0" }
private fun fmtHz(hz: Double) = String.format(Locale.ROOT, "%.2f", hz)
private fun fmtA4(hz: Double) = if (hz == hz.roundToInt().toDouble()) hz.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", hz)
