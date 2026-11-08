package com.qwantarc.messenger.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.media.ExifInterface
import com.qwantarc.messenger.media.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Profile photos, decoded once per file. */
object ProfilePhotoCache {
    private val bitmaps = HashMap<String, ImageBitmap?>()

    fun bitmap(path: String): ImageBitmap? = bitmaps.getOrPut(path) {
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }
}

/** Square-crops and shrinks a picture to a profile photo: 512 px, JPEG, upright. */
object ProfilePhotoMaker {
    fun jpeg(context: Context, uri: Uri): ByteArray? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        jpeg(bytes)
    }.getOrNull()

    fun jpeg(bytes: ByteArray): ByteArray? = runCatching {
        // Decoded at a size near 1024 px, then turned upright from its EXIF.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotation = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val side = minOf(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            postRotate(rotation)
            val scale = 512f / side
            postScale(scale, scale)
        }
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side, matrix, true)
        // Written fresh: nothing of the original's metadata comes along.
        ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    }.getOrNull()
}

/** "Choose photo" (dark) and "Take photo" (outlined): a square JPEG for a profile. */
@Composable
fun ProfilePhotoButtons(working: Boolean, modifier: Modifier = Modifier, use: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<File?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { ProfilePhotoMaker.jpeg(context, uri) }?.let(use)
        }
    }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = shot
        shot = null
        if (saved && file != null) scope.launch {
            withContext(Dispatchers.IO) { ProfilePhotoMaker.jpeg(file.readBytes()).also { file.delete() } }?.let(use)
        } else file?.delete()
    }
    fun launchCamera() {
        val file = MediaFiles.newOutgoingFile(context, "jpg")
        shot = file
        runCatching { take.launch(MediaFiles.uriFor(context, file)) }.onFailure { shot = null; file.delete() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val t = tones()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier.weight(1f).height(46.dp).pressScale(source).clip(CircleShape).background(Palette.Ink)
                .clickable(interactionSource = source, indication = null, enabled = !working) {
                    choose.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            if (working) CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Cream, strokeWidth = 2.dp)
            else Text("Choose photo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Cream)
        }
        if (hasCamera) {
            val source2 = remember { MutableInteractionSource() }
            Box(
                Modifier.weight(1f).height(46.dp).pressScale(source2).clip(CircleShape).border(1.5.dp, t.text, CircleShape)
                    .clickable(interactionSource = source2, indication = null, enabled = !working) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                        else permission.launch(Manifest.permission.CAMERA)
                    }
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Text("Take photo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text) }
        }
    }
}
