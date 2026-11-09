package com.qwantarc.messenger.ui

import androidx.compose.ui.draw.blur
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.LocationSharingService
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.SafetyNumber
import com.qwantarc.messenger.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Safety numbers, the recovery key, your own server, blocked people, live
// location setup and the states, as designed (R-More), as on the iPhone
// (SafetyScreens.swift, ManageScreens.swift, StateScreens.swift).

// ---- safety numbers ----

/** Everyone you have a 1:1 chat with, to compare safety numbers. */
@Composable
fun SafetyNumbersScreen(state: UiState, model: AppViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val t = tones()
    val chats = state.conversations.filter { it.kind == ConversationKind.DIRECT && it.active }
        .sortedWith(compareByDescending<com.qwantarc.messenger.core.ConversationSummary> { it.safetyChangedMs ?: 0L }.thenBy { it.title })
    val numbers = remember { mutableStateMapOf<String, SafetyNumber>() }
    LaunchedEffect(chats.map { it.id }) {
        chats.forEach { chat -> if (chat.id !in numbers) runCatching { model.safetyNumber(chat.id) }.getOrNull()?.let { numbers[chat.id] = it } }
    }
    val changed = chats.count { it.safetyChangedMs != null }
    val people = if (chats.size == 1) "1 person" else "${chats.size} people"
    BackHandler(onBack = onBack)
    KitPage("Safety numbers", onBack, back = "Back") {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Palette.Ink).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Is it really them?", style = displayStyle(27.sp), color = Palette.Cream, modifier = Modifier.padding(end = 90.dp))
                Text(
                    "Meet up and compare numbers. If they match, nobody is sitting in the middle of your chat.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFC9C6BC), modifier = Modifier.padding(end = 84.dp),
                )
                Row(
                    Modifier.padding(top = 4.dp).height(28.dp).clip(CircleShape).background(Palette.Marigold).padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Glyphs.Lock, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(12.dp))
                    Text(if (changed == 0) people else "$people · $changed changed", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                }
            }
            ShapeSticker(ShapeKind.Seal, Modifier.align(Alignment.TopEnd).padding(16.dp), size = 64.dp, delayMs = 50, tappable = true)
            ShapeSticker(ShapeKind.Sparkle, Modifier.align(Alignment.TopEnd).padding(top = 82.dp, end = 64.dp), size = 26.dp, delayMs = 200, phase = -1f)
        }
        if (chats.isEmpty()) {
            EmptyState(
                Illustration.Safety,
                title = "No one-to-one chats yet",
                text = "Safety numbers are for chats with one other person. Start one, then compare numbers in person.",
            )
        } else {
            KitCard(radius = 24.dp) {
                chats.forEach { chat ->
                    val number = numbers[chat.id]
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (chat.safetyChangedMs != null) (if (isDark()) Color(0xFF2A2615) else Color(0xFFFFF7DA)) else Color.Transparent)
                            .clickable(role = Role.Button) { onOpen(chat.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Avatar(chat.title, size = 44, stamp = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(chat.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1)
                                chat.safetyChangedMs?.let { at ->
                                    Text(
                                        "Changed " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(at)),
                                        fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Palette.Ink,
                                        modifier = Modifier.background(Palette.Marigold, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            Text(
                                number?.digits?.split(" ")?.take(3)?.joinToString(" ")?.plus(" …") ?: "…",
                                fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, letterSpacing = 0.4.sp, color = t.secondary,
                            )
                        }
                        KitChevron()
                    }
                }
            }
        }
        KitFoot("“Changed” means their number is new since you last chatted, usually because they recovered their account or reset their identity. Check it with them before sharing anything sensitive.")
    }
}

