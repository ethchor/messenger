package com.qwantarc.messenger.ui

import androidx.compose.ui.draw.drawBehind
import com.qwantarc.messenger.userMessage
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qwantarc.messenger.AppLock
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Onboarding's paper: warm ground, faint grid, and the stamp outline (as on the iPhone). */
private class OnboardTones(val dark: Boolean) {
    val ground = if (dark) Color(0xFF0F0F11) else Color(0xFFF7F5EE)
    val grid = if (dark) Color(0xFF18181C) else Color(0xFFE9E6DA)
    val field = if (dark) Color(0xFF1A1A1D) else Color.White
    val text = if (dark) Color(0xFFF3F2EE) else Color(0xFF141414)
    val sub = if (dark) Color(0xFFA3A1A9) else Color(0xFF4A4840)
    val ink = if (dark) Color(0xFF1F1F23) else Color(0xFF141414)
    val off = if (dark) Color(0xFF3A3A40) else Color(0xFFE2DFD3)
    val ok = if (dark) Color(0xFF5DD08A) else Color(0xFF166534)
    val bad = if (dark) Color(0xFFFF8A7A) else Color(0xFF9A3412)
    val seg = if (dark) Color(0xFF2A2A2F) else Color(0xFFE9E6DC)
    val segThumb = if (dark) Color(0xFF4A4A52) else Color.White
    val stampLine = if (dark) Color(0xFFF3F2EE) else Color(0xFF141414)
}

private val Dark = Color(0xFF141414)
private val Cream = Color(0xFFF7F5EE)

/** The stickers to pick from at sign-up, each on its own colour. */
private val onboardStickers = listOf(
    AvatarDrawing.Fox to 0xFFFFD23F, AvatarDrawing.Cat to 0xFFF4A6D0, AvatarDrawing.Owl to 0xFFC3B5FF, AvatarDrawing.Bear to 0xFF5DC584,
    AvatarDrawing.Rabbit to 0xFFC3B5FF, AvatarDrawing.Panda to 0xFF5DC584, AvatarDrawing.Penguin to 0xFFFFD23F, AvatarDrawing.Koala to 0xFFF4A6D0,
)

/** Kept until sign-up finishes, then shared as the profile. */
private object PendingFace {
    var drawing: AvatarDrawing? = null
    /** A photo instead, as a square JPEG. */
    var photo: ByteArray? = null
    var set = false
}

@Composable
private fun onboardTones() = OnboardTones(isDark()).let { remember(it.dark) { it } }

@Composable
private fun OnboardGround(t: OnboardTones) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(t.ground)
        val step = 28.dp.toPx()
        var x = 0f
        while (x <= size.width) { drawRect(t.grid, Offset(x, 0f), Size(1.dp.toPx(), size.height)); x += step }
        var y = 0f
        while (y <= size.height) { drawRect(t.grid, Offset(0f, y), Size(size.width, 1.dp.toPx())); y += step }
    }
}

private enum class Step { Welcome, Name, Recover, Link }

/** Sign-up, recovery and linking: a sticker welcome, then a name and a face. */
@Composable
fun OnboardingScreen(state: UiState, model: AppViewModel) {
    val t = onboardTones()
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    var forward by remember { mutableStateOf(true) }
    fun go(next: Step, fwd: Boolean = true) { forward = fwd; step = next }
    if (step != Step.Welcome) BackHandler { go(Step.Welcome, false) }

    Box(Modifier.fillMaxSize()) {
        OnboardGround(t)
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val ease = tween<androidx.compose.ui.unit.IntOffset>(360, easing = CubicBezierEasing(0.2f, 0.85f, 0.2f, 1f))
                (slideInHorizontally(ease) { if (forward) it else -it } + fadeIn()) togetherWith
                    (slideOutHorizontally(ease) { if (forward) -it else it } + fadeOut())
            },
            label = "onboarding",
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        ) { s ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 520.dp).fillMaxSize()) {
                    when (s) {
                        Step.Welcome -> WelcomeStep(t) { go(it) }
                        Step.Name -> NameStep(t, state, model)
                        Step.Recover -> RecoverStep(t, state, model)
                        Step.Link -> LinkStep(t, model)
                    }
                }
            }
        }
        Box(Modifier.statusBarsPadding()) {
            OnboardTopBar(t, canBack = step != Step.Welcome, dots = if (step == Step.Name) 0 else null) { go(Step.Welcome, false) }
        }
    }
}

