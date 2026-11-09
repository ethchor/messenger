package com.qwantarc.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.ConversationSummary
import com.qwantarc.messenger.core.MemberView
import com.qwantarc.messenger.userMessage
import kotlinx.coroutines.launch

// Managing a group, as designed (R-More "Manage a group"), as on the iPhone
// (GroupScreens.swift).

/** The colour a group was made in, if any. */
val ConversationSummary.groupColour: GroupColour? get() = GroupColour.byId(colour)

/** The group's card: its stamp, its name (tap the pencil to rename), and faces. */
@Composable
fun GroupHeroCard(conversation: ConversationSummary, members: List<MemberView>, model: AppViewModel) {
    val colour = conversation.groupColour ?: GroupColour.Green
    val scope = rememberCoroutineScope()
    val showToast = LocalShowToast.current
    var renaming by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(conversation.title) }
    var error by remember { mutableStateOf<String?>(null) }
    val bounce = remember { Animatable(1f) }
    var boing by remember { mutableStateOf(0) }
    LaunchedEffect(boing) {
        if (boing == 0) return@LaunchedEffect
        bounce.animateTo(0.84f, tween(90))
        bounce.animateTo(1.1f, tween(120))
        bounce.animateTo(1f, spring(dampingRatio = 0.5f))
    }

    fun save() {
        val title = draft.trim()
        if (title.isEmpty()) return
        if (title == conversation.title) { renaming = false; return }
        scope.launch {
            try {
                model.renameGroup(conversation.id, title)
                error = null
                renaming = false
                showToast("Renamed. Everyone sees “$title”")
            } catch (e: Exception) {
                error = e.userMessage
            }
        }
    }

    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(colour.card).dotTexture()
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { boing++ },
            ) {
                Box(Modifier.padding(top = 7.dp).size(100.dp).background(Palette.Ink, RoundedCornerShape(33.dp)))
                Box(
                    Modifier.size(100.dp).clip(RoundedCornerShape(33.dp)).background(Color.White).border(2.dp, Palette.Ink, RoundedCornerShape(33.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(groupInitials(conversation.title), style = displayStyle(36.sp, bold = true), color = colour.deep) }
            }
            if (renaming) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                            .border(2.dp, Palette.Ink, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            draft, { draft = it }, singleLine = true,
                            textStyle = TextStyle(color = Palette.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                            cursorBrush = SolidColor(Palette.Ink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Group name" },
                        )
                    }
                    Box(
                        Modifier.height(46.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Ink)
                            .clickable(role = Role.Button) { save() }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Save", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Cream) }
                }
            } else {
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = conversation.active, role = Role.Button) {
                        draft = conversation.title
                        renaming = true
                    }.semantics { contentDescription = "${conversation.title}. Rename" }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(conversation.title, style = displayStyle(27.sp), color = Palette.Ink, textAlign = TextAlign.Center)
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                        Icon(Glyphs.Pencil, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(14.dp))
                    }
                }
            }
            error?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.padding(start = 8.dp)) {
                    members.take(6).forEach { member ->
                        Box(Modifier.offset(x = (-8).dp).clip(RoundedCornerShape(10.dp)).border(2.dp, Palette.Ink, RoundedCornerShape(10.dp))) {
                            Avatar(member.username, size = 30, stamp = true)
                        }
                    }
                }
                Text("Group · ${members.size} members", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            }
        }
        ShapeSticker(ShapeKind.Clover, Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 16.dp), size = 44.dp, delayMs = 80, tappable = true)
        ShapeSticker(ShapeKind.Sparkle, Modifier.align(Alignment.TopStart).padding(top = 26.dp, start = 22.dp), size = 28.dp, delayMs = 180, tappable = true)
        ShapeSticker(ShapeKind.Bolt, Modifier.align(Alignment.BottomEnd).padding(bottom = 54.dp, end = 30.dp), size = 24.dp, delayMs = 300, phase = -1f, tappable = true)
    }
}