/** One person's safety number: you and them, a QR code, 60 digits, and a scan. */
@Composable
fun SafetyNumberScreen(conversationId: String, title: String, state: UiState, model: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val t = tones()
    var number by remember { mutableStateOf<SafetyNumber?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(conversationId) {
        try { number = model.safetyNumber(conversationId) } catch (e: Exception) { error = e.userMessage }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) scanning = true }
    val card = Ink.byId(state.profiles[title]?.ink)?.card ?: Palette.TileSky
    val me = state.account?.username ?: ""
    BackHandler { if (scanning) scanning = false else onBack() }
    KitPage("Safety number", onBack, back = "Back") {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
            Face(me, "You")
            Box(Modifier.width(110.dp).height(64.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(3.dp)) {
                    drawLine(
                        t.text.copy(alpha = 0.5f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                    )
                }
                ShapeSticker(if (result == true) ShapeKind.Heart else ShapeKind.Seal, size = 34.dp, delayMs = 100)
            }
            Face(title, title)
        }
        val n = number
        when {
            n != null -> Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Box(Modifier.matchParentSize().padding(top = 6.dp).background(Palette.Ink, RoundedCornerShape(30.dp)))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(card).border(2.dp, Palette.Ink, RoundedCornerShape(30.dp)).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val bitmap = remember(n.qrPayload) { qrBitmap(n.qrPayload).asImageBitmap() }
                    Image(
                        bitmap, contentDescription = "Safety number code",
                        modifier = Modifier.size(156.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).border(2.dp, Palette.Ink, RoundedCornerShape(20.dp)).padding(10.dp),
                    )
                    val groups = n.digits.split(" ")
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        groups.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { g ->
                                    Text(g, fontFamily = FontFamily.Monospace, fontSize = 16.5.sp, letterSpacing = 0.5.sp, color = Palette.Ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Text("The same 60 digits on both your phones", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink.copy(alpha = 0.75f))
                }
            }
            error != null -> KitFoot(error!!)
            else -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        AnimatedVisibility(result == true) {
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Palette.Green).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Ink), contentAlignment = Alignment.Center) {
                        Icon(Glyphs.Checked, contentDescription = null, tint = Palette.Green, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.padding(end = 40.dp)) {
                        Text("It's a match!", style = displayStyle(19.sp), color = Color(0xFF0F3D24))
                        Text("Just you and $title in this chat. Nobody in the middle.", fontSize = 13.5.sp, color = Color(0xFF0F3D24))
                    }
                }
                ShapeSticker(ShapeKind.Sparkle, Modifier.align(Alignment.TopEnd).padding(12.dp), size = 28.dp, delayMs = 100)
            }
        }
        AnimatedVisibility(result == false) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFFFFD9D2)).border(2.dp, Palette.Ink, RoundedCornerShape(26.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("That's not $title's code", style = displayStyle(19.sp), color = Color(0xFF7A1A0E))
                Text("Scan the code on their phone, in your chat with them. If it still doesn't match, keep anything sensitive for when you meet.", fontSize = 13.5.sp, lineHeight = 19.sp, color = Color(0xFF7A1A0E))
            }
        }
        if (number != null) {
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(Palette.Ink).clickable(role = Role.Button) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                    else camera.launch(Manifest.permission.CAMERA)
                },
                contentAlignment = Alignment.Center,
            ) { Text("Scan their code", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Cream) }
        }
        KitFoot("Not together? Read the digits to each other on a call you trust.")
    }
    if (scanning) {
        Dialog(onDismissRequest = { scanning = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val sweep = rememberInfiniteTransition(label = "scan").animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "line")
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0C))) {
                Box(Modifier.matchParentSize().graphicsLayer { alpha = 0.9f }) {
                    QrScanner { code ->
                        val match = code.trim() == number?.qrPayload
                        scanning = false
                        result = match
                        if (match) model.markSafetyChecked(conversationId)
                    }
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(240.dp).border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(30.dp))) {
                        Box(
                            Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(3.dp).offset(y = (34 + 166 * sweep.value).dp)
                                .clip(CircleShape).background(Palette.Marigold),
                        )
                    }
                    Text("Point at the code on $title's phone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Box(
                        Modifier.height(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(role = Role.Button) { scanning = false }
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun Face(username: String, label: String) {
    Column(Modifier.width(90.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LiftedAvatar(username, size = 64)
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = tones().text, maxLines = 1)
    }
}

// ---- recovery key ----

/** Settings → Recovery key: only you have it, and making a new one behind your unlock. */
@Composable
fun RecoveryKeySettings(state: UiState, model: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showToast = LocalShowToast.current
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    KitPage("Recovery key", onBack) {
        val key = state.unconfirmedRecoveryKey
        if (key != null) {
            NewKeyCard(key, model, showToast)
        } else {
            Box(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Palette.Ink).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Written down", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F3D24),
                        modifier = Modifier.background(Palette.Green, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Text("Only you have it", style = displayStyle(24.sp), color = Palette.Cream, modifier = Modifier.padding(end = 60.dp))
                    Text(
                        "We can't show it again: this phone stopped keeping it once you said you'd written it down. Lost it? Make a new one.",
                        fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFC9C6BC),
                    )
                }
                ShapeSticker(ShapeKind.Seal, Modifier.align(Alignment.TopEnd).padding(16.dp), size = 54.dp, delayMs = 50, tappable = true)
            }
            BigButton("Make a new recovery key", enabled = true, working = working, modifier = Modifier.padding(top = 6.dp)) {
                scope.launch {
                    if (!confirmOwner(context, "Confirm it's you to make a new recovery key")) return@launch
                    working = true
                    try { model.newRecoveryKey(); error = null } catch (e: Exception) { error = e.userMessage } finally { working = false }
                }
            }
            KitFoot(error ?: "The new key works at once and the old one stops working. Your chats and safety number stay as they are.")
        }
    }
}