@Composable
private fun OnboardTopBar(t: OnboardTones, canBack: Boolean, dots: Int?, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canBack) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(t.ground).border(1.5.dp, t.text, CircleShape)
                    .pressScale(remember { MutableInteractionSource() }).clickable(onClick = back)
                    .semantics { contentDescription = "Back"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyphs.Back, contentDescription = null, tint = t.text, modifier = Modifier.size(20.dp)) }
        } else {
            Spacer(Modifier.size(44.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (dots != null) StepDots(t, dots) }
        Spacer(Modifier.size(44.dp))
    }
}

@Composable
private fun StepDots(t: OnboardTones, current: Int) {
    Row(Modifier.semantics { contentDescription = "Step ${current + 1} of 3" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val w by animateDpAsState(if (i == current) 26.dp else 8.dp, spring(dampingRatio = 0.6f), label = "dot")
            Box(Modifier.height(8.dp).width(w).graphicsLayer { alpha = if (i <= current) 1f else 0.25f }.clip(CircleShape).background(t.text))
        }
    }
}

/** A rounded-square sticker with a dark outline. */
@Composable
private fun StampFace(t: OnboardTones, drawing: AvatarDrawing?, initial: String, background: Color, side: Dp, radius: Dp, outline: Dp = 2.dp, line: Color = t.stampLine) {
    val shape = RoundedCornerShape(radius)
    Box(Modifier.size(side).clip(shape).background(background).border(outline, line, shape), contentAlignment = Alignment.Center) {
        if (drawing != null) {
            AvatarArt(drawing, Color.Transparent, Modifier.size(side * 0.8f), stamp = true)
        } else {
            Text(initial, style = displayStyle((side.value * 0.42f).sp, bold = true), color = Dark)
        }
    }
}

/** Pops in from small with an overshoot after [delayMs]. */
@Composable
private fun Modifier.popIn(delayMs: Int): Modifier {
    val still = reduceMotion()
    val a = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) { delay(delayMs.toLong()); a.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) } }
    return graphicsLayer { val v = a.value; scaleX = 0.4f + 0.6f * v; scaleY = 0.4f + 0.6f * v; alpha = v.coerceIn(0f, 1f) }
}

/** Fades and rises 14 dp into place after [delayMs]. */
@Composable
private fun Modifier.riseIn(delayMs: Int): Modifier {
    val still = reduceMotion()
    val a = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) { delay(delayMs.toLong()); a.animateTo(1f, tween(500, easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f))) } }
    return graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 14.dp.toPx() }
}

/** The big pill with an arrow in a circle at its end. */
@Composable
private fun ArrowPill(title: String, dark: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().height(60.dp).pressScale(source).clip(CircleShape).background(if (dark) Dark else Palette.Marigold)
            .clickable(interactionSource = source, indication = null, onClick = onClick).padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (dark) Cream else Dark, modifier = Modifier.weight(1f))
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (dark) Palette.Marigold else Dark), contentAlignment = Alignment.Center) {
            Icon(Glyphs.ArrowUp, contentDescription = null, tint = if (dark) Dark else Palette.Marigold, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = 90f })
        }
    }
}

/** A full-width pill: marigold when it can go, muted when it can't. */
@Composable
private fun BigButton(t: OnboardTones, title: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().height(60.dp).pressScale(source).clip(CircleShape)
            .background(if (enabled) Palette.Marigold else if (t.dark) Color(0xFF2A2A2F) else Color(0xFFE2DFD3))
            .clickable(interactionSource = source, indication = null, enabled = enabled && !busy, onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Dark, strokeWidth = 2.dp)
        else Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Dark else if (t.dark) Color(0xFF8C8A92) else Color(0xFF6B6860))
    }
}

/** An outlined pill. */
@Composable
private fun OutlinePill(t: OnboardTones, title: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().height(54.dp).pressScale(source).clip(CircleShape).border(1.5.dp, t.text, CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = t.text) }
}

