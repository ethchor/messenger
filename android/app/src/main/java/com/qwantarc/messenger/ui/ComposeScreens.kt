package com.qwantarc.messenger.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.ConversationSummary
import com.qwantarc.messenger.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// New chat and new group, as designed (R-More), the same as on the iPhone
// (ComposeScreens.swift).

/** The colours a group is made in. The core keeps the name. */
enum class GroupColour(val id: String, val label: String, val tint: Color, val deep: Color, val card: Color) {
    Rose("rose", "Rose", Color(0xFFF9DDE3), Color(0xFF8A2846), Color(0xFFF4A6D0)),
    Blue("blue", "Blue", Color(0xFFDCE6FA), Color(0xFF26457E), Color(0xFFB9D3FF)),
    Green("green", "Green", Color(0xFFD7F3E2), Color(0xFF1E6B44), Color(0xFF5DC584)),
    Marigold("marigold", "Marigold", Color(0xFFFCEFC6), Color(0xFF8A5A00), Color(0xFFFFD23F)),
    Lilac("lilac", "Lilac", Color(0xFFEADFF8), Color(0xFF5C3886), Color(0xFFC9BDFF)),
    Graphite("graphite", "Graphite", Color(0xFFE3E6EB), Color(0xFF3A3F4A), Color(0xFFD9DCE2));

    companion object {
        fun byId(id: String?): GroupColour? = entries.firstOrNull { it.id == id }
    }
}

/** A group's initials: the first letter of its first two words. */
fun groupInitials(title: String): String =
    title.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "G" }

/** Usernames as the server takes them. */
fun isUsername(text: String) = Regex("^[a-z][a-z0-9_.]{2,31}$").matches(text)

private fun cleanName(query: String) = query.trim().lowercase().removePrefix("@")

/** A full-screen page over everything, like the iPhone's sheets. */
@Composable
fun FullScreen(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(tones().home).statusBarsPadding().navigationBarsPadding().imePadding(), content = content)
    }
}

/** A screen's top: a round back button, a centred title, and maybe a button. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val t = tones()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).border(1.5.dp, t.text, CircleShape)
                .clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyphs.Back, contentDescription = null, tint = t.text, modifier = Modifier.size(18.dp)) }
        Text(
            title, style = displayStyle(22.sp), color = t.text, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Box(Modifier.widthIn(min = 44.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

/** The search field of the compose screens. */
@Composable
fun ComposeSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String = "Search or type a @username") {
    val t = tones()
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(46.dp).clip(CircleShape).background(t.surface)
            .border(1.5.dp, t.line, CircleShape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Glyphs.Search, contentDescription = null, tint = t.secondary, modifier = Modifier.size(17.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = t.secondary, fontSize = 16.sp, maxLines = 1)
            BasicTextField(
                value, onValueChange, singleLine = true,
                textStyle = TextStyle(color = t.text, fontSize = 16.sp),
                cursorBrush = SolidColor(t.text),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
    }
}

/** Faint dots fading in from the top left, on coloured cards. */
fun Modifier.dotTexture(): Modifier = drawBehind {
    val gap = 18.dp.toPx()
    val r = 1.6.dp.toPx()
    var x = gap / 2
    while (x < size.width) {
        var y = gap / 2
        while (y < size.height) {
            val fade = (((x / size.width + y / size.height) / 2f) - 0.25f).coerceIn(0f, 0.6f) / 0.6f
            if (fade > 0f) drawCircle(Palette.Ink.copy(alpha = 0.11f * fade), r, Offset(x, y))
            y += gap
        }
        x += gap
    }
}

/** A big coloured card with a shape: New group, Invite a friend. */
@Composable
fun ShapeCard(title: String, detail: String, colour: Color, shape: ShapeKind, modifier: Modifier = Modifier, delayMs: Int = 50, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.height(136.dp).pressScale(source).clip(RoundedCornerShape(26.dp)).background(colour).dotTexture()
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
    ) {
        ShapeSticker(shape, Modifier.align(Alignment.TopEnd).padding(10.dp), size = 50.dp, delayMs = delayMs)
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = displayStyle(21.sp), color = Palette.Ink, maxLines = 2)
            Text(detail, fontSize = 13.sp, color = Palette.Ink.copy(alpha = 0.78f), maxLines = 2)
        }
    }
}

