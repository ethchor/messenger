package com.qwantarc.messenger.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.qwantarc.messenger.core.AttachmentKind
import com.qwantarc.messenger.core.NewAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Turns what people pick into files to send. Photos are made smaller and
 * re-encoded, and videos re-encoded, so neither carries where or when it was
 * taken, or on what. Each gets a tiny thumbnail that travels inside the
 * message. Documents and voice messages go as they are.
 */
object Preparation {
    private const val PHOTO_SIDE = 2048
    private const val PREVIEW_SIDE = 240

    /** A photo from the library, the camera or a file. GIFs go as they are, so they keep moving. */
    suspend fun photo(context: Context, uri: Uri): PreparedFile = withContext(Dispatchers.IO) {
        if (context.contentResolver.getType(uri) == "image/gif") return@withContext gif(context, uri)
        val decoded = decodeUpright(context, uri, PHOTO_SIDE) ?: throw MediaException("This photo can't be opened.")
        // JPEG has no transparency: see-through parts go white rather than black.
        val bitmap = if (decoded.hasAlpha()) onWhite(decoded) else decoded
        val file = MediaFiles.newOutgoingFile(context, "jpg")
        // Bitmap.compress writes no EXIF: nothing about where, when or on what.
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        PreparedFile(
            NewAttachment(
                path = file.path,
                kind = AttachmentKind.PHOTO,
                contentType = "image/jpeg",
                fileName = "photo.jpg",
                width = bitmap.width.toUInt(),
                height = bitmap.height.toUInt(),
                durationMs = 0u,
                thumbnail = thumbnail(bitmap),
                waveform = ByteArray(0),
            ),
            preview = scaled(bitmap, PREVIEW_SIDE),
        )
    }

    private fun gif(context: Context, uri: Uri): PreparedFile {
        val file = MediaFiles.newOutgoingFile(context, "gif")
        copyLimited(context, uri, file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val first = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PREVIEW_SIDE) }
            BitmapFactory.decodeFile(file.path, options)
        } else {
            null
        }
        if (first == null) {
            file.delete()
            throw MediaException("This photo can't be opened.")
        }
        return PreparedFile(
            NewAttachment(
                path = file.path,
                kind = AttachmentKind.PHOTO,
                contentType = "image/gif",
                fileName = "animation.gif",
                width = bounds.outWidth.toUInt(),
                height = bounds.outHeight.toUInt(),
                durationMs = 0u,
                thumbnail = thumbnail(first),
                waveform = ByteArray(0),
            ),
            preview = scaled(first, PREVIEW_SIDE),
        )
    }

    /**
     * A video re-encoded to H.264 and AAC, its short side at most 720 px,
     * then stripped of its metadata. If re-encoding fails, the original goes,
     * stripped the same way, as long as it's an MP4.
     */
    suspend fun video(context: Context, uri: Uri): PreparedFile {
        val output = MediaFiles.newOutgoingFile(context, "mp4")
        try {
            try {
                Transcoder.transcode(context, uri, output, shortSide = 720)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { copyLimited(context, uri, output) }
            }
            return withContext(Dispatchers.IO) { finishVideo(output) }
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }

    private fun finishVideo(file: File): PreparedFile {
        if (!Mp4Scrubber.scrub(file)) throw MediaException("This video can't be sent.")
        if (file.length() > MediaLimits.BYTES) throw MediaException("This video is too long to send.")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
            val turned = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0L
            val width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW_SIDE, PREVIEW_SIDE)
            } else {
                retriever.getFrameAtTime(0)?.let { scaled(it, PREVIEW_SIDE) }
            }
            return PreparedFile(
                NewAttachment(
                    path = file.path,
                    kind = AttachmentKind.VIDEO,
                    contentType = "video/mp4",
                    fileName = "video.mp4",
                    width = (if (turned) height else width).toUInt(),
                    height = (if (turned) width else height).toUInt(),
                    durationMs = number(MediaMetadataRetriever.METADATA_KEY_DURATION).coerceIn(0L, UInt.MAX_VALUE.toLong()).toUInt(),
                    thumbnail = frame?.let(::thumbnail),
                    waveform = ByteArray(0),
                ),
                preview = frame,
            )
        } catch (e: Exception) {
            throw MediaException("This video can't be sent.")
        } finally {
            retriever.release()
        }
    }

    /** A document as it is. */
    suspend fun file(context: Context, uri: Uri): PreparedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) name = cursor.getString(nameColumn)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        if ((size ?: 0L) > MediaLimits.BYTES) throw MediaException("Files can be at most 96 MB.")
        val fileName = name?.takeIf { it.isNotBlank() } ?: "file"
        val extension = fileName.substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
        val type = resolver.getType(uri)
            ?: extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
        val copy = MediaFiles.newOutgoingFile(context, extension ?: "bin")
        copyLimited(context, uri, copy)
        PreparedFile(
            NewAttachment(
                path = copy.path,
                kind = AttachmentKind.FILE,
                contentType = type,
                fileName = fileName,
                width = 0u,
                height = 0u,
                durationMs = 0u,
                thumbnail = null,
                waveform = ByteArray(0),
            ),
            preview = null,
        )
    }

    fun voiceNote(note: VoiceNote) = NewAttachment(
        path = note.file.path,
        kind = AttachmentKind.VOICE,
        contentType = "audio/mp4",
        fileName = "voice-message.m4a",
        width = 0u,
        height = 0u,
        durationMs = note.durationMs.coerceIn(0L, UInt.MAX_VALUE.toLong()).toUInt(),
        thumbnail = null,
        waveform = note.waveform,
    )

    // ---- pictures ----

    /** The picture at most [maxSide] pixels on its long side, the right way up. */
    fun decodeUpright(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val decoded = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    // Whole steps down while decoding; the exact size comes after.
                    decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, maxSide))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                decodeWithExif(context, uri, maxSide)
            }
        } catch (e: Exception) {
            null
        }
        return decoded?.let { scaled(it, maxSide) }
    }

    /** Before Android 9: BitmapFactory, turned the way the EXIF says. */
    private fun decodeWithExif(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = orientationMatrix(orientation) ?: return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun orientationMatrix(orientation: Int): Matrix? = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> return null
        }
    }

    /** The largest power of two that keeps the long side at least [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /**
     * The JPEG that travels inside the message, so the picture shows (blurred)
     * before the file is fetched: as big as fits in 8 KiB, up to 96 px.
     */
    fun thumbnail(bitmap: Bitmap): ByteArray? {
        var side = 96
        while (side >= 24) {
            val bytes = ByteArrayOutputStream().also { scaled(bitmap, side).compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
            if (bytes.size <= MediaLimits.THUMBNAIL_BYTES) return bytes
            side = (side * 0.8f).roundToInt()
        }
        return null
    }

    fun scaled(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun onWhite(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return result
    }

    /** Copies at most the largest file that can be sent. */
    private fun copyLimited(context: Context, uri: Uri, to: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw MediaException("This file can't be opened.")
        var tooBig = false
        input.use { source ->
            to.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MediaLimits.BYTES) {
                        tooBig = true
                        break
                    }
                    sink.write(buffer, 0, read)
                }
            }
        }
        if (tooBig) {
            to.delete()
            throw MediaException("Files can be at most 96 MB.")
        }
    }
}
