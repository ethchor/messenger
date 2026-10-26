package com.qwantarc.messenger.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import androidx.core.content.FileProvider
import com.qwantarc.messenger.core.NewAttachment
import java.io.File
import java.util.UUID

/** The core's limits on files (crates/core/src/media.rs). */
object MediaLimits {
    const val FILES = 10
    const val BYTES = 96L * 1024 * 1024
    const val THUMBNAIL_BYTES = 8 * 1024
    const val WAVEFORM_STEPS = 256
}

/** Why a file can't be sent, in words for the person sending it. */
class MediaException(message: String) : Exception(message)

/** A file made ready to send, and a small picture of it for the composer. */
class PreparedFile(val attachment: NewAttachment, val preview: Bitmap?)

/**
 * Where files wait on their way to the core, which moves them into its own
 * folder when the message is sent, and where files are linked under the
 * names they were sent with, for other apps to open.
 */
object MediaFiles {
    private fun outgoingDir(context: Context) = File(context.cacheDir, "outgoing").apply { mkdirs() }

    private fun sharedDir(context: Context) = File(context.cacheDir, "shared")

    fun newOutgoingFile(context: Context, extension: String): File =
        File(outgoingDir(context), "${UUID.randomUUID()}.$extension")

    /** A content URI for one of our files, for another app to write or read. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /**
     * A file of ours under the name it was sent with, for another app. A hard
     * link, so nothing is copied; a copy if linking fails.
     */
    fun shareable(context: Context, path: String, fileName: String): Uri {
        val source = File(path)
        var name = fileName.trim().replace('/', '_')
        if (name.isEmpty() || name.startsWith(".")) name = "file$name"
        if (!name.contains('.') && source.extension.isNotEmpty()) name += ".${source.extension}"
        val folder = File(sharedDir(context), UUID.randomUUID().toString()).apply { mkdirs() }
        val link = File(folder, name)
        try {
            Os.link(source.path, link.path)
        } catch (e: Exception) {
            source.copyTo(link, overwrite = true)
        }
        return uriFor(context, link)
    }

    /**
     * Clears out files on their way to the core and links made for other
     * apps. The links matter most: they would keep a file readable after the
     * core erased it.
     */
    fun removeTemporaryFiles(context: Context) {
        outgoingDir(context).deleteRecursively()
        sharedDir(context).deleteRecursively()
    }

    fun remove(attachments: List<NewAttachment>) {
        attachments.forEach { File(it.path).delete() }
    }
}