/** The iPhone-style switch in green, for onboarding's toggles. */
@Composable
private fun GreenSwitch(t: OnboardTones, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.6f), label = "thumb")
    Box(
        Modifier.size(51.dp, 31.dp).clip(CircleShape).background(if (on) Palette.Green else t.off)
            .clickable { onChange(!on) }
            .semantics { contentDescription = label; role = Role.Switch; stateDescription = if (on) "On" else "Off" }
            .padding(2.dp),
    ) { Box(Modifier.offset(x = x).size(27.dp).clip(CircleShape).background(Color.White)) }
}

// ---- Welcome ----

@Composable
private fun WelcomeStep(t: OnboardTones, go: (Step) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 24.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { StickerCollage(t) }
        Text("Every chat, just between you.", style = displayStyle(40.sp).copy(lineHeight = 41.sp), color = t.text, modifier = Modifier.padding(top = 8.dp).riseIn(100))
        Text(
            "Messages lock on your phone and unlock only on theirs. Our server carries them without reading a word.",
            fontSize = 16.sp, lineHeight = 23.sp, color = t.sub, modifier = Modifier.padding(top = 12.dp).riseIn(180),
        )
        Spacer(Modifier.weight(1f).height(20.dp))
        Column(Modifier.riseIn(260), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ArrowPill("Let's go") { go(Step.Name) }
            OutlinePill(t, "I have a recovery key") { go(Step.Recover) }
            Text(
                "Use the account from my phone",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text, textDecoration = TextDecoration.Underline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { go(Step.Link) }.padding(vertical = 10.dp).semantics { role = Role.Button },
            )
        }
    }
}

/** Five stickers, four shapes and two little bubbles, floating. */
@Composable
private fun StickerCollage(t: OnboardTones) {
    val still = reduceMotion()
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { if (!still) while (true) withFrameMillis { time = it } }
    val now = time / 1000.0
    Box(Modifier.requiredWidth(342.dp).height(320.dp).clearAndSetSemantics {}) {
        @Composable
        fun stamp(drawing: AvatarDrawing, color: Long, side: Int, radius: Int, x: Int, y: Int, delayMs: Int, period: Double) {
            val dy = if (still) 0f else (-5 * sin(PI * (((now + delayMs * 0.003) % period) / period))).toFloat()
            Box(Modifier.offset(x.dp, y.dp).popIn(delayMs).graphicsLayer { translationY = dy.dp.toPx() }) {
                StampFace(t, drawing, "", Color(color), side.dp, radius.dp)
            }
        }
        stamp(AvatarDrawing.Cat, 0xFFF4A6D0, 118, 30, 6, 12, 0, 5.0)
        stamp(AvatarDrawing.Fox, 0xFFFFD23F, 98, 26, 146, 0, 80, 6.5)
        stamp(AvatarDrawing.Owl, 0xFFDCE6FA, 92, 26, 250, 74, 160, 5.0)
        stamp(AvatarDrawing.Bear, 0xFF5DC584, 104, 28, 34, 162, 240, 6.5)
        stamp(AvatarDrawing.Rabbit, 0xFFFFFFFF, 118, 30, 166, 140, 320, 5.0)
        ShapeSticker(ShapeKind.Sparkle, Modifier.offset(282.dp, 204.dp), size = 56.dp, delayMs = 450)
        ShapeSticker(ShapeKind.Squiggle, Modifier.offset(262.dp, 12.dp), size = 38.dp, delayMs = 550)
        ShapeSticker(ShapeKind.Star, Modifier.offset(0.dp, 132.dp), size = 30.dp, delayMs = 620)
        ShapeSticker(ShapeKind.Bolt, Modifier.offset(150.dp, 270.dp), size = 38.dp, delayMs = 700)
        Box(
            Modifier.offset(112.dp, 112.dp).popIn(550).height(34.dp)
                .clip(RoundedCornerShape(17.dp, 17.dp, 17.dp, 5.dp)).background(Dark).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Kal milte hain?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Cream) }
        val pakka = RoundedCornerShape(17.dp, 17.dp, 5.dp, 17.dp)
        Box(
            Modifier.offset(240.dp, 272.dp).popIn(750).height(34.dp)
                .clip(pakka).background(Palette.Marigold).border(1.5.dp, Dark, pakka).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Pakka!", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Dark) }
    }
}

// ---- Name and face ----

