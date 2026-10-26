package com.qwantarc.messenger.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pictures from files on this device, decoded only as big as they're shown
 * and kept a while, so scrolling back doesn't decode them again.
 */
object Pictures {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(96L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun key(path: String, maxSide: Int) = "$path#$maxSide"

    fun cached(path: String, maxSide: Int): Bitmap? = cache.get(key(path, maxSide))

    /** A photo, or a video's first frame, at least [maxSide] pixels on its long side where it has them. */
    suspend fun load(path: String, video: Boolean, maxSide: Int): Bitmap? {
        cached(path, maxSide)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { if (video) frame(path, maxSide) else decode(path, maxSide) } ?: return null
        cache.put(key(path, maxSide), bitmap)
        return bitmap
    }

    /** A thumbnail that came inside a message. They're tiny, so this is quick. */
    fun thumbnail(bytes: ByteArray, key: String): Bitmap? {
        val thumbnailKey = "thumbnail#$key"
        cache.get(thumbnailKey)?.let { return it }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        cache.put(thumbnailKey, bitmap)
        return bitmap
    }

    private fun decode(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = Preparation.sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun frame(path: String, maxSide: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
            } else {
                retriever.getFrameAtTime(0)?.let { Preparation.scaled(it, maxSide) }
            }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
