package com.qwantarc.messenger.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.log10
import kotlin.math.roundToInt

/** A voice message just recorded. */
class VoiceNote(val file: File, val durationMs: Long, val waveform: ByteArray)

/**
 * Records voice messages while the button is held: AAC in MPEG-4, with the
 * loudness sampled every 50 ms for the waveform the others see.
 */
class VoiceRecorder(private val context: Context) {
    var recording by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set

    /** Loudness so far, 0 to 1. */
    val levels = mutableStateListOf<Float>()

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val sample = object : Runnable {
        override fun run() {
            val recorder = recorder ?: return
            // The loudest moment since the last call, 0 to 32767. Speech sits
            // between about -40 dB and 0.
            val amplitude = runCatching { recorder.maxAmplitude }.getOrDefault(0)
            val decibels = if (amplitude > 0) 20 * log10(amplitude / 32767.0) else -160.0
            levels.add(((decibels + 50) / 50).coerceIn(0.0, 1.0).toFloat())
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            main.postDelayed(this, 50)
        }
    }

    fun start() {
        if (recording) return
        VoicePlayer.stop()
        val file = MediaFiles.newOutgoingFile(context, "m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(48_000)
            recorder.setOutputFile(file.path)
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            recorder.release()
            file.delete()
            throw MediaException("The microphone can't be used right now.")
        }
        this.recorder = recorder
        this.file = file
        startedAt = SystemClock.elapsedRealtime()
        levels.clear()
        elapsedMs = 0
        recording = true
        main.postDelayed(sample, 50)
    }

    /** Stops, and returns the note unless it was too short to be one. */
    fun finish(): VoiceNote? {
        val duration = SystemClock.elapsedRealtime() - startedAt
        val file = stop() ?: return null
        if (duration < SHORTEST_MS) {
            file.delete()
            return null
        }
        return VoiceNote(file, duration, waveform(levels))
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        stop()?.delete()
    }

    private fun stop(): File? {
        main.removeCallbacks(sample)
        val recorder = recorder ?: return null
        // stop() throws when nothing was recorded yet.
        val saved = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        this.recorder = null
        recording = false
        val file = file
        this.file = null
        if (!saved) file?.delete()
        return file.takeIf { saved }
    }

    companion object {
        /** Anything shorter was a tap on the button, not a message. */
        const val SHORTEST_MS = 1_000L

        /** At most 256 steps, each the loudest moment of its stretch, 0 to 255. */
        fun waveform(levels: List<Float>): ByteArray {
            val steps = minOf(MediaLimits.WAVEFORM_STEPS, levels.size)
            return ByteArray(steps) { step ->
                val start = step * levels.size / steps
                val end = maxOf(start + 1, (step + 1) * levels.size / steps)
                val loudest = levels.subList(start, end).maxOrNull() ?: 0f
                (loudest * 255).roundToInt().toByte()
            }
        }
    }
}

/** Plays voice messages, one at a time. */
object VoicePlayer {
    /** The note playing or paused. */
    var path by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var audio: AudioManager? = null
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val player = player ?: return
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            if (playing) main.postDelayed(this, 50)
        }
    }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Plays the note, or pauses it if it's playing. */
    fun toggle(context: Context, path: String) {
        val current = player
        if (this.path == path && current != null) {
            if (current.isPlaying) {
                current.pause()
                playing = false
            } else {
                current.start()
                playing = true
                main.post(tick)
            }
            return
        }
        stop()
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(path)
            player.prepare()
        } catch (e: Exception) {
            player.release()
            return
        }
        player.setOnCompletionListener { finished -> if (finished === this.player) stop() }
        audio = context.getSystemService(AudioManager::class.java)
        // Other sound goes quiet while the note plays.
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
            .also { audio?.requestAudioFocus(it) }
        player.start()
        this.player = player
        this.path = path
        durationMs = player.duration.coerceAtLeast(0)
        positionMs = 0
        playing = true
        main.post(tick)
    }

    /** Jumps within the note that's playing or paused. */
    fun seek(path: String, fraction: Float) {
        val player = player ?: return
        if (this.path != path) return
        player.seekTo((fraction.coerceIn(0f, 1f) * durationMs).roundToInt())
        positionMs = player.currentPosition
    }

    fun stop() {
        main.removeCallbacks(tick)
        player?.release()
        player = null
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
        path = null
        playing = false
        positionMs = 0
        durationMs = 0
    }
}