@Composable
private fun NewKeyCard(key: String, model: AppViewModel, showToast: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val groups = key.lowercase().filter { it.isLetterOrDigit() }.chunked(4)
    KitFoot("Write this down now and keep it somewhere offline. Your old key no longer works.")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Palette.Ink).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.blur(if (shown) 0.dp else 7.dp).graphicsLayer { alpha = if (shown || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1f else 0.15f }
                .semantics { if (!shown) contentDescription = "Hidden" },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            groups.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { g -> Text(g, fontFamily = FontFamily.Monospace, fontSize = 17.sp, letterSpacing = 0.8.sp, color = Palette.Cream, modifier = Modifier.weight(1f)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.height(42.dp).clip(CircleShape).background(if (copied) Palette.Green else Palette.Marigold)
                    .clickable(role = Role.Button) {
                        clipboard.setText(AnnotatedString(key))
                        copied = true
                        scope.launch { delay(1500); copied = false }
                    }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (copied) "Copied" else "Copy", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Palette.Ink) }
            Box(
                Modifier.height(42.dp).clip(CircleShape).border(1.5.dp, Color(0xFF4A4840), CircleShape)
                    .clickable(role = Role.Button) { shown = !shown }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (shown) "Hide" else "Show", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.Cream) }
        }
    }
    KitCard(radius = 22.dp) { SwitchRow("I've written it down", checked = saved) { saved = it } }
    BigButton("Done", enabled = saved, modifier = Modifier.padding(top = 6.dp)) {
        model.confirmRecoveryKeySaved()
        showToast("New key saved. The old one stopped working")
    }
}

// ---- blocked ----

/** Everyone you've blocked, each with Unblock; or nobody, with shapes. */
@Composable
fun BlockedScreen(model: AppViewModel, onBack: () -> Unit) {
    val t = tones()
    var blocked by remember { mutableStateOf<List<String>?>(null) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(version) { if (version > 0) delay(400); blocked = model.blockedUsers() }
    BackHandler(onBack = onBack)
    KitPage("Blocked", onBack, back = "You") {
        val list = blocked
        if (!list.isNullOrEmpty()) {
            KitCard(radius = 24.dp) {
                list.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Avatar(name, size = 42, stamp = true)
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                            Text("@$name", fontSize = 13.sp, color = t.secondary)
                        }
                        Text(
                            "Unblock", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.text,
                            modifier = Modifier.clip(CircleShape).border(1.5.dp, t.line, CircleShape)
                                .clickable(role = Role.Button) { model.setBlocked(name, false); version++ }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        } else if (list != null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(120.dp, 90.dp)) {
                    ShapeSticker(ShapeKind.Moon, Modifier.align(Alignment.CenterStart).offset(x = 10.dp, y = 6.dp), size = 70.dp, delayMs = 50)
                    ShapeSticker(ShapeKind.Sparkle, Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 6.dp), size = 30.dp, delayMs = 200, phase = -1f)
                }
                Text("Nobody's blocked", style = displayStyle(22.sp), color = t.text)
                Text("Block someone from the menu in your chat with them.", fontSize = 14.5.sp, color = t.secondary, textAlign = TextAlign.Center)
            }
        }
        KitFoot("Blocked people can't message you, and their messages in groups don't reach this phone. They aren't told. Blocks follow you to every linked device.")
    }
}

// ---- your own server ----

