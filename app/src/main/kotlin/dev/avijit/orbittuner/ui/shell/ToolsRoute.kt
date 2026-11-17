package dev.avijit.orbittuner.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.avijit.orbittuner.AppGraph
import dev.avijit.orbittuner.backing.BackingRoute
import dev.avijit.orbittuner.metronome.MetronomeRoute
import dev.avijit.orbittuner.settings.ToolsPane
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.launch

/**
 * The Tools tab: a "Metronome · Backing tracks" switch over the two panes, opening on the one used last. Each pane
 * stops its own player when it leaves the screen, so switching stops whatever was playing.
 */
@Composable
fun ToolsRoute(graph: AppGraph) {
    val saved by graph.prefs.toolsPane.collectAsState(initial = null)
    var chosen by rememberSaveable { mutableStateOf<ToolsPane?>(null) }
    val pane = chosen ?: saved
    // The app scope, so leaving the tab right after a tap can't cancel the save.
    ToolsFrame(pane, onPane = { p ->
        chosen = p
        graph.appScope.launch { graph.prefs.setToolsPane(p) }
    }) {
        // Nothing until the saved choice is read (a few ms), so the wrong pane never flashes or starts.
        when (pane) {
            ToolsPane.Metronome -> MetronomeRoute(graph, heading = false)
            ToolsPane.Backing -> BackingRoute(graph, Modifier.fillMaxSize())
            null -> Unit
        }
    }
}

/** The switch over a pane; split out so the hub can be rendered without an [AppGraph]. */
@Composable
internal fun ToolsFrame(pane: ToolsPane?, onPane: (ToolsPane) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
            PaneSwitch(pane, Modifier.widthIn(max = 480.dp).fillMaxWidth(), onPane)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

private val LABELS = mapOf(ToolsPane.Metronome to "Metronome", ToolsPane.Backing to "Backing tracks")

@Composable
private fun PaneSwitch(pane: ToolsPane?, modifier: Modifier, onPane: (ToolsPane) -> Unit) {
    val oc = OrbitTunerTheme.colors
    Row(modifier.selectableGroup().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(4.dp)) {
        ToolsPane.entries.forEach { p ->
            val on = p == pane
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) oc.raised else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onPane(p) })
                    .testTag("tools-" + p.name.lowercase()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    LABELS.getValue(p), style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onBackground else oc.muted,
                )
            }
        }
    }
}
