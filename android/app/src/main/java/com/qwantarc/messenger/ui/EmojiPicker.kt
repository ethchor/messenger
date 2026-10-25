package com.qwantarc.messenger.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qwantarc.messenger.core.ReactionSummary
import kotlinx.coroutines.launch

/** One emoji the picker offers: what it's called, words to find it by, and its five skin tones if it has them. */
class EmojiEntry(val emoji: String, val name: String, val skins: List<String>, val words: List<String>) {
    /** This emoji in [tone] (1 light to 5 dark), or as it is. */
    fun inTone(tone: Int): String = if (tone in 1..5 && skins.size == 5) skins[tone - 1] else emoji
}

class EmojiGroup(val name: String, val label: String, val entries: List<EmojiEntry>)

/**
 * The emoji the picker offers, read once from assets/emoji.tsv (written by
 * scripts/emoji.py), and this device's recent ones and skin tone.
 */
object EmojiCatalog {
    @Volatile private var loaded: List<EmojiGroup>? = null
    private val shortNames = listOf("😀", "👋", "🐻", "🍔", "✈️", "⚽", "💡", "❤️", "🏳️")

    fun groups(context: Context): List<EmojiGroup> = loaded ?: synchronized(this) {
        loaded ?: load(context).also { loaded = it }
    }

    private fun load(context: Context): List<EmojiGroup> {
        val text = runCatching { context.assets.open("emoji.tsv").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return emptyList()
        val groups = mutableListOf<EmojiGroup>()
        var name: String? = null
        var entries = mutableListOf<EmojiEntry>()
        fun finishGroup() {
            name?.let { groups += EmojiGroup(it, shortNames.getOrElse(groups.size) { "🙂" }, entries) }
            entries = mutableListOf()
        }
        for (line in text.lineSequence()) {
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("@") -> {
                    finishGroup()
                    name = line.drop(1)
                }
                else -> {
                    val fields = line.split('\t')
                    if (fields.size < 3) continue
                    val nameWords = fields[1].lowercase().split(' ', ':', ',').filter { it.isNotEmpty() }
                    entries += EmojiEntry(
                        emoji = fields[0],
                        name = fields[1],
                        skins = if (fields.size > 3) fields[3].split(' ') else emptyList(),
                        words = nameWords + fields[2].split(' '),
                    )
                }
            }
        }
        finishGroup()
        return groups
    }

    /** Emoji with a word starting with each word of [query]. */
    fun search(context: Context, query: String): List<EmojiEntry> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return groups(context).asSequence().flatMap { it.entries }
            .filter { entry -> words.all { word -> entry.words.any { it.startsWith(word) } } }
            .take(300)
            .toList()
    }

    fun name(context: Context, emoji: String): String? = groups(context).asSequence().flatMap { it.entries }
        .firstOrNull { it.emoji == emoji || emoji in it.skins }?.name

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("emoji", Context.MODE_PRIVATE)

    /** Picked lately on this device, newest first. */
    fun recent(context: Context): List<String> =
        prefs(context).getString(KEY_RECENT, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    fun used(context: Context, emoji: String) {
        val list = listOf(emoji) + recent(context).filter { it != emoji }
        prefs(context).edit().putString(KEY_RECENT, list.take(24).joinToString("\n")).apply()
    }

    /** The skin tone for emoji that have them: 0 for the default yellow, 1 light to 5 dark. */
    fun tone(context: Context): Int = prefs(context).getInt(KEY_TONE, 0)

    fun setTone(context: Context, tone: Int) = prefs(context).edit().putInt(KEY_TONE, tone).apply()

    /** Forgets the recent ones and the tone, when the account leaves this device. */
    fun reset(context: Context) = prefs(context).edit().clear().apply()

    private const val KEY_RECENT = "recent"
    private const val KEY_TONE = "tone"
}

private val Hands = listOf("✋", "✋🏻", "✋🏼", "✋🏽", "✋🏾", "✋🏿")
private val ToneNames = listOf("Default", "Light", "Medium-light", "Medium", "Medium-dark", "Dark")

/** Any emoji: by group, by name, or one used lately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val groups = remember { EmojiCatalog.groups(context) }
    val recent = remember { EmojiCatalog.recent(context) }
    var query by remember { mutableStateOf("") }
    var tone by remember { mutableIntStateOf(EmojiCatalog.tone(context)) }
    var toneMenu by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val searching = query.isNotBlank()
    val results = remember(query) { if (searching) EmojiCatalog.search(context, query) else emptyList() }
    // Where each group's heading sits in the grid, to jump to it.
    val headings = remember(groups, recent) {
        var index = if (recent.isEmpty()) 0 else recent.size + 1
        groups.map { group -> index.also { index += group.entries.size + 1 } }
    }

    fun pick(emoji: String) {
        EmojiCatalog.used(context, emoji)
        onPick(emoji)
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search emoji") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(
                        onClick = { toneMenu = true },
                        modifier = Modifier.semantics { contentDescription = "Skin tone: ${ToneNames[tone]}" },
                    ) { Text(Hands[tone], fontSize = 22.sp) }
                    DropdownMenu(expanded = toneMenu, onDismissRequest = { toneMenu = false }) {
                        Hands.forEachIndexed { index, hand ->
                            DropdownMenuItem(
                                text = { Text("$hand  ${ToneNames[index]}") },
                                onClick = {
                                    tone = index
                                    EmojiCatalog.setTone(context, index)
                                    toneMenu = false
                                },
                            )
                        }
                    }
                }
            }
            if (!searching) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    groups.forEachIndexed { index, group ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(CircleShape)
                                .clickable { scope.launch { grid.animateScrollToItem(headings[index]) } }
                                .semantics { contentDescription = group.name },
                            contentAlignment = Alignment.Center,
                        ) { Text(group.label, fontSize = 18.sp) }
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(48.dp),
                state = grid,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            ) {
                if (searching) {
                    if (results.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "No emoji found for \"$query\"",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    items(results, key = { it.emoji }) { entry -> EmojiCell(entry.inTone(tone), entry.name) { pick(it) } }
                } else {
                    if (recent.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { GroupHeading("Recently used") }
                        items(recent, key = { "recent $it" }) { emoji -> EmojiCell(emoji, EmojiCatalog.name(context, emoji)) { pick(it) } }
                    }
                    groups.forEach { group ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "group ${group.name}") { GroupHeading(group.name) }
                        items(group.entries, key = { it.emoji }) { entry -> EmojiCell(entry.inTone(tone), entry.name) { pick(it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmojiCell(emoji: String, name: String?, onPick: (String) -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable { onPick(emoji) }
            .semantics { contentDescription = name ?: emoji },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 28.sp)
    }
}

/** The chips under a message: one per emoji, with counts, and one to add another. */
@Composable
fun ReactionChips(
    reactions: List<ReactionSummary>,
    onToggle: (emoji: String, mine: Boolean) -> Unit,
    onMore: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val ink = LocalAppearance.current.ink
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        reactions.forEach { reaction ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (reaction.mine) ink.wash(dark) else incomingBubble(dark),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggle(reaction.emoji, reaction.mine) }
                    .semantics { contentDescription = "${reaction.emoji} from ${reaction.usernames.joinToString(", ")}" },
            ) {
                Text(
                    if (reaction.count > 1u) "${reaction.emoji} ${reaction.count}" else reaction.emoji,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = incomingBubble(dark),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onMore)
                .semantics { contentDescription = "Add a reaction" },
        ) {
            Text(
                "+",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