/** From onboarding's "Use my own": an address, checked before you sign up. */
@Composable
fun OwnServerScreen(state: UiState, model: AppViewModel, onDismiss: () -> Unit) {
    val t = tones()
    val scope = rememberCoroutineScope()
    val showToast = LocalShowToast.current
    var address by remember { mutableStateOf(if (state.serverUrl != DEFAULT_SERVER) state.serverUrl else "") }
    var check by remember { mutableStateOf(0) } // 0 idle, 1 checking, 2 ok, 3 bad
    val cleaned = address.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    val local = cleaned.startsWith("localhost") || cleaned.startsWith("127.0.0.1") || cleaned.startsWith("10.0.2.2")
    val url = (if (local) "http://" else "https://") + cleaned
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }

    fun go() {
        if (cleaned.isEmpty()) return
        if (check == 2) {
            model.setServerUrl(url)
            showToast("Signing up on $cleaned")
            onDismiss()
            return
        }
        check = 1
        scope.launch {
            val ok = serverAnswers(url)
            check = if (ok) 2 else 3
            if (!ok) { shake.snapTo(0f); shake.animateTo(1f, tween(400)) }
        }
    }

    FullScreen(onDismiss) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Your server", onDismiss)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Use your own server", style = displayStyle(32.sp), color = t.text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
                Text(
                    "Run Messenger's server yourself and sign up on it. Everyone you want to chat with needs an account on the same server.",
                    fontSize = 13.sp, lineHeight = 18.5.sp, color = t.secondary, modifier = Modifier.padding(horizontal = 22.dp),
                )
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Server address", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.secondary)
                    val border = when (check) { 2 -> Kit.OkGreen; 3 -> Color(0xFFE5484D); else -> t.text }
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).graphicsLayer { translationX = 6.dp.toPx() * kotlin.math.sin(shake.value * Math.PI.toFloat() * 4) }
                            .clip(RoundedCornerShape(18.dp)).background(t.surface).border(1.5.dp, border, RoundedCornerShape(18.dp))
                            .padding(start = 16.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("https://", fontSize = 16.sp, color = t.secondary)
                        Box(Modifier.weight(1f)) {
                            if (address.isEmpty()) Text("chat.example.com", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.secondary)
                            BasicTextField(
                                address, { address = it; check = 0 }, singleLine = true,
                                textStyle = TextStyle(color = t.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                                cursorBrush = SolidColor(t.text),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { go() }),
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Server address" },
                            )
                        }
                    }
                    Row(Modifier.heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (check == 1) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = t.text)
                        Text(
                            when (check) {
                                1 -> "Checking $cleaned…"
                                2 -> "Messenger server found"
                                3 -> "No Messenger server answered there. Check the address with whoever runs it."
                                else -> ""
                            },
                            fontSize = 14.sp, color = when (check) { 2 -> Kit.OkGreen; 3 -> t.danger; else -> t.secondary },
                        )
                    }
                }
                if (check == 2) {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        KitCard(radius = 22.dp) {
                            KitRow("Server") { KitValueText(cleaned) }
                            KitRow("Connection") { KitValueText(if (local) "HTTP (this computer)" else "HTTPS") }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).clip(CircleShape)
                            .background(if (cleaned.isEmpty()) t.line else if (check == 2) Palette.Marigold else Palette.Ink)
                            .clickable(enabled = cleaned.isNotEmpty() && check != 1, role = Role.Button) { go() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (check == 2) "Continue on $cleaned" else "Check server", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            color = if (cleaned.isEmpty()) t.secondary else if (check == 2) Palette.Ink else Palette.Cream,
                        )
                    }
                    val defaultHost = DEFAULT_SERVER.removePrefix("https://")
                    Text(
                        "Use $defaultHost instead", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text,
                        textDecoration = TextDecoration.Underline, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            model.setServerUrl(DEFAULT_SERVER)
                            showToast("Using $defaultHost")
                            onDismiss()
                        }.padding(vertical = 12.dp),
                    )
                }
                Text(
                    "Your server stores encrypted messages and files for up to 30 days and can't read them.",
                    fontSize = 13.sp, lineHeight = 18.5.sp, color = t.secondary, modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
        }
    }
}

private const val DEFAULT_SERVER = "https://qchat.qwantarc.com"