@Composable
private fun NameStep(t: OnboardTones, state: UiState, model: AppViewModel) {
    var sticker by rememberSaveable { mutableIntStateOf(0) }
    // 0 sticker, 1 photo, 2 initial.
    var kind by rememberSaveable { mutableIntStateOf(0) }
    val useInitial = kind == 2
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var showServer by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val cleaned = name.trim().lowercase()
    val valid = Regex("^[a-z][a-z0-9_.]{2,31}$").matches(cleaned)
    // Live: ask the server's directory a moment after typing stops.
    var availability by remember { mutableStateOf<Pair<String, Availability>?>(null) }
    LaunchedEffect(cleaned, state.serverUrl) {
        if (!valid) { availability = null; return@LaunchedEffect }
        availability = cleaned to Availability.Checking
        delay(350)
        availability = cleaned to checkUsername(cleaned, state.serverUrl)
    }
    val avail = availability?.takeIf { it.first == cleaned }?.second
    val nameOk = valid && avail != Availability.Taken
    val (status, statusColor) = when {
        cleaned.isEmpty() -> "3 to 32 characters, starting with a letter. Then letters, numbers, dots or underscores." to t.sub
        !cleaned.first().isLetter() -> "Start with a letter" to t.bad
        cleaned.length < 3 -> "A little longer: at least 3 characters" to t.bad
        cleaned.length > 32 -> "A little shorter: 32 characters at most" to t.bad
        !valid -> "Only letters, numbers, dots and underscores" to t.bad
        avail == Availability.Free -> "@$cleaned is all yours. Nice one" to t.ok
        avail == Availability.Taken -> "@$cleaned is taken. How about @${cleaned}_${cleaned.length * 7 % 90 + 10}?" to t.bad
        avail == Availability.Unknown -> "Couldn't check right now. We'll check when you continue." to t.sub
        else -> "Checking @$cleaned…" to t.sub
    }
    fun submit() {
        if (!nameOk) { scope.launch { shake.snapTo(0f); shake.animateTo(1f, tween(400)) }; return }
        PendingFace.drawing = if (kind == 0) onboardStickers[sticker].first else null
        PendingFace.photo = if (kind == 1) photo else null
        PendingFace.set = true
        model.register(cleaned)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 68.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pick a name and a face.", style = displayStyle(36.sp), color = t.text)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val picked = photo?.let { remember(it) { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (kind == 1 && picked != null) {
                val shape = RoundedCornerShape(28.dp)
                Image(picked, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(96.dp).clip(shape).border(2.dp, t.stampLine, shape))
            } else StampFace(
                t, if (kind == 0) onboardStickers[sticker].first else null, (cleaned.firstOrNull() ?: 'v').uppercase(),
                when (kind) { 2 -> Palette.Marigold; 1 -> Color(0xFFDCE6FA); else -> Color(onboardStickers[sticker].second) }, 96.dp, 28.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This is how you show up in every chat. Change the face any time.", fontSize = 14.5.sp, lineHeight = 20.sp, color = t.sub)
                Segmented(t, listOf("Sticker", "Photo", "Initial"), kind) { kind = it }
            }
        }
        if (useInitial) {
            Text("Your initial on a marigold stamp. Pick another colour any time.", fontSize = 14.sp, color = t.sub)
        } else if (kind == 1) {
            ProfilePhotoButtons(working = false) { photo = it }
            Text("Your photo is encrypted like a message. Where and when it was taken is stripped before it leaves your phone.", fontSize = 13.sp, color = t.sub)
        } else {
            Row(
                Modifier.padding(horizontal = 0.dp).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onboardStickers.forEachIndexed { i, (drawing, color) ->
                    val on = i == sticker
                    val lift by animateDpAsState(if (on) (-3).dp else 0.dp, spring(dampingRatio = 0.5f), label = "lift")
                    val shape = RoundedCornerShape(18.dp)
                    Box(
                        Modifier.offset(y = lift).size(58.dp).clip(shape).background(if (on) Color(color) else t.field)
                            .border(if (on) 2.dp else 1.5.dp, if (on) t.stampLine else if (t.dark) Color(0xFF2E2E33) else Color(0xFFD8D4C6), shape)
                            .clickable { sticker = i }
                            .semantics { contentDescription = drawing.title; selected = on; role = Role.RadioButton },
                        contentAlignment = Alignment.Center,
                    ) { AvatarArt(drawing, Color.Transparent, Modifier.size(44.dp), stamp = true) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Username", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier.fillMaxWidth().height(58.dp)
                    .graphicsLayer { translationX = (8 * sin(shake.value * PI * 4)).toFloat().dp.toPx() }
                    .clip(shape).background(t.field)
                    .border(2.dp, if (cleaned.isEmpty()) t.text else if (!nameOk) t.bad else if (avail == Availability.Free) t.ok else t.text, shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("@", fontSize = 18.sp, color = t.sub)
                Spacer(Modifier.width(3.dp))
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) Text("yourname", fontSize = 18.sp, color = t.sub.copy(alpha = 0.7f))
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.lowercase().replace(" ", "") },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 18.sp, color = t.text),
                        cursorBrush = SolidColor(t.text),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Username" },
                    )
                }
                AnimatedVisibility(valid && avail == Availability.Free) { Icon(Glyphs.Checked, contentDescription = null, tint = t.ok, modifier = Modifier.size(20.dp)) }
                if (valid && avail == Availability.Checking) CircularProgressIndicator(Modifier.size(16.dp), color = t.sub, strokeWidth = 2.dp)
            }
        }
        Text(status, fontSize = 14.sp, color = statusColor)
        Text(
            "Friends find you by your username, so make it one they'll recognise. It's yours for good: it can't be changed, only freed if you delete your account.",
            fontSize = 14.sp, lineHeight = 20.sp, color = Dark,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.VanishAccent).padding(horizontal = 16.dp, vertical = 14.dp),
        )
        ServerLine(t, state, model, showServer) { showServer = it }
        BigButton(t, "Continue", enabled = nameOk, busy = state.busy) { submit() }
    }
}

