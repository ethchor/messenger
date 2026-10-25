package com.qwantarc.messenger.media

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qwantarc.messenger.core.NewAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Files picked for the next message in a chat or a thread, made ready to
 * send as soon as they're picked. They go with the message, its text as
 * their caption. The view model keeps them, so they outlive rotating the
 * screen and the trip to the photo picker or the camera.
 */
class AttachmentTray(private val context: Context, private val scope: CoroutineScope) {
    sealed interface State {
        data object Preparing : State

        class Ready(val file: PreparedFile) : State

        class Failed(val message: String) : State
    }

    class Item(val id: Long, state: State) {
        var state by mutableStateOf(state)
    }

    val items = mutableStateListOf<Item>()
    private var nextId = 0L

    val isPreparing: Boolean get() = items.any { it.state is State.Preparing }
    val hasFiles: Boolean get() = items.any { it.state is State.Ready }

    /** How many more fit in one message. */
    val room: Int get() = (MediaLimits.FILES - items.size).coerceAtLeast(0)

    /** Photos and videos from the photo picker. */
    fun addMedia(uris: List<Uri>) {
        for (uri in uris.take(room)) {
            val video = context.contentResolver.getType(uri)?.startsWith("video/") == true
            prepare { if (video) Preparation.video(context, uri) else Preparation.photo(context, uri) }
        }
    }

    fun addFiles(uris: List<Uri>) {
        for (uri in uris.take(room)) {
            prepare { Preparation.file(context, uri) }
        }
    }

    /** What the camera saved to [file], which goes once it's prepared. */
    fun addCapture(file: File, video: Boolean) {
        if (room == 0) {
            file.delete()
            return
        }
        prepare {
            try {
                val uri = Uri.fromFile(file)
                if (video) Preparation.video(context, uri) else Preparation.photo(context, uri)
            } finally {
                file.delete()
            }
        }
    }

    fun remove(id: Long) {
        val item = items.firstOrNull { it.id == id } ?: return
        (item.state as? State.Ready)?.let { MediaFiles.remove(listOf(it.file.attachment)) }
        items.remove(item)
    }

    /** Hands the prepared files over for sending and empties the tray. */
    fun take(): List<NewAttachment> {
        val files = items.mapNotNull { (it.state as? State.Ready)?.file?.attachment }
        items.clear()
        return files
    }

    /** Empties the tray, deleting what was prepared. */
    fun clear() = MediaFiles.remove(take())

    private fun prepare(work: suspend () -> PreparedFile) {
        val item = Item(nextId++, State.Preparing)
        items.add(item)
        scope.launch {
            val state = try {
                State.Ready(work())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: "This file can't be sent.")
            }
            if (items.any { it === item }) {
                item.state = state
            } else if (state is State.Ready) {
                // Taken out while it was being prepared.
                MediaFiles.remove(listOf(state.file.attachment))
            }
        }
    }
}
