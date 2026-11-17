package dev.avijit.orbittuner.settings

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.data.SyncScheduler
import dev.avijit.orbittuner.library.PlaylistCover
import dev.avijit.orbittuner.net.AccountState
import dev.avijit.orbittuner.songview.DataStoreDiagramPrefs
import dev.avijit.orbittuner.songview.DiagramInstrument
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import java.util.Locale
import kotlin.math.roundToInt

/** Settings wired to the app graph. */
@Composable
fun SettingsRoute(graph: AppGraph) {
    val context = LocalContext.current.applicationContext
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(
            graph.prefs, graph.auth, graph.accounts, graph.library,
            requestSync = { SyncScheduler.requestSafely(context) },
            diagramPrefs = DataStoreDiagramPrefs.get(graph.context),
            actionScope = graph.appScope,
            googleSignIn = graph.googleSignIn,
        )
    }
    SettingsScreen(vm)
}

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val snackbar = remember { SnackbarHostState() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        "Settings", style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 4.dp).semantics { heading() },
                    )
                    Section("Account") { AccountCard(vm) }
                    Section("Tuner") { TunerCard(vm) }
                    Section("Song view") { SongViewCard(vm) }
                    Section("Appearance") { AppearanceCard(vm) }
                    if (vm.debug) Section("Developer") { DeveloperCard(vm) }
                    Section("About") { AboutCard(vm) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
        modifier = Modifier.padding(start = 4.dp, top = 26.dp, bottom = 10.dp).semantics { heading() },
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface), content = content)
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(OrbitTunerTheme.colors.line))
}

@Composable
private fun RowTitle(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OrbitTunerTheme.colors.muted)
    }
}

