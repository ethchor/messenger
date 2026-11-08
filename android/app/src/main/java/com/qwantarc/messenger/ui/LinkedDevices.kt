package com.qwantarc.messenger.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.qwantarc.messenger.AppLock
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.core.LinkedDevice
import com.qwantarc.messenger.userMessage
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private const val LINK_PREFIX = "messenger-link:"
private const val MAX_DEVICES = 5

// ---- on a new device ----

/** "Link to an existing account": a QR code for a signed-in device to scan, then wait for its approval. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkThisDeviceScreen(model: AppViewModel, onBack: () -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        try {
            code = model.beginLink()
            model.waitForLink()
        } catch (e: Exception) {
            error = e.userMessage
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Link a device", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { RoundBarButton(onBack, "Back", Glyphs.Back) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Link to your account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "On a device where you're signed in, open Settings → Linked devices → Link a device, and scan this code.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shown = code
            when {
                shown != null -> {
                    val bitmap = remember(shown) { qrBitmap(shown) }
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = "Link code",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("Waiting for approval…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(shown)) }) { Text("Copy the code instead") }
                }
                error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error)
                else -> CircularProgressIndicator()
            }
            Text(
                "Your chats, history and settings come across, end-to-end encrypted. You can link up to five devices.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A QR code for [text], one pixel per module; drawn without smoothing. */
private fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}

// ---- on a device already signed in ----

/** Settings → Linked devices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesPage(model: AppViewModel, onLink: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<LinkedDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<LinkedDevice?>(null) }

    suspend fun reload() {
        loading = true
        try {
            devices = model.linkedDevices().sortedWith(compareBy({ !it.thisDevice }, { it.addedAtMs }))
            error = null
        } catch (e: Exception) {
            error = e.userMessage
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Linked devices", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { RoundBarButton(onBack, "Back", Glyphs.Back) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            SectionHeader("Your devices")
            Group {
                devices.forEach { device ->
                    ListItem(
                        headlineContent = { Text(device.name.ifBlank { "Device" }) },
                        supportingContent = { Text(if (device.thisDevice) "This device" else lastSeen(device)) },
                        leadingContent = { Icon(Glyphs.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            if (!device.thisDevice) {
                                TextButton(
                                    onClick = { removing = device },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) { Text("Remove") }
                            }
                        },
                    )
                }
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
            Footer("Removing a device signs it out and takes it out of every chat. Up to five devices.")
            Spacer(Modifier.height(12.dp))
            Group { ActionRow("Link a device", enabled = devices.size < MAX_DEVICES, onClick = onLink) }
            error?.let { Footer(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${device.name.ifBlank { "this device" }}?") },
            text = { Text("It's signed out and taken out of every chat. If it was lost or stolen, also consider Reset identity in Account.") },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        removing = null
                        scope.launch {
                            try {
                                model.removeDevice(device.deviceId)
                                reload()
                            } catch (e: Exception) {
                                error = e.userMessage
                            }
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

private fun lastSeen(device: LinkedDevice): String {
    val at = device.lastSeenMs.toLong()
    if (at <= 0) return "Not active yet"
    return "Active " + DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
}

/** Scan (or paste) the new device's code, then approve it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkDevicePage(model: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var withHistory by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
    LaunchedEffect(Unit) { if (!cameraAllowed) askForCamera.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onDone)

    fun read(text: String) {
        if (code != null || working) return
        val trimmed = text.trim()
        scope.launch {
            try {
                name = model.linkRequestName(trimmed).ifBlank { "New device" }
                code = trimmed
                error = null
            } catch (e: Exception) {
                error = e.userMessage
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Link a device", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { RoundBarButton(onDone, "Back", Glyphs.Back) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 40.dp),
        ) {
            val approving = code
            if (approving == null) {
                if (cameraAllowed) {
                    Box(
                        Modifier.padding(16.dp).fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                    ) {
                        QrScanner(onCode = ::read)
                    }
                    Footer("On the new device, choose “Link to an existing account” and point the camera at its code.")
                } else {
                    Footer("Allow the camera to scan the new device's code, or paste the code below.")
                }
                SectionHeader("Or paste the code")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        placeholder = { Text("$LINK_PREFIX…") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { read(pasted) }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                }
            } else {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Link “$name”?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "It will be signed in as you, and get every message from now on. Only link devices you own.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Group { SwitchRow("Bring my chat history", checked = withHistory) { withHistory = it } }
                Footer(
                    if (withHistory) "Your chats and messages come across, encrypted. Photos and files download when opened, while they're still on the server."
                    else "The new device starts fresh: your chats are there, but empty.",
                )
                Button(
                    onClick = {
                        scope.launch {
                            if (!confirmOwner(context, "Link $name to your account")) return@launch
                            working = true
                            try {
                                model.approveLink(approving, withHistory)
                                onDone()
                            } catch (e: Exception) {
                                error = e.userMessage
                            } finally {
                                working = false
                            }
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                ) {
                    if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Link")
                }
            }
            error?.let { Footer(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** The camera, reading QR codes that are link codes. */
@Composable
private fun QrScanner(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val analysis = remember { Executors.newSingleThreadExecutor() }
    AndroidView(
        factory = { viewContext ->
            val view = PreviewView(viewContext)
            val future = ProcessCameraProvider.getInstance(viewContext)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val reader = QRCodeReader()
                val hints = mapOf(DecodeHintType.TRY_HARDER to true)
                var found = false
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer.setAnalyzer(analysis) { image ->
                    try {
                        if (found) return@setAnalyzer
                        val plane = image.planes[0]
                        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                        val source = PlanarYUVLuminanceSource(
                            bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
                        )
                        val text = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
                        reader.reset()
                        if (text != null && text.startsWith(LINK_PREFIX)) {
                            found = true
                            ContextCompat.getMainExecutor(context).execute { onCode(text) }
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer) }
            }, ContextCompat.getMainExecutor(viewContext))
            view
        },
        onRelease = {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysis.shutdown()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ---- reset identity ----

/** For a lost or stolen device: a new account key, with the recovery key. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetIdentityPage(model: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recoveryKey by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onDone)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Reset identity", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { RoundBarButton(onDone, "Back", Glyphs.Back) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("If one of your devices was lost or stolen, this signs out every device except this one and takes them out of every chat.")
            Text(
                "Your chats end, and carry on when you or the other person start them again. Everyone sees a new safety number for you.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = recoveryKey,
                onValueChange = { recoveryKey = it },
                label = { Text("Recovery key (64 characters)") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        if (!confirmOwner(context, "Reset your identity")) return@launch
                        working = true
                        try {
                            model.resetIdentity(recoveryKey)
                            onDone()
                        } catch (e: Exception) {
                            error = e.userMessage
                        } finally {
                            working = false
                        }
                    }
                },
                enabled = !working && recoveryKey.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Reset identity")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ---- the owner's say-so ----

private tailrec fun Context.activity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Fingerprint, face or screen lock, for something only the owner may do. True on a device with no screen lock. */
private suspend fun confirmOwner(context: Context, title: String): Boolean {
    val activity = context.activity() ?: return false
    val available = BiometricManager.from(activity).canAuthenticate(AppLock.AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    if (!available) return true
    return suspendCancellableCoroutine { done ->
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(AppLock.AUTHENTICATORS)
            .build()
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (done.isActive) done.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (done.isActive) done.resume(false)
                }
            },
        ).authenticate(info)
    }
}
