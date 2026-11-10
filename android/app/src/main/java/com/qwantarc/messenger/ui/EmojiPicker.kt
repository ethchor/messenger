package com.qwantarc.messenger.ui

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
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

/**
 * Reacting with any emoji: the quick reactions, then Android's own emoji
 * picker as it is (its sections, recents and skin tones).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val t = tones()
    fun pick(emoji: String) {
        EmojiCatalog.used(context, emoji)
        onPick(emoji)
        onDismiss()
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.home,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("❤️", "😂", "😮", "😢", "🙏", "👍").forEach { emoji ->
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(t.surface).clickable { pick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 24.sp) }
                }
            }
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.emoji2.emojipicker.EmojiPickerView(ctx).apply {
                        emojiGridColumns = 8
                        setOnEmojiPickedListener { item -> pick(item.emoji) }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(380.dp),
            )
        }
    }
}

/** The earlier picker, drawn by us; kept for reference while the system's is in use. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerGridSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    // As designed (and as on the iPhone): quick reactions, search, a tab per
    // category, eight to a row, and skin tones as swatches.
    val context = LocalContext.current
    val t = tones()
    val groups = remember { EmojiCatalog.groups(context) }
    val recent = remember { EmojiCatalog.recent(context) }
    var query by remember { mutableStateOf("") }
    var tone by remember { mutableIntStateOf(EmojiCatalog.tone(context)) }
    var category by remember { mutableIntStateOf(-1) }
    val searching = query.isNotBlank()
    val results = remember(query) { if (searching) EmojiCatalog.search(context, query) else emptyList() }
    val quick = listOf("❤️", "😂", "😮", "😢", "🙏", "👍")
    val swatches = listOf(0xFFFFC83D, 0xFFF7DECE, 0xFFE8BC97, 0xFFC68E6A, 0xFF8D5A3B, 0xFF4E3426)
    val shown: List<Pair<String, String?>> = when {
        searching -> results.map { it.inTone(tone) to it.name }
        category == -1 -> (recent.ifEmpty { quick }).map { it to EmojiCatalog.name(context, it) }
        else -> groups.getOrNull(category)?.entries?.map { it.inTone(tone) to it.name }.orEmpty()
    }
    val heading = when {
        searching -> "Results"
        category == -1 -> "Recent"
        else -> groups.getOrNull(category)?.name.orEmpty()
    }

    fun pick(emoji: String) {
        EmojiCatalog.used(context, emoji)
        onPick(emoji)
        onDismiss()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onDismiss),
        ) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(t.home)
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}
                    .navigationBarsPadding().imePadding().padding(top = 10.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(t.line))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    quick.forEach { emoji ->
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(t.surface).clickable(role = Role.Button) { pick(emoji) }
                                .semantics { contentDescription = EmojiCatalog.name(context, emoji) ?: emoji },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 26.sp) }
                    }
                }
                ComposeSearchField(query, { query = it }, placeholder = "Search emoji")
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    (listOf(-1 to ("🕘" to "Recent")) + groups.mapIndexed { i, g -> i to (g.label to g.name) }).forEach { (id, pair) ->
                        val on = category == id && !searching
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(if (on) t.surface else Color.Transparent)
                                .clickable(role = Role.Tab) { query = ""; category = id }
                                .semantics { contentDescription = pair.second; selected = on }
                                .graphicsLayer { alpha = if (on) 1f else 0.6f },
                            contentAlignment = Alignment.Center,
                        ) { Text(pair.first, fontSize = 18.sp) }
                    }
                }
                Text(heading.uppercase(), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = t.secondary, modifier = Modifier.padding(horizontal = 20.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(8), modifier = Modifier.height(236.dp).padding(horizontal = 12.dp)) {
                    if (shown.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("No emoji called “$query”", textAlign = TextAlign.Center, fontSize = 14.sp, color = t.secondary, modifier = Modifier.padding(vertical = 30.dp))
                        }
                    }
                    items(shown.size) { i ->
                        val (emoji, name) = shown[i]
                        Box(
                            Modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).clickable { pick(emoji) }.semantics { contentDescription = name ?: emoji },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 27.sp) }
                    }
                }
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Skin tone", fontSize = 12.5.sp, color = t.secondary)
                    swatches.forEachIndexed { index, colour ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(Color(colour))
                                .border(if (tone == index) 3.dp else 1.5.dp, if (tone == index) t.text else t.line, CircleShape)
                                .clickable(role = Role.RadioButton) { tone = index; EmojiCatalog.setTone(context, index) }
                                .semantics { contentDescription = "${ToneNames[index]} skin tone"; selected = tone == index },
                        )
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

/** The chips on a message's corner: cream, one per emoji with counts, and one to add another. */
@Composable
fun ReactionChips(
    reactions: List<ReactionSummary>,
    onToggle: (emoji: String, mine: Boolean) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheet = sheetColor()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        reactions.forEach { reaction ->
            Text(
                if (reaction.count > 1u) "${reaction.emoji} ${reaction.count}" else reaction.emoji,
                color = Palette.Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (reaction.mine) Palette.Marigold else Palette.Cream)
                    .border(2.5.dp, sheet, RoundedCornerShape(50))
                    .clickable { onToggle(reaction.emoji, reaction.mine) }
                    .semantics { contentDescription = "${reaction.emoji} from ${reaction.usernames.joinToString(", ")}" }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            "+",
            color = Palette.Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Palette.Cream)
                .border(2.5.dp, sheet, RoundedCornerShape(50))
                .clickable(onClick = onMore)
                .semantics { contentDescription = "Add a reaction" }
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}