/** "Who's in": Add people, then everyone, you last, each with Remove. */
@Composable
fun GroupMembersCard(members: List<MemberView>, active: Boolean, onAdd: () -> Unit, onRemove: (MemberView) -> Unit) {
    val t = tones()
    Text("Who's in", style = displayStyle(20.sp), color = t.text, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    KitCard(radius = 24.dp) {
        if (active) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onAdd).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Marigold).border(1.5.dp, Palette.Ink, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyphs.Plus, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(20.dp)) }
                Text("Add people", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            }
        }
        members.sortedBy { it.isMe }.forEach { member ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(member.username, size = 42, stamp = true)
                Column(Modifier.weight(1f)) {
                    Text(if (member.isMe) "You" else member.username, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                    Text("@${member.username}", fontSize = 13.sp, color = t.secondary)
                }
                if (active && !member.isMe) {
                    Text(
                        "Remove", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.danger,
                        modifier = Modifier.clip(CircleShape).border(1.5.dp, t.line, CircleShape)
                            .clickable(role = Role.Button) { onRemove(member) }
                            .semantics { contentDescription = "Remove ${member.username}" }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
    Text(
        "There are no admins: anyone in the group can add or remove people, and everyone sees who did it.",
        fontSize = 13.sp, lineHeight = 18.5.sp, color = t.secondary, modifier = Modifier.padding(horizontal = 6.dp),
    )
}

/** A sheet from the bottom asking to confirm something that can't be undone. */
@Composable
fun ConfirmSheet(title: String, body: String, button: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val t = tones()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss)) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(t.home)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                    .navigationBarsPadding().padding(top = 10.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(t.line))
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = displayStyle(22.sp), color = t.text)
                    Text(body, fontSize = 15.sp, lineHeight = 21.sp, color = t.secondary)
                }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(Color(0xFFD92D20)).clickable(role = Role.Button, onClick = onConfirm),
                        contentAlignment = Alignment.Center,
                    ) { Text(button, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    Box(
                        Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text) }
                }
            }
        }
    }
}

/** "Add people" for a group: pick from the people you chat with, or type a username. */
@Composable
fun AddPeopleScreen(conversationId: String, already: List<String>, state: UiState, model: AppViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    val t = tones()
    val scope = rememberCoroutineScope()
    val showToast = LocalShowToast.current
    val picked = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val typed = query.trim().lowercase().removePrefix("@")
    val all = (state.conversations.filter { it.kind == ConversationKind.DIRECT && !it.blocked }.map { it.title }.filter { it !in already } + picked).distinct()
    val shown = if (typed.isEmpty()) all else all.filter { it.contains(typed) }
    val people = if (isUsername(typed) && typed != state.account?.username && typed !in already && typed !in shown) shown + typed else shown

    FullScreen(onDismiss) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Add people", onDismiss) {
                PillButton("Add", enabled = picked.isNotEmpty(), working = working) {
                    working = true
                    scope.launch {
                        try {
                            model.addMembers(conversationId, picked.toList())
                            showToast(if (picked.size == 1) "${picked[0]} was added" else "${picked.size} people were added")
                            onDone()
                        } catch (e: Exception) {
                            error = e.userMessage
                        } finally {
                            working = false
                        }
                    }
                }
            }
            ComposeSearchField(query, { query = it })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(it, fontSize = 13.sp, color = t.danger) }
                KitCard(radius = 24.dp) {
                    people.forEach { name ->
                        val on = name in picked
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) {
                                if (on) picked.remove(name) else picked.add(name)
                                if (typed == name) query = ""
                            }.padding(horizontal = 14.dp, vertical = 10.dp),
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
                if (people.isEmpty()) Text("Type a username to add someone you don't chat with yet.", fontSize = 13.sp, color = t.secondary)
            }
        }
    }
}

/** A group's stamp: its initials on its colour's tint. */
@Composable
fun GroupStamp(title: String, colour: GroupColour?, fallback: Ink, size: Int, stamp: Boolean) {
    val shape = if (stamp) RoundedCornerShape((size / 3f).dp) else CircleShape
    val dark = isDark()
    Box(
        Modifier.size(size.dp).clip(shape).background(colour?.tint ?: fallback.pastel).then(if (stamp) Modifier.border(1.5.dp, tones().stampLine, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(groupInitials(title), style = displayStyle((size * 0.36f).sp, bold = true), color = colour?.deep ?: fallback.color(dark), maxLines = 1)
    }
}
