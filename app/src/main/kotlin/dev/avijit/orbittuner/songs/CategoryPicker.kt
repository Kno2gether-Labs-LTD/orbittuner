package dev.avijit.orbittuner.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbittuner.net.CategoryFacet
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import dev.avijit.orbittuner.ui.theme.SchibstedGrotesk
import java.text.Normalizer

/** Rows the picker shows before it scrolls. */
internal const val PICKER_ROWS = 8
private val ROW_HEIGHT = 48.dp

/** Lower case, accents removed ("Café" -> "cafe"), trimmed. */
private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
    .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase().trim()

private fun words(s: String) = s.split(' ', '-', '_', '/', '&', ',', '.', '\'', '(', ')').filter { it.isNotEmpty() }

/**
 * Categories matching [query], ignoring case and diacritics: the name starts with it, or any word in the name does
 * ("sang" finds "Rabindra Sangeet"). Name-prefix matches come first, then larger categories. A blank query lists nothing.
 */
fun findCategories(facets: List<CategoryFacet>, query: String): List<CategoryFacet> {
    val q = fold(query)
    if (q.isEmpty()) return emptyList()
    fun rank(c: CategoryFacet): Int? {
        val name = fold(c.name)
        return when {
            name.startsWith(q) -> 0
            words(name).any { it.startsWith(q) } -> 1
            else -> null
        }
    }
    return facets.mapNotNull { c -> rank(c)?.let { it to c } }
        .sortedWith(compareBy<Pair<Int, CategoryFacet>> { it.first }.thenByDescending { it.second.count }.thenBy { it.second.name.lowercase() })
        .map { it.second }
}

/** "Find a category": a field, the matches below it (never the full list), and the chosen category as a removable chip. */
@Composable
internal fun CategoryPicker(categories: List<CategoryFacet>, selected: String?, onSelect: (String?) -> Unit, showSelected: Boolean = true) {
    val oc = OrbitTunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    val matches = findCategories(categories, query)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        if (selected != null && showSelected) {
            Row(
                Modifier.padding(bottom = 8.dp).height(48.dp).clip(CircleShape).background(scheme.onBackground)
                    .clickable(role = Role.Button, onClickLabel = "Remove category") { onSelect(null) }
                    .semantics { contentDescription = "$selected, remove category" }
                    .testTag("category-selected")
                    .padding(start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected, style = PickerChip, color = scheme.background, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Icon(SongsIcons.Clear, contentDescription = null, tint = scheme.background, modifier = Modifier.size(18.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surface).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SongsIcons.Search, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = PickerField.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.weight(1f).testTag("category-search"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Find a category", style = PickerField, color = oc.faint, maxLines = 1)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { query = "" }
                        .semantics { contentDescription = "Clear category search" },
                    contentAlignment = Alignment.Center,
                ) { Icon(SongsIcons.Clear, contentDescription = null, tint = oc.muted, modifier = Modifier.size(18.dp)) }
            }
        }
        if (query.isNotBlank()) {
            if (matches.isEmpty()) {
                Text("No category called “${query.trim()}”", style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = oc.muted, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
            } else {
                Column(
                    Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = ROW_HEIGHT * PICKER_ROWS).clip(RoundedCornerShape(16.dp))
                        .background(scheme.surface).verticalScroll(rememberScrollState()).testTag("category-options"),
                    verticalArrangement = Arrangement.Top,
                ) {
                    matches.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().height(ROW_HEIGHT)
                                .clickable(role = Role.Button) { focus.clearFocus(); query = ""; onSelect(c.name) }
                                .padding(horizontal = 14.dp).testTag("category-option-${c.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.name, style = PickerRow, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(if (c.count == 1) "1 song" else "${c.count} songs", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = oc.muted)
                        }
                    }
                }
            }
        }
    }
}

private val PickerChip = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
private val PickerField = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
private val PickerRow = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