@Composable
private fun ServerLine(t: OnboardTones, state: UiState, model: AppViewModel, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val host = runCatching { java.net.URI(state.serverUrl).host }.getOrNull() ?: state.serverUrl
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("Server: $host · ", fontSize = 13.sp, color = t.sub)
            Text(
                if (expanded) "Done" else "Use my own", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.text,
                modifier = Modifier.clickable { onExpanded(!expanded) }.semantics { role = Role.Button },
            )
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shape = RoundedCornerShape(16.dp)
                BasicTextField(
                    value = state.serverUrl,
                    onValueChange = model::setServerUrl,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = t.text),
                    cursorBrush = SolidColor(t.text),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().height(46.dp).clip(shape).background(t.field).border(1.5.dp, t.text, shape)
                        .padding(horizontal = 14.dp, vertical = 13.dp).semantics { contentDescription = "Server" },
                )
                Text("Qwantarc's server, unless you run your own. For one on this computer, the emulator uses http://10.0.2.2:8080.", fontSize = 12.5.sp, color = t.sub)
            }
        }
    }
}

@Composable
private fun Segmented(t: OnboardTones, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(t.seg).padding(2.dp)) {
        val w = maxWidth / labels.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxSize().clip(RoundedCornerShape(8.dp)).background(t.segThumb))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxSize().clickable { onSelect(i) }.semantics { this.selected = i == selected; role = Role.Tab },
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontSize = 13.5.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium, color = t.text) }
            }
        }
    }
}

// ---- Recover ----

