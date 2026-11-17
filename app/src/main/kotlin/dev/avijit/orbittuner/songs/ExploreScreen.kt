package dev.avijit.orbittuner.songs

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbittuner.net.CategoryFacet
import dev.avijit.orbittuner.ui.theme.Literata
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk

/**
 * Explore, shown while the query is empty: language chips, the top three categories for the user and a category search (from the service's facets), songs on this
 * device, then the browse list for the chosen language and category.
 */
internal fun LazyListScope.exploreContent(
    explore: ExploreUi, selection: Selection, onOpen: (String) -> Unit, onOpenSettings: () -> Unit,
    onLanguage: (String?) -> Unit, onCategory: (String?) -> Unit, onMore: () -> Unit, onRetry: () -> Unit,
) {
    if (explore.facets == FacetsState.Error) item(key = "x-facets-err") {
        NoteWithAction("Couldn't load languages and categories", "Try again", onRetry)
    }
    if (explore.languages.isNotEmpty()) item(key = "x-langs") {
        ChipRow(Modifier.padding(top = 14.dp)) {
            Chip("Any language", on = explore.lang == null) { onLanguage(null) }
            explore.languages.forEach { l -> Chip(languageName(l.code), on = explore.lang == l.code) { onLanguage(l.code) } }
        }
    }
    if (explore.categories.isNotEmpty()) {
        item(key = "x-cat-h") { TierHeader(null, "For you", "") }
        item(key = "x-cat-top") { TopCategories(explore.topCategories, explore.cat, onCategory) }
        item(key = "x-cat-find") { CategoryPicker(explore.categories, explore.cat, onCategory) }
    }
    if (explore.recent.isNotEmpty()) {
        item(key = "x-recent-h") { TierHeader(SongsIcons.Device, "On this device", explore.recent.size.toString()) }
        item(key = "x-recent") { RecentCards(explore.recent, onOpen) }
    }
    val filter = listOfNotNull(explore.lang?.let(::languageName), explore.cat).joinToString(" · ")
    libraryTier(
        explore.list, query = "", selection = selection, onOpen = onOpen, onOpenSettings = onOpenSettings,
        onMore = onMore, onRetry = onRetry, emptyText = "No songs here yet", keyPrefix = "x",
        title = if (filter.isEmpty()) "Library" else "Library · $filter",
    )
}

/**
 * Search-mode filters, never an unbounded list: a language selector, the chosen category (removable), the top
 * categories for the user and a "Category" chip that opens the same picker as Explore.
 */
@Composable
internal fun SearchFilters(explore: ExploreUi, onLanguage: (String?) -> Unit, onCategory: (String?) -> Unit) {
    if (explore.languages.isEmpty() && explore.categories.isEmpty()) return
    var menu by remember { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf(false) }
    Column {
        ChipRow(Modifier.padding(top = 14.dp)) {
            if (explore.languages.isNotEmpty()) Box {
                Chip(explore.lang?.let(::languageName) ?: "Any language", on = true, menu = true) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Any language") }, onClick = { menu = false; onLanguage(null) })
                    explore.languages.forEach { l ->
                        DropdownMenuItem(text = { Text(languageName(l.code)) }, onClick = { menu = false; if (explore.lang != l.code) onLanguage(l.code) })
                    }
                }
            }
            explore.cat?.let { c -> Chip(c, on = true, clearable = true, modifier = Modifier.testTag("category-selected")) { onCategory(null) } }
            explore.topCategories.filter { it.name != explore.cat }.forEach { c ->
                Chip(c.name, on = false, modifier = Modifier.testTag("category-chip-${c.name}")) { onCategory(c.name) }
            }
            if (explore.categories.isNotEmpty()) {
                Chip("Category", on = picker, menu = true, modifier = Modifier.testTag("category-picker-toggle")) { picker = !picker }
            }
        }
        if (picker) CategoryPicker(explore.categories, explore.cat, { picker = false; onCategory(it) }, showSelected = false)
    }
}

@Composable
private fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Chip(label: String, on: Boolean, menu: Boolean = false, clearable: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        // The pill is 34 dp; the touch target is 48.
        modifier.minimumInteractiveComponentSize().height(34.dp).clip(CircleShape)
            .then(if (on) Modifier.background(scheme.onBackground) else Modifier.border(1.dp, oc.line, CircleShape))
            .then(
                // A filter is a checkable chip (like FilterChip); the language selector opens a menu.
                if (menu) Modifier.clickable(role = Role.DropdownList, onClick = onClick)
                else Modifier.selectable(selected = on, role = Role.Checkbox, onClick = onClick),
            )
            .padding(start = 13.dp, end = if (menu || clearable) 8.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = ChipStyle, color = if (on) scheme.background else oc.muted, maxLines = 1)
        if (clearable) Icon(SongsIcons.Clear, contentDescription = "Remove category", tint = scheme.background, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        else if (menu) Icon(SongsIcons.Down, contentDescription = null, tint = if (on) scheme.background else oc.muted, modifier = Modifier.padding(start = 2.dp).size(16.dp))
    }
}

private val ChipStyle = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp)

/** The categories for the user: compact rows on a narrow list, one row of tiles when there is room. */
@Composable
private fun TopCategories(top: List<CategoryFacet>, selected: String?, onCategory: (String?) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        if (maxWidth < WIDE) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                top.forEach { c -> CategoryRow(c.name, c.count, on = selected == c.name) { onCategory(c.name) } }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                top.forEach { c -> CategoryTile(c.name, c.count, on = selected == c.name, Modifier.weight(1f)) { onCategory(c.name) } }
                repeat(TOP_CATEGORIES - top.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val WIDE = 600.dp

@Composable
private fun CategoryRow(name: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(shape)
            .background(Brush.linearGradient(listOf(oc.raised, scheme.surface)))
            .then(if (on) Modifier.border(1.5.dp, scheme.primary, shape) else Modifier)
            .selectable(selected = on, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag("category-$name"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name, style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
            color = if (on) scheme.primary else scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(if (count == 1) "1 song" else "$count songs", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = oc.muted)
    }
}

@Composable
private fun CategoryTile(name: String, count: Int, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.height(80.dp).clip(shape)
            .background(Brush.linearGradient(listOf(oc.raised, scheme.surface)))
            .then(if (on) Modifier.border(1.5.dp, scheme.primary, shape) else Modifier)
            .selectable(selected = on, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("category-$name"),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            name, style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
            color = if (on) scheme.primary else scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(if (count == 1) "1 song" else "$count songs", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = oc.muted)
    }
}

@Composable
private fun RecentCards(rows: List<SongRow>, onOpen: (String) -> Unit) {
    val oc = OrbitTunerTheme.colors
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rows, key = { it.id }) { row ->
            Column(
                Modifier.width(150.dp).height(112.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
                    .clickable(role = Role.Button, onClickLabel = "Open song") { onOpen(row.id) }
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    row.title, style = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.key != null) {
                        Text(row.key, style = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold, fontSize = 13.sp), color = oc.chord)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        row.artist ?: languageName(row.language), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = oc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
