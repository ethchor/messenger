package com.qwantarc.messenger.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Re-encodes videos with Media3's Transformer. */
@androidx.annotation.OptIn(UnstableApi::class)
object Transcoder {
    /**
     * Writes [input] to [output] as H.264 with AAC sound, scaled down so its
     * short side is at most [shortSide] pixels.
     */
    suspend fun transcode(context: Context, input: Uri, output: File, shortSide: Int) {
        val smaller = withContext(Dispatchers.IO) { shortSideOf(context, input) }?.let { it > shortSide } ?: true
        val effects = if (smaller) listOf(Presentation.createForShortSide(shortSide)) else emptyList()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(emptyList(), effects))
            .build()
        // Transformer works on the thread that made it, which needs a looper.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            continuation.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            continuation.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer.start(item, output.path)
                continuation.invokeOnCancellation {
                    ContextCompat.getMainExecutor(context).execute { transformer.cancel() }
                }
            }
        }
    }

    /** The video's short side in pixels, if it can be read. */
    private fun shortSideOf(context: Context, input: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, input)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (width != null && height != null) minOf(width, height) else null
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