@Composable
private fun RecoverStep(t: OnboardTones, state: UiState, model: AppViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val cleanKey = key.lowercase().filter { it.isLetterOrDigit() }
    val nameOk = name.trim().length >= 3
    val keyOk = cleanKey.length == 64
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 68.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val shape = RoundedCornerShape(20.dp)
            Box(Modifier.size(64.dp).clip(shape).background(Palette.VanishAccent).border(2.dp, t.stampLine, shape), contentAlignment = Alignment.Center) {
                Icon(Glyphs.Key, contentDescription = null, tint = Dark, modifier = Modifier.size(28.dp))
            }
            Text("Welcome back.", style = displayStyle(36.sp), color = t.text)
        }
        Text(
            "Enter your username and the recovery key you wrote down. Old messages can't come back, and your contacts will see a new safety number for you.",
            fontSize = 15.5.sp, lineHeight = 22.sp, color = t.sub,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Username", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(shape).background(t.field).border(2.dp, t.text, shape).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("@", fontSize = 18.sp, color = t.sub)
                Box(Modifier.weight(1f).padding(start = 3.dp)) {
                    if (name.isEmpty()) Text("yourname", fontSize = 18.sp, color = t.sub.copy(alpha = 0.7f))
                    BasicTextField(
                        value = name, onValueChange = { name = it.lowercase().replace(" ", "") }, singleLine = true,
                        textStyle = TextStyle(fontSize = 18.sp, color = t.text), cursorBrush = SolidColor(t.text),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Username" },
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recovery key", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier.fillMaxWidth().clip(shape).background(t.ink)
                    .border(2.dp, if (tried && !keyOk) t.bad else if (keyOk) t.ok else t.text, shape).padding(16.dp),
            ) {
                if (key.isEmpty()) Text("abcd efgh ijkl mnop …", fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = Cream.copy(alpha = 0.45f))
                BasicTextField(
                    value = key, onValueChange = { key = it }, minLines = 3,
                    textStyle = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, fontFamily = FontFamily.Monospace, color = Cream),
                    cursorBrush = SolidColor(Cream),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Recovery key" },
                )
            }
        }
        Text(
            when {
                keyOk -> "That looks like a whole key."
                cleanKey.isEmpty() -> "64 letters and numbers. Spaces don't matter."
                else -> "${cleanKey.length} of 64 characters so far."
            },
            fontSize = 14.sp, color = if (tried && !keyOk) t.bad else if (keyOk) t.ok else t.sub,
        )
        Spacer(Modifier.height(10.dp))
        BigButton(t, "Recover my account", enabled = nameOk && keyOk, busy = state.busy) {
            tried = true
            if (nameOk && keyOk) model.recover(name.trim().lowercase(), key)
        }
    }
}

// ---- Link ----

@Composable
private fun LinkStep(t: OnboardTones, model: AppViewModel) {
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var secondsLeft by remember { mutableIntStateOf(600) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        try {
            code = model.beginLink()
            model.waitForLink()
        } catch (e: Exception) {
            error = e.userMessage
        }
    }
    LaunchedEffect(code) {
        if (code == null) return@LaunchedEffect
        secondsLeft = 600
        while (secondsLeft > 0) { delay(1000); secondsLeft -= 1 }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 68.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Link this device.", style = displayStyle(36.sp), color = t.text)
        Text(
            buildAnnotatedString {
                append("On your phone, open ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = t.text)) { append("You › Linked devices › Link a device") }
                append(" and scan this code.")
            },
            fontSize = 15.5.sp, lineHeight = 22.sp, color = t.sub,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(30.dp)
            Box {
                Box(Modifier.matchParentSize().offset(y = 7.dp).clip(shape).background(Dark))
                Box(Modifier.clip(shape).background(Color.White).border(2.dp, Dark, shape).padding(18.dp), contentAlignment = Alignment.Center) {
                    val shown = code
                    when {
                        shown != null -> {
                            val bitmap = remember(shown) { qrBitmap(shown) }
                            Image(bitmap.asImageBitmap(), contentDescription = "Link code", filterQuality = FilterQuality.None, modifier = Modifier.size(189.dp))
                            val key = RoundedCornerShape(14.dp)
                            Box(Modifier.size(46.dp).clip(key).background(Palette.Marigold).border(2.dp, Dark, key), contentAlignment = Alignment.Center) {
                                Icon(Glyphs.Key, contentDescription = null, tint = Dark, modifier = Modifier.size(22.dp))
                            }
                        }
                        error != null -> Text(error ?: "", color = t.bad, modifier = Modifier.size(189.dp))
                        else -> Box(Modifier.size(189.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Dark) }
                    }
                }
            }
        }
        if (code != null) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                PingDot()
                Spacer(Modifier.width(10.dp))
                Text(
                    if (secondsLeft > 0) "Waiting for your phone · ${secondsLeft / 60}:${"%02d".format(secondsLeft % 60)}" else "This code expired. Go back for a fresh one",
                    fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text,
                )
            }
            Text(
                "Copy the code instead", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.text, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { code?.let { clipboard.setText(AnnotatedString(it)) } }.padding(6.dp),
            )
        }
        Text(
            "The code lasts 10 minutes. Your phone approves, then sends your chats across, sealed so the server can't open them.",
            fontSize = 13.sp, lineHeight = 19.sp, color = t.sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A green dot with a ring pulsing out of it. */
@Composable
fun PingDot() {
    val still = reduceMotion()
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { if (!still) while (true) withFrameMillis { time = it } }
    val p = ((time % 2000) / 2000f)
    Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
        if (!still) Box(Modifier.size(10.dp).graphicsLayer { scaleX = 1 + p; scaleY = 1 + p; alpha = 0.55f * (1 - p) }.clip(CircleShape).background(Palette.Green))
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF2E9E5B)))
    }
}