/** A person's stamp with the design's dark offset shadow. */
@Composable
fun LiftedAvatar(username: String, size: Int = 66) {
    val shape = RoundedCornerShape((size / 3f).dp)
    Box {
        Box(Modifier.padding(top = 4.dp).size(size.dp).background(Palette.Ink, shape))
        Box(Modifier.size(size.dp).clip(shape).border(2.dp, Palette.Ink, shape)) { Avatar(username, size = size, stamp = true) }
    }
}

/** A round tick: marigold with a dark edge when picked. */
@Composable
fun PickTick(on: Boolean) {
    val t = tones()
    val fill by animateColorAsState(if (on) Palette.Marigold else Color.Transparent, tween(200), label = "tick")
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(fill).border(2.dp, if (on) Palette.Ink else t.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(Glyphs.Checked, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(14.dp)) }
}

/** A dark or grey capsule: Next, Say hi. */
@Composable
fun PillButton(label: String, enabled: Boolean = true, height: Dp = 36.dp, fontSize: Int = 14, working: Boolean = false, onClick: () -> Unit) {
    val t = tones()
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.height(height).pressScale(source).clip(CircleShape).background(if (enabled) Palette.Ink else t.line)
            .clickable(interactionSource = source, indication = null, enabled = enabled && !working, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (working) CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Cream, strokeWidth = 2.dp)
        else Text(label, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, color = if (enabled) Palette.Cream else t.secondary)
    }
}

/** The big marigold button at the bottom of a screen. */
@Composable
fun BigButton(label: String, enabled: Boolean, working: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = tones()
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().height(56.dp).pressScale(source).clip(CircleShape).background(if (enabled) Palette.Marigold else t.line)
            .clickable(interactionSource = source, indication = null, enabled = enabled && !working, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (working) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Ink, strokeWidth = 2.dp)
        else Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (enabled) Palette.Ink else t.secondary)
    }
}

private sealed interface Lookup {
    data object Idle : Lookup
    data object Checking : Lookup
    data class Found(val name: String) : Lookup
    data class Missing(val name: String) : Lookup
    data object Unknown : Lookup
}

/** New chat: two big cards, a live username lookup, Recent and everyone. */
@Composable
fun NewChatScreen(state: UiState, model: AppViewModel, onDismiss: () -> Unit) {
    var group by remember { mutableStateOf(false) }
    FullScreen(onDismiss) {
        AnimatedContent(group, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) }, label = "compose") { inGroup ->
            if (inGroup) NewGroupContent(state, model, onBack = { group = false }, onDone = onDismiss)
            else NewChatContent(state, model, onDismiss, onGroup = { group = true })
        }
    }
}

/** New group on its own, from the + menu. */
@Composable
fun NewGroupScreen(state: UiState, model: AppViewModel, onDismiss: () -> Unit) {
    FullScreen(onDismiss) { NewGroupContent(state, model, onBack = onDismiss, onDone = onDismiss) }
}

