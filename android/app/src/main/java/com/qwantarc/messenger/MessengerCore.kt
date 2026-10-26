package com.qwantarc.messenger

import android.content.Context
import com.qwantarc.messenger.core.MessengerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Rust core, opened once per process. The screens and the location
 * sharing service use the same client, so sharing carries on after the
 * screens are gone.
 */
object MessengerCore {
    private val mutex = Mutex()

    @Volatile
    var client: MessengerClient? = null
        private set
    private var openedUrl: String? = null

    /** Not backed up, and excluded from device transfer (see the manifest). */
    fun dataDir(context: Context): String = File(context.applicationContext.noBackupFilesDir, "messenger").path

    /**
     * Opens the core for a server, or returns the one already open for it.
     * The core keeps the server of a registered account, so this only
     * matters before sign-up.
     */
    suspend fun open(context: Context, serverUrl: String): MessengerClient = mutex.withLock {
        client?.let { if (openedUrl == serverUrl) return it }
        client?.close()
        client = null
        val opened = withContext(Dispatchers.IO) { MessengerClient(dataDir(context), serverUrl) }
        client = opened
        openedUrl = serverUrl
        opened
    }

    /** Opens the core with whatever server it last used (for the service). */
    suspend fun openExisting(context: Context): MessengerClient {
        client?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences("messenger", Context.MODE_PRIVATE)
        return open(context, prefs.getString("serverUrl", null) ?: DEFAULT_SERVER_URL)
    }
}