// ---- Recovery key and done ----

/**
 * Step 2 of 3 at sign-up ("Meet your recovery key"), then the done card.
 * From Settings ([isReplacement], with [onBack]), the same key page after
 * making a new key.
 */
@Composable
internal fun RecoveryKeyScreen(
    recoveryKey: String,
    busy: Boolean,
    isReplacement: Boolean = false,
    onBack: (() -> Unit)? = null,
    model: AppViewModel? = null,
    state: UiState? = null,
    lock: AppLock.State? = null,
    onTurnOnLock: () -> Unit = {},
    onConfirm: () -> Unit,
) {
    val t = onboardTones()
    var wroteItDown by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var done by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val groups = recoveryKey.split(" ").filter { it.isNotEmpty() }
    onBack?.let { BackHandler(onBack = it) }
    // The face picked at sign-up goes out as the profile, once.
    LaunchedEffect(Unit) {
        if (!isReplacement && PendingFace.set && model != null) {
            PendingFace.set = false
            model.setProfile(PendingFace.drawing, Ink.Marigold)
            PendingFace.photo?.let { model.setProfilePhoto(it) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        OnboardGround(t)
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(
                targetState = done,
                transitionSpec = {
                    val ease = tween<androidx.compose.ui.unit.IntOffset>(360, easing = CubicBezierEasing(0.2f, 0.85f, 0.2f, 1f))
                    (slideInHorizontally(ease) { it } + fadeIn()) togetherWith (slideOutHorizontally(ease) { -it } + fadeOut())
                },
                label = "key",
                modifier = Modifier.widthIn(max = 520.dp).fillMaxSize(),
            ) { isDone ->
                if (isDone) {
                    DoneStep(t, state, lock, onTurnOnLock, model, busy, onConfirm)
                } else Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = if (isReplacement && onBack == null) 16.dp else 68.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(if (isReplacement) "Your new recovery key." else "Meet your recovery key.", style = displayStyle(36.sp), color = t.text)
                    Text(
                        if (isReplacement) "Your old recovery key no longer works. Write this one down and keep it somewhere offline; it's the only way to move your account to a new device."
                        else "Lose every device and this gets your account back. Only you have it, not even we keep a copy, so write it down and keep it somewhere offline.",
                        fontSize = 15.5.sp, lineHeight = 22.sp, color = t.sub,
                    )
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(t.ink)
                            .drawBehind {
                                // Two marigold rings peeking in at the corner, like a key's bow.
                                val c = Offset(size.width + 24.dp.toPx() - 60.dp.toPx(), -28.dp.toPx() + 60.dp.toPx())
                                drawCircle(Palette.Marigold, 40.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                                drawCircle(Palette.Marigold, 14.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                            }
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SelectionContainer {
                            Column(Modifier.blur(if (revealed) 0.dp else 7.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                groups.chunked(4).forEach { row ->
                                    Row(Modifier.fillMaxWidth()) {
                                        row.forEach {
                                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 17.sp, letterSpacing = 1.sp, color = Cream, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.height(44.dp).clip(CircleShape).background(if (copied) Palette.Green else Palette.Marigold)
                                    .clickable { clipboard.setText(AnnotatedString(recoveryKey)); copied = true }.padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(if (copied) Glyphs.Checked else Glyphs.Copy, contentDescription = null, tint = Dark, modifier = Modifier.size(18.dp))
                                Text(if (copied) "Copied" else "Copy", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Dark)
                            }
                            Box(
                                Modifier.height(44.dp).clip(CircleShape).border(1.5.dp, Color(0xFF4A4840), CircleShape)
                                    .clickable { revealed = !revealed }.padding(horizontal = 18.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (revealed) "Hide" else "Show", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Cream) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(t.field).border(1.5.dp, t.text, RoundedCornerShape(22.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("I've written it down", fontSize = 16.sp, color = t.text, modifier = Modifier.weight(1f))
                        GreenSwitch(t, "I've written my recovery key down", wroteItDown) { wroteItDown = it }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Lose it later? Make a new one in Settings and the old one stops working at once.",
                        fontSize = 13.sp, color = t.sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    BigButton(t, "Continue", enabled = wroteItDown, busy = busy) {
                        if (isReplacement) onConfirm() else done = true
                    }
                }
            }
            if (!isReplacement) OnboardTopBar(t, canBack = false, dots = if (done) 2 else 1) {}
        }
    }
}

/** "You're in": your face on its colour, app lock, and Start a chat. */
@Composable
private fun DoneStep(
    t: OnboardTones,
    state: UiState?,
    lock: AppLock.State?,
    onTurnOnLock: () -> Unit,
    model: AppViewModel?,
    busy: Boolean,
    onConfirm: () -> Unit,
) {
    val name = state?.account?.username ?: ""
    var lockOn by remember { mutableStateOf(true) }
    val drawing = PendingFace.drawing
    val color = drawing?.let { d -> onboardStickers.firstOrNull { it.first == d }?.second } ?: 0xFFFFD23F
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 68.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(Color(color))) {
            ShapeSticker(ShapeKind.Sparkle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 22.dp), size = 62.dp, delayMs = 250)
            ShapeSticker(ShapeKind.Heart, Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 104.dp), size = 36.dp, delayMs = 350)
            ShapeSticker(ShapeKind.Squiggle, Modifier.align(Alignment.TopEnd).padding(top = 108.dp, end = 36.dp), size = 34.dp, delayMs = 450)
            ShapeSticker(ShapeKind.Clover, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 130.dp), size = 30.dp, delayMs = 550)
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.popIn(0)) {
                    val pending = PendingFace.photo?.let { remember(it) { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                    if (pending != null) {
                        val shape = RoundedCornerShape(34.dp)
                        Image(pending, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(118.dp).clip(shape).border(2.dp, Dark, shape))
                    } else {
                        StampFace(OnboardTones(false), drawing, (name.firstOrNull() ?: 'v').uppercase(), Color.White, 118.dp, 34.dp, line = Dark)
                    }
                }
                Text("Welcome to the club, @$name", style = displayStyle(36.sp, bold = true), color = Dark, modifier = Modifier.riseIn(150))
                Text(
                    "All set. Every chat from here is end-to-end encrypted. Who's first?",
                    fontSize = 15.sp, lineHeight = 21.sp, color = Dark, modifier = Modifier.riseIn(220),
                )
            }
        }
        if (lock != null && model != null) {
            Row(
                Modifier.fillMaxWidth().riseIn(300).clip(RoundedCornerShape(24.dp)).background(t.field).border(1.5.dp, t.text, RoundedCornerShape(24.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Lock Messenger", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                    Text("With your fingerprint, face or screen lock", fontSize = 13.5.sp, color = t.sub)
                }
                GreenSwitch(t, "Lock Messenger", lockOn) { lockOn = it }
            }
        }
        Spacer(Modifier.weight(1f).height(20.dp))
        Box(Modifier.riseIn(380)) {
            if (busy) Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = t.text) }
            else ArrowPill("Start a chat", dark = true) {
                // On by default, as designed; turning it on asks for the fingerprint or face once.
                if (lockOn && lock?.enabled == false) onTurnOnLock()
                onConfirm()
            }
        }
    }
}


internal enum class Availability { Checking, Free, Taken, Unknown }

/** Whether a username is free, from the server's public directory: 404 free, 200 taken. */
internal suspend fun checkUsername(name: String, server: String): Availability = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val url = java.net.URL(server.trim().trimEnd('/') + "/v1/directory/" + name)
        val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            useCaches = false
        }
        try {
            when (connection.responseCode) {
                404 -> Availability.Free
                200 -> Availability.Taken
                else -> Availability.Unknown
            }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(Availability.Unknown)
}