/** Mockup `.seg`: a sunken track with the chosen option raised. */
@Composable
private fun <T> Segmented(options: List<T>, chosen: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val on = if (dark) OrbitTunerTheme.colors.raised else scheme.surface
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.background).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { o ->
            val sel = o == chosen
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) on else Color.Transparent)
                    .then(if (sel && !dark) Modifier.border(1.dp, OrbitTunerTheme.colors.line, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(o) }
                    .semantics { selected = sel },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o), fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = if (sel) scheme.onSurface else OrbitTunerTheme.colors.muted, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun <T> SegmentedRow(title: String, subtitle: String?, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
        RowTitle(title, subtitle)
        Spacer(Modifier.height(12.dp))
        Segmented(options, selected, label, onSelect)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Account
// ---------------------------------------------------------------------------------------------------------------------

private fun planLabel(role: String, plan: String): String = when (role) {
    "owner" -> "Owner"
    "admin" -> "Admin"
    else -> if (plan == "plus") "Plus" else "Free"
}

@Composable
private fun AccountCard(vm: SettingsViewModel) {
    val oc = OrbitTunerTheme.colors
    val account by vm.account.collectAsStateWithLifecycle()
    val last by vm.lastSync.collectAsStateWithLifecycle()
    val errors by vm.syncErrors.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val warn = OrbitTunerTheme.tunerColors.sharp
    val ok = OrbitTunerTheme.tunerColors.inTune
    val me = (account as? AccountState.Ready)?.me
    val signedIn = account is AccountState.Ready || account == AccountState.Disabled
    val resetNotice by vm.resetNotice.collectAsStateWithLifecycle()
    val googleBusy by vm.googleBusy.collectAsStateWithLifecycle()
    val googleError by vm.googleError.collectAsStateWithLifecycle()
    val googleConfirm by vm.googleConfirm.collectAsStateWithLifecycle()
    val googleEmail = me?.google?.email
    val activity = LocalContext.current

    if (resetNotice) AccountResetNotice(vm::dismissResetNotice, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp))

    if (account == AccountState.Disabled) {
        Row(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(warn.copy(alpha = 0.10f)).border(1.dp, warn.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .testTag("account-disabled").padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = warn, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("This account is disabled", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Nothing syncs while it's disabled. Your favourites, playlists and the tuner keep working on this device.",
                    style = MaterialTheme.typography.bodySmall, color = oc.muted,
                )
            }
        }
    }
    val (title, subtitle) = when (account) {
        is AccountState.Ready -> (googleEmail?.let { "Signed in as $it" } ?: "Your OrbitTuner account") to
            "Favourites and playlists are kept in your account and sync to this device."
        AccountState.Creating -> "Setting up your account" to "Connecting to OrbitTuner…"
        AccountState.None -> "Setting up your account" to
            "Happens automatically when you're online. Until then, favourites and playlists stay on this device."
        AccountState.Disabled -> "Your OrbitTuner account" to "Contact OrbitTuner support if this looks wrong."
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlaylistCover(if (signedIn) 5 else 3, if (signedIn) 1.1f else 0.4f, 48.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (googleEmail != null && account is AccountState.Ready) GoogleGlyph(16.dp)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = oc.muted)
        }
        if (me != null) {
            val plus = me.plan == "plus" || me.role == "owner" || me.role == "admin"
            Text(
                planLabel(me.role, me.plan), fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = if (plus) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(CircleShape)
                    .background(if (plus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                    .testTag("plan-chip").padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
    if (account is AccountState.Ready) {
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowTitle("AI imports left today", if (me == null) "Shown when you're online" else "Resets at midnight UTC", Modifier.weight(1f))
            if (me != null) Text(
                "${me.aiLeftToday} of ${me.aiDailyLimit}", fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            )
        }
        Divider()
        val (dot, status) = when {
            last == null -> oc.faint to "Not synced yet"
            else -> ok to "Synced ${syncedAgo(last!!)}"
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(1f)) {
                Text(status, style = MaterialTheme.typography.bodyMedium, color = oc.muted)
                if (errors > 0) Text(
                    if (errors == 1) "1 playlist wasn't accepted. It's marked in Library." else "$errors playlists weren't accepted. They're marked in Library.",
                    style = MaterialTheme.typography.bodySmall, color = warn,
                )
            }
            TextButton(onClick = vm::syncNow) { Text("Sync now") }
        }
    }
    // Google sign-in: hidden once linked (the title says "Signed in as …"), and when this build has no client ID.
    if (googleEmail == null && account != AccountState.Disabled) {
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowTitle(
                "Sign in with Google",
                if (vm.googleAvailable) "Keep favourites and playlists across your devices" else "Google sign-in isn't set up yet",
                Modifier.weight(1f),
            )
            if (vm.googleAvailable) OutlinedButton(
                onClick = { vm.signInWithGoogle(activity) },
                enabled = account is AccountState.Ready && !googleBusy,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.testTag("google-sign-in").semantics { contentDescription = "Sign in with Google" },
            ) {
                if (googleBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else GoogleGlyph(16.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (googleBusy) "Signing in" else "Sign in")
            }
        }
        googleError?.let { msg ->
            Text(
                msg, style = MaterialTheme.typography.bodySmall, color = warn,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp).testTag("google-error"),
            )
        }
    }
    if (signedIn) {
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            TextButton(onClick = { confirmSignOut = true }) { Text("Sign out of this device", color = warn) }
        }
    }
    googleConfirm?.let { pending ->
        val n = pending.changes
        AlertDialog(
            onDismissRequest = vm::cancelGoogleSignIn,
            title = { Text("Sign in with Google?") },
            text = {
                Text(
                    "${if (n == 1) "1 change" else "$n changes"} couldn't be saved and will be removed if this Google " +
                        "account already has OrbitTuner data. Continue?",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmGoogleSignIn, modifier = Modifier.testTag("google-confirm")) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelGoogleSignIn, modifier = Modifier.testTag("google-confirm-cancel")) { Text("Cancel") }
            },
        )
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out of this device?") },
        text = {
            Text(
                "Favourites and playlists are removed from this device, and it starts again with a new account. " +
                    "Songs you've opened stay readable, and the tuner works as before.",
            )
        },
        confirmButton = {
            TextButton(onClick = { confirmSignOut = false; vm.signOut() }, modifier = Modifier.testTag("sign-out-confirm")) {
                Text("Sign out", color = warn)
            }
        },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )
}

/** The four-colour Google "G", drawn (no bitmap asset). */
@Composable
private fun GoogleGlyph(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val stroke = w * 0.22f
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arc = Size(w - stroke, w - stroke)
        // Angles: 0 = 3 o'clock, clockwise. The gap sits at the upper right, where the blue bar starts.
        drawArc(Color(0xFFEA4335), 200f, 118f, false, topLeft, arc, style = Stroke(stroke))
        drawArc(Color(0xFFFBBC05), 145f, 56f, false, topLeft, arc, style = Stroke(stroke))
        drawArc(Color(0xFF34A853), 40f, 106f, false, topLeft, arc, style = Stroke(stroke))
        drawArc(Color(0xFF4285F4), 0f, 41f, false, topLeft, arc, style = Stroke(stroke))
        drawRect(Color(0xFF4285F4), Offset(w / 2, w / 2 - stroke / 2), Size(w / 2, stroke))
    }
}