@Composable
private fun NewChatContent(state: UiState, model: AppViewModel, onDismiss: () -> Unit, onGroup: () -> Unit) {
    val t = tones()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var lookup by remember { mutableStateOf<Lookup>(Lookup.Idle) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val me = state.account?.username ?: ""
    val name = cleanName(query)
    val people = state.conversations.filter { it.kind == ConversationKind.DIRECT && !it.blocked }
    val matching = if (name.isEmpty()) people else people.filter { it.title.lowercase().contains(name) }

    LaunchedEffect(name) {
        error = null
        if (!isUsername(name) || name == me) { lookup = Lookup.Idle; return@LaunchedEffect }
        if (people.any { it.title == name }) { lookup = Lookup.Found(name); return@LaunchedEffect }
        lookup = Lookup.Checking
        delay(350)
        val server = state.account?.serverUrl ?: return@LaunchedEffect
        lookup = when (checkUsername(name, server)) {
            Availability.Taken -> Lookup.Found(name)
            Availability.Free -> Lookup.Missing(name)
            else -> Lookup.Unknown
        }
    }

    fun open(chat: ConversationSummary) {
        model.openChat(chat)
        onDismiss()
    }

    fun start(username: String) {
        people.firstOrNull { it.title == username && it.active }?.let { open(it); return }
        working = true
        scope.launch {
            try {
                model.startDirectChat(username)
                onDismiss()
            } catch (e: Exception) {
                error = e.userMessage
            } finally {
                working = false
            }
        }
    }

    BackHandler(onBack = onDismiss)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("New chat", onDismiss)
        ComposeSearchField(query, { query = it })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeCard("New group", "Friends, family, the hike crew", Palette.Green, ShapeKind.Clover, Modifier.weight(1f), onClick = onGroup)
                ShapeCard("Invite a friend", "Share @$me so they find you", Palette.Marigold, ShapeKind.Heart, Modifier.weight(1f), delayMs = 120) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Find me on Messenger: @$me")
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
            AnimatedContent(lookup, transitionSpec = { (scaleIn(initialScale = 0.6f, animationSpec = spring(dampingRatio = 0.6f)) + fadeIn()) togetherWith fadeOut() }, label = "lookup") { found ->
                LookupCard(found, name, working, error) { start(it) }
            }
            if (matching.isNotEmpty()) {
                Text(if (name.isEmpty()) "Recent" else "Matches", style = displayStyle(20.sp), color = t.text, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    matching.take(10).forEach { chat ->
                        Column(
                            Modifier.clickable(role = Role.Button) { open(chat) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LiftedAvatar(chat.title)
                            Text(chat.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Text("Everyone you chat with", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.secondary, modifier = Modifier.padding(start = 22.dp))
                Box(Modifier.padding(horizontal = 16.dp)) {
                    KitCard(radius = 24.dp) {
                        matching.sortedBy { it.title }.forEach { chat ->
                            Row(
                                Modifier.fillMaxWidth().clickable { open(chat) }.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Avatar(chat.title, size = 42, stamp = true)
                                Column(Modifier.weight(1f)) {
                                    Text(chat.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1)
                                    Text("@${chat.title}", fontSize = 13.sp, color = t.secondary)
                                }
                                Text(shortTime(chat.lastActivityMs), fontSize = 12.5.sp, color = t.secondary)
                            }
                        }
                    }
                }
            }
            Text(
                "No phone numbers, no contact upload. Friends find each other by the exact username they share.",
                fontSize = 13.sp, lineHeight = 18.5.sp, color = t.secondary, modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
    }
}

@Composable
private fun LookupCard(lookup: Lookup, name: String, working: Boolean, error: String?, onStart: (String) -> Unit) {
    val t = tones()
    val shape = RoundedCornerShape(26.dp)
    when (lookup) {
        Lookup.Checking -> Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(t.surface).border(1.dp, Kit.cardLine(), shape).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(26.dp), color = t.text, strokeWidth = 3.dp)
            Text("Looking for @$name…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text)
        }
        is Lookup.Found -> Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(shape).background(Palette.Marigold).border(2.dp, Palette.Ink, shape).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LiftedAvatar(lookup.name, size = 56)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("@${lookup.name}", style = displayStyle(20.sp), color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(error ?: "Found! Say hi to start an encrypted chat", fontSize = 13.sp, color = Palette.Ink.copy(alpha = 0.8f))
                }
                PillButton("Say hi", height = 40.dp, fontSize = 15, working = working) { onStart(lookup.name) }
            }
            ShapeSticker(ShapeKind.Sparkle, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 98.dp), size = 26.dp, delayMs = 200)
        }
        is Lookup.Missing -> Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(t.surface).border(1.dp, Kit.cardLine(), shape).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShapeSticker(ShapeKind.Moon, size = 52.dp, delayMs = 50)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("No @${lookup.name} here yet", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.text)
                Text("Usernames are exact, so check the spelling with them. Or send them yours.", fontSize = 13.sp, lineHeight = 17.5.sp, color = t.text.copy(alpha = 0.75f))
            }
        }
        Lookup.Unknown -> Text(
            "Can't check usernames right now. You can still try: Say hi starts the chat if they're there.",
            fontSize = 13.sp, color = t.secondary, modifier = Modifier.padding(horizontal = 22.dp),
        )
        Lookup.Idle -> Spacer(Modifier.height(0.dp))
    }
}