/** A Messenger server answers /v1/health with "ok". */
private suspend fun serverAnswers(base: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val connection = (java.net.URL("$base/v1/health").openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
        }
        try {
            connection.responseCode == 200 && connection.inputStream.bufferedReader().readText().trim() == "ok"
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}

// ---- live location ----

/** Sharing live location with a chat: who sees it, a little map, how long, the "Always" note, and Share. */
@Composable
fun LiveLocationSheet(conversationId: String, state: UiState, model: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val t = tones()
    val scope = rememberCoroutineScope()
    val showToast = LocalShowToast.current
    val name = state.conversations.firstOrNull { it.id == conversationId }?.title ?: "them"
    var choice by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    val hour = System.currentTimeMillis() + 3_600_000L
    val night = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59) }.timeInMillis
    val choices = listOf(
        Triple("For 1 hour", "Until " + time.format(java.util.Date(hour)), hour),
        Triple("Until end of day", "Until " + time.format(java.util.Date(night)), night),
        Triple("Until you stop", "You decide when", null),
    )
    fun start() {
        scope.launch {
            try {
                model.startSharingLocation(conversationId, choices[choice].third)
                showToast("Sharing with $name " + if (choices[choice].third == null) "until you stop" else choices[choice].second.lowercase())
                onDismiss()
            } catch (e: Exception) {
                error = e.userMessage
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (LocationSharingService.hasLocationPermission(context)) start() else error = "Allow location access to share your location."
    }
    val background = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
        ) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(t.home)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                    .navigationBarsPadding().padding(top = 10.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(t.line))
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Share live location with $name", style = displayStyle(22.sp), color = t.text)
                    Text("Only $name sees it, encrypted like a message. The server never sees a place.", fontSize = 14.sp, lineHeight = 19.5.sp, color = t.secondary)
                }
                MiniMap("You", Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(130.dp))
                Box(Modifier.padding(horizontal = 16.dp)) {
                    KitCard(radius = 24.dp) {
                        choices.forEachIndexed { i, (label, sub, _) ->
                            Row(
                                Modifier.fillMaxWidth().height(54.dp).clickable(role = Role.RadioButton) { choice = i }.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                                    Text(sub, fontSize = 12.5.sp, color = t.secondary)
                                }
                                PickTick(choice == i)
                            }
                        }
                    }
                }
                AnimatedVisibility(choice > 0 && !background) {
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFFFF1C7)).padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("📍", fontSize = 16.sp)
                        Text(
                            buildAnnotatedString {
                                append("To keep sharing when Messenger is closed, allow location ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("All the time") }
                                append(" next. Otherwise it pauses while the app is in the background.")
                            },
                            fontSize = 13.5.sp, lineHeight = 19.sp, color = Color(0xFF5A3E00),
                        )
                    }
                }
                error?.let { Text(it, fontSize = 13.sp, color = t.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
                BigButton("Share", enabled = true, modifier = Modifier.padding(horizontal = 16.dp)) {
                    val missing = missingPermissions(context)
                    if (missing.isEmpty()) start() else permissions.launch(missing)
                }
            }
        }
    }
}