/** Shown once after a revoked account was replaced (Library and Settings) until dismissed. */
@Composable
fun AccountResetNotice(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val warn = OrbitTunerTheme.tunerColors.sharp
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(warn.copy(alpha = 0.10f)).border(1.dp, warn.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .testTag("account-reset-banner").padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = warn, modifier = Modifier.size(20.dp))
        Text(
            "This device was signed out, so a new account was started. Favourites and playlists on this device were cleared.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Developer (debug builds only)
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun DeveloperCard(vm: SettingsViewModel) {
    val oc = OrbitTunerTheme.colors
    val override by vm.hostOverride.collectAsStateWithLifecycle()
    val host by vm.accountHost.collectAsStateWithLifecycle()
    var text by rememberSaveable(override) { mutableStateOf(override ?: vm.defaultHost) }
    var invalid by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
        RowTitle("Service host", "New accounts are made here. Changing it signs this device out. Default: ${vm.defaultHost}")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            text, onValueChange = { text = it; invalid = false }, singleLine = true, isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            supportingText = if (invalid) ({ Text("Use a bare host like tuner.orbitapps.space or 10.0.2.2:8787") }) else null,
            modifier = Modifier.fillMaxWidth().testTag("service-host"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Signed in on ${host ?: "no service yet"}", style = MaterialTheme.typography.bodySmall, color = oc.muted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { text = vm.defaultHost; vm.setServiceHost("") }) { Text("Default") }
            TextButton(onClick = { invalid = !vm.setServiceHost(text) }) { Text("Apply") }
        }
    }
}

private fun syncedAgo(at: Long, now: Long = System.currentTimeMillis()): String =
    if (now - at < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

// ---------------------------------------------------------------------------------------------------------------------
// Preferences
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun TunerCard(vm: SettingsViewModel) {
    val a4 by vm.a4.collectAsStateWithLifecycle()
    val naming by vm.noteNaming.collectAsStateWithLifecycle()
    val acc by vm.accidental.collectAsStateWithLifecycle()
    val meter by vm.meterStyle.collectAsStateWithLifecycle()
    SegmentedRow(
        "Tuner meter", "Guide talks you through each string; Dial shows exact cents", MeterStyle.entries, meter,
        label = { if (it == MeterStyle.Guide) "Guide" else "Dial" }, onSelect = vm::setMeterStyle,
    )
    Divider()
    A4Row(a4, vm::setA4)
    Divider()
    SegmentedRow(
        "Note names", null, NoteNaming.entries, naming,
        label = { if (it == NoteNaming.Letters) "C D E" else "Do Re Mi" }, onSelect = vm::setNoteNaming,
    )
    Divider()
    SegmentedRow(
        "Sharps or flats", "For chromatic readings and songs without a key", AccidentalPref.entries, acc,
        label = { if (it == AccidentalPref.Sharps) "C♯  Sharps" else "D♭  Flats" }, onSelect = vm::setAccidental,
    )
}

private fun fmtHz(hz: Double) = if (hz % 1.0 == 0.0) hz.toInt().toString() else String.format(Locale.ROOT, "%.1f", hz)

/** A4 reference: a stepper for exact values and a rail for big moves (415 to 466 Hz). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun A4Row(a4: Double, onChange: (Double) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag?.toDouble() ?: a4
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowTitle("Reference pitch", if (shown == Prefs.DEFAULT_A4) "A4, the usual standard" else "A4. Standard is 440 Hz", Modifier.weight(1f))
            StepButton("−", "Lower A4", enabled = shown > Prefs.MIN_A4) { onChange((shown - 1).coerceAtLeast(Prefs.MIN_A4)) }
            Text(
                buildString { append(fmtHz(shown)); append(" Hz") },
                fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center,
                modifier = Modifier.width(84.dp).semantics { contentDescription = "A4 ${fmtHz(shown)} hertz" },
            )
            StepButton("+", "Raise A4", enabled = shown < Prefs.MAX_A4) { onChange((shown + 1).coerceAtMost(Prefs.MAX_A4)) }
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { drag = it.roundToInt().toFloat() },
            onValueChangeFinished = { drag?.let { onChange(it.toDouble()) }; drag = null },
            valueRange = Prefs.MIN_A4.toFloat()..Prefs.MAX_A4.toFloat(),
            steps = (Prefs.MAX_A4 - Prefs.MIN_A4).toInt() - 1,
            colors = SliderDefaults.colors(
                activeTrackColor = scheme.primary, inactiveTrackColor = OrbitTunerTheme.colors.line,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
            thumb = {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(scheme.surface).padding(4.dp)
                        .clip(CircleShape).background(scheme.onBackground),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state, modifier = Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                    colors = SliderDefaults.colors(
                        activeTrackColor = scheme.primary, inactiveTrackColor = OrbitTunerTheme.colors.line,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                    ),
                )
            },
            modifier = Modifier.semantics { stateDescription = "${fmtHz(shown)} hertz" },
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text("415", style = MaterialTheme.typography.labelSmall, color = OrbitTunerTheme.colors.faint)
            Spacer(Modifier.weight(1f))
            Text("440", style = MaterialTheme.typography.labelSmall, color = OrbitTunerTheme.colors.faint)
            Spacer(Modifier.weight(1.04f))
            Text("466", style = MaterialTheme.typography.labelSmall, color = OrbitTunerTheme.colors.faint)
        }
    }
}

@Composable
private fun StepButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(OrbitTunerTheme.colors.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol, fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else OrbitTunerTheme.colors.faint,
        )
    }
}

@Composable
private fun SongViewCard(vm: SettingsViewModel) {
    val instrument by vm.instrument.collectAsStateWithLifecycle()
    val keepOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    SegmentedRow("Chord diagrams", "Also changes when you switch in a diagram", DiagramInstrument.entries, instrument, label = { it.name }, onSelect = vm::setInstrument)
    Divider()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { vm.setKeepScreenOn(!keepOn) }.padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RowTitle("Keep screen on", "While a song is open. Otherwise only while it auto-scrolls.", Modifier.weight(1f))
        Switch(
            keepOn, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedTrackColor = MaterialTheme.colorScheme.background, uncheckedBorderColor = OrbitTunerTheme.colors.line,
                uncheckedThumbColor = OrbitTunerTheme.colors.faint,
            ),
        )
    }
}

@Composable
private fun AppearanceCard(vm: SettingsViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    SegmentedRow("Theme", null, ThemeMode.entries, theme, label = { if (it == ThemeMode.System) "Match system" else it.name }, onSelect = vm::setTheme)
}

// ---------------------------------------------------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------------------------------------------------

private class Licence(val name: String, val use: String, val licence: String, val asset: String?)

private val LICENCES = listOf(
    Licence("chords-db", "Chord diagrams, by David Rubert", "MIT", "chords/CHORDS-LICENSE.txt"),
    Licence("fractional-indexing", "Playlist ordering, by Rocicorp", "MIT", "licenses/fractional-indexing-MIT.txt"),
    Licence("Literata", "Lyrics and titles, by TypeTogether", "SIL Open Font License 1.1", "licenses/Literata-OFL.txt"),
    Licence("Schibsted Grotesk", "Interface and chords, by Schibsted", "SIL Open Font License 1.1", "licenses/SchibstedGrotesk-OFL.txt"),
)

@Composable
private fun AboutCard(vm: SettingsViewModel) {
    val oc = OrbitTunerTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppMark()
        Column(Modifier.weight(1f)) {
            Text("OrbitTuner", fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("Version ${vm.version}. Tuner, chords and setlists.", style = MaterialTheme.typography.bodyMedium, color = oc.muted)
        }
    }
    Divider()
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Hide licences" else "Show licences") { open = !open }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTitle("Open-source licences", "Libraries and fonts OrbitTuner is built with", Modifier.weight(1f))
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = oc.faint)
    }
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column { LICENCES.forEach { LicenceRow(it) } }
    }
}

@Composable
private fun LicenceRow(l: Licence) {
    val oc = OrbitTunerTheme.colors
    val context = LocalContext.current
    var expanded by rememberSaveable(l.name) { mutableStateOf(false) }
    Divider()
    Column(
        Modifier.fillMaxWidth().clickable(enabled = l.asset != null, onClickLabel = "Read the licence") { expanded = !expanded }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(l.use, style = MaterialTheme.typography.bodySmall, color = oc.muted)
            }
            Text(
                l.licence, style = MaterialTheme.typography.labelMedium, color = oc.muted,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (expanded && l.asset != null) {
            val text = remember(l.asset) {
                runCatching { context.assets.open(l.asset).bufferedReader().use { it.readText() } }.getOrDefault("")
            }
            Text(
                text.trim(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = oc.muted,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background).padding(12.dp),
            )
        }
    }
}

/** The brand ring, small (the full logo lives in the launcher icon). */
@Composable
private fun AppMark() {
    androidx.compose.foundation.Canvas(Modifier.size(48.dp)) {
        val u = size.width / 48f
        drawRoundRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF241A6B), Color(0xFF0A0820))),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(14 * u, 14 * u),
        )
        drawCircle(
            androidx.compose.ui.graphics.Brush.sweepGradient(dev.avijit.orbittuner.ui.theme.BrandRing + dev.avijit.orbittuner.ui.theme.BrandRing.first()),
            radius = 13 * u, style = androidx.compose.ui.graphics.drawscope.Stroke(4.5f * u),
        )
        drawCircle(Color(0xFFFFC48F), 3.4f * u, androidx.compose.ui.geometry.Offset(33.5f * u, 14.5f * u))
    }
}