/** New group: pick people, then a name and a colour. */
@Composable
private fun NewGroupContent(state: UiState, model: AppViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val t = tones()
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf(GroupColour.Green) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val me = state.account?.username ?: ""
    val typed = cleanName(query)
    val all = (state.conversations.filter { it.kind == ConversationKind.DIRECT && !it.blocked }.map { it.title } + picked).distinct()
    val shown = if (typed.isEmpty()) all else all.filter { it.contains(typed) }
    val people = if (isUsername(typed) && typed != me && typed !in shown) shown + typed else shown

    fun toggle(name: String) {
        if (name in picked) picked.remove(name) else picked.add(name)
        if (isUsername(typed) && typed == name) query = ""
    }

    fun create() {
        val name = title.trim()
        if (name.isEmpty() || working) return
        working = true
        scope.launch {
            try {
                model.createGroup(name, picked.toList(), colour.id)
                onDone()
            } catch (e: Exception) {
                error = e.userMessage
            } finally {
                working = false
            }
        }
    }

    BackHandler { if (naming) naming = false else onBack() }
    if (!naming) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("New group", onBack) { PillButton("Next", enabled = picked.isNotEmpty()) { naming = true } }
            // Lavender: who's in so far, each face with an ×.
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 14.dp).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 118.dp).clip(RoundedCornerShape(28.dp)).background(Palette.VanishAccent).dotTexture().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (picked.isEmpty()) "Who’s in?" else "${picked.size} picked · ${picked.size + 1} with you",
                        style = displayStyle(26.sp), color = Palette.Ink,
                    )
                    if (picked.isEmpty()) {
                        Text("Pick at least one friend. You can always add more later.", fontSize = 14.sp, lineHeight = 19.sp, color = Palette.Ink, modifier = Modifier.padding(end = 70.dp))
                    } else {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            picked.forEach { name ->
                                Box(Modifier.clickable(role = Role.Button) { toggle(name) }.semantics { contentDescription = "Remove $name" }) {
                                    LiftedAvatar(name, size = 50)
                                    Box(
                                        Modifier.align(Alignment.TopEnd).offset(5.dp, (-5).dp).size(20.dp).clip(CircleShape).background(Palette.Ink)
                                            .border(2.dp, Palette.VanishAccent, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("×", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                                }
                            }
                        }
                    }
                }
                ShapeSticker(ShapeKind.Clover, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp), size = 46.dp, delayMs = 50)
                ShapeSticker(ShapeKind.Sparkle, Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 66.dp), size = 22.dp, delayMs = 200, phase = -1f)
            }
            ComposeSearchField(query, { query = it })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 30.dp)) {
                KitCard(radius = 24.dp) {
                    people.forEach { name ->
                        val on = name in picked
                        Row(
                            Modifier.fillMaxWidth().background(if (on) (if (isDark()) Color(0xFF2A2615) else Color(0xFFFFF7DA)) else Color.Transparent)
                                .clickable(role = Role.Checkbox) { toggle(name) }.semantics { selected = on }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Avatar(name, size = 44, stamp = true)
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                                Text("@$name", fontSize = 13.sp, color = t.secondary)
                            }
                            PickTick(on)
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader("Name it", { naming = false })
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val tint by animateColorAsState(colour.tint, tween(250), label = "tint")
                Box {
                    Box(Modifier.padding(top = 6.dp).size(104.dp).background(Palette.Ink, RoundedCornerShape(34.dp)))
                    Box(
                        Modifier.size(104.dp).clip(RoundedCornerShape(34.dp)).background(tint).border(2.dp, Palette.Ink, RoundedCornerShape(34.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(groupInitials(title.ifBlank { "New group" }), style = displayStyle(38.sp, bold = true), color = colour.deep) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupColour.entries.forEach { option ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(option.tint)
                                .border(if (option == colour) 3.dp else 1.5.dp, if (option == colour) t.text else t.line, CircleShape)
                                .clickable(role = Role.RadioButton) { colour = option }
                                .semantics { contentDescription = option.label; selected = option == colour },
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Group name", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.secondary)
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp)).background(t.surface)
                        .border(1.5.dp, t.text, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (title.isEmpty()) Text("Sunday Hike Crew", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = t.secondary)
                    BasicTextField(
                        title, { title = it }, singleLine = true,
                        textStyle = TextStyle(color = t.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(t.text),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { create() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Group name" },
                    )
                }
            }
            Text("${picked.size + 1} members, with you", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.secondary, modifier = Modifier.padding(start = 22.dp, top = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                picked.forEach { name ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Avatar(name, size = 48, stamp = true)
                        Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, softWrap = false)
                    }
                }
            }
            Text(
                error ?: "Anyone in the group can add or remove people. The name and members are encrypted: the server never sees them.",
                fontSize = 13.sp, lineHeight = 18.5.sp, color = if (error == null) t.secondary else t.danger,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Spacer(Modifier.weight(1f))
            BigButton("Create group", enabled = title.isNotBlank(), working = working, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { create() }
        }
    }
}