/** A drawn street grid with a blue dot and a label: the setup's little map. */
@Composable
fun MiniMap(label: String, modifier: Modifier = Modifier) {
    val t = tones()
    val dark = isDark()
    Box(modifier.clip(RoundedCornerShape(22.dp))) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(if (dark) Color(0xFF16181B) else Color(0xFFECEEE6))
            val road = if (dark) Color(0xFF24272C) else Color.White
            val gap = 54.dp.toPx()
            val w = 4.dp.toPx()
            var x = 0f
            while (x <= size.width) { drawRect(road, Offset(x, 0f), Size(w, size.height)); x += gap }
            var y = 0f
            while (y <= size.height) { drawRect(road, Offset(0f, y), Size(size.width, w)); y += gap }
        }
        // You, as on the map: your sticker on a pin with a blue ground dot.
        val mine = LocalMyName.current
        if (mine != null) {
            BalloonPin(
                inkFor(mine, LocalProfiles.current).color(dark),
                Modifier.align(Alignment.Center).offset(y = (-14).dp),
                size = 42.dp, isMe = true,
            ) { Avatar(mine, size = 42) }
        }
        Text(
            label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.text,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(t.surface, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

// ---- states ----

/** Before the system asks: what turning notifications on means. */
@Composable
fun NotificationPrompt(onTurnOn: () -> Unit, onLater: () -> Unit) {
    val t = tones()
    Dialog(onDismissRequest = onLater, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            HomeGround(Modifier.matchParentSize())
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(210.dp)) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 14.dp, top = 40.dp).fillMaxWidth()
                            .graphicsLayer { shadowElevation = 15.dp.toPx(); shape = RoundedCornerShape(22.dp); clip = true }
                            .background(t.surface).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF111110)), contentAlignment = Alignment.Center) {
                            Icon(Glyphs.ChatsFilled, contentDescription = null, tint = Palette.Marigold, modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text("Mira", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.text, modifier = Modifier.weight(1f))
                                Text("now", fontSize = 13.5.sp, color = t.secondary)
                            }
                            Text("Leaving now, see you at arrivals ✈️", fontSize = 13.5.sp, color = t.text)
                        }
                    }
                    ShapeSticker(ShapeKind.Bolt, Modifier.align(Alignment.TopEnd).padding(end = 20.dp), size = 46.dp, delayMs = 200)
                    ShapeSticker(ShapeKind.Heart, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 30.dp), size = 40.dp, delayMs = 320, phase = -1f)
                }
                Text("Want a nudge when someone messages?", style = displayStyle(34.sp), color = t.text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("✓", fontWeight = FontWeight.Bold, color = Kit.OkGreen, fontSize = 15.sp)
                    Text("The push itself carries nothing readable. This phone fetches and decrypts the message, then shows it.", fontSize = 15.sp, lineHeight = 21.sp, color = t.text)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("!", fontWeight = FontWeight.Bold, color = Color(0xFFB07A00), fontSize = 15.sp)
                    Text("To wake the right phones, our server learns who you chat with. With notifications off, it doesn't. You can change this any time.", fontSize = 15.sp, lineHeight = 21.sp, color = t.secondary)
                }
                Spacer(Modifier.weight(1f))
                BigButton("Turn on notifications", enabled = true, onClick = onTurnOn)
                Text(
                    "Not now", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onLater).padding(vertical = 14.dp),
                )
            }
        }
    }
}

/** The Map when nobody shares with you: three shapes, a line about it, and sharing your own. */
@Composable
fun EmptyMapCard(state: UiState, model: AppViewModel, modifier: Modifier = Modifier) {
    val t = tones()
    var picking by remember { mutableStateOf(false) }
    var sharingWith by remember { mutableStateOf<String?>(null) }
    Column(
        modifier.fillMaxWidth().graphicsLayer { shadowElevation = 20.dp.toPx(); shape = RoundedCornerShape(28.dp); clip = true }
            .background(t.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FloatingPins(Modifier.fillMaxWidth().height(96.dp))
        Text("Nobody's sharing right now", style = displayStyle(23.sp), color = t.text)
        Text(
            "When a friend shares their live location with you, they show up here. Only you see this map; it's built on this phone.",
            fontSize = 14.5.sp, lineHeight = 20.sp, color = t.secondary,
        )
        Box(
            Modifier.padding(top = 4.dp).fillMaxWidth().height(50.dp).clip(CircleShape).background(Palette.Ink)
                .clickable(enabled = state.conversations.any { it.active }, role = Role.Button) { picking = true },
            contentAlignment = Alignment.Center,
        ) { Text("Share yours with someone", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Cream) }
    }
    if (picking) {
        ChatPickerSheet("Share with…", state, onDismiss = { picking = false }) { id ->
            picking = false
            sharingWith = id
        }
    }
    sharingWith?.let { id -> LiveLocationSheet(id, state, model) { sharingWith = null } }
}

/** Picks one chat. */
@Composable
fun ChatPickerSheet(title: String, state: UiState, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val t = tones()
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val chats = state.conversations.filter { it.active && !it.blocked }.filter { q.isEmpty() || it.title.lowercase().contains(q) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
        ) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(t.home)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                    .navigationBarsPadding().padding(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(t.line))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = displayStyle(22.sp), color = t.text, modifier = Modifier.weight(1f))
                    Text("Cancel", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.secondary, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
                }
                ComposeSearchField(query, { query = it }, placeholder = "Search chats")
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    KitCard(radius = 24.dp) {
                        chats.forEach { chat ->
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(chat.id) }.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Avatar(chat.title, isGroup = chat.kind == ConversationKind.GROUP, size = 40, stamp = true)
                                Text(chat.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, modifier = Modifier.weight(1f))
                                KitChevron()
                            }
                        }
                    }
                }
            }
        }
    }
}
