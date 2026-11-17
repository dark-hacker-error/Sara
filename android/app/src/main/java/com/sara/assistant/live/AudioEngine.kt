package com.sara.assistant.live

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.sara.assistant.core.SaraLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Mic + speaker plumbing for the Live session.
 *
 * Input  : 16kHz, mono, 16-bit PCM (what the Live API expects).
 * Output : 24kHz, mono, 16-bit PCM (what the Live API produces).
 *
 * Barge-in support: [flushOutput] is called the moment the server reports `interrupted`,
 * and also pre-emptively when our own mic level says the user started talking. Both paths
 * matter — the server signal is authoritative but arrives after a round trip.
 */
class AudioEngine(private val scope: CoroutineScope) {

    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureJob: Job? = null

    private var track: AudioTrack? = null
    private var playbackJob: Job? = null
    private val playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var speaking = false

    @Volatile
    private var flushing = false

    /** Called for every captured mic frame (raw 16-bit PCM, little-endian). */
    var onInputFrame: ((ByteArray, Float) -> Unit)? = null

    // ---------------------------------------------------------------- microphone

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before starting
    fun startCapture() {
        if (captureJob?.isActive == true) return

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            SaraLog.e("AudioRecord buffer size unavailable, cannot capture")
            return
        }
        val bufferBytes = minBuffer * BUFFER_MULTIPLIER

        val recorder = AudioRecord(
            // VOICE_COMMUNICATION routes through the platform's echo canceller, which
            // matters a lot when the speaker and mic are both open.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            SaraLog.e("AudioRecord failed to initialize")
            recorder.release()
            return
        }

        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            }
        }.onFailure { SaraLog.w("Audio effects unavailable: ${it.message}") }

        record = recorder
        recorder.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val frame = ByteArray(FRAME_BYTES_IN)
            while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) continue

                val level = rmsLevel(frame, read)

                // Local barge-in heuristic: if the user is clearly talking while we're
                // playing Sara's voice, cut playback immediately instead of waiting for
                // the server round trip. Conservative threshold to avoid false stops.
                if (speaking && level > BARGE_IN_LEVEL) {
                    flushOutput()
                }

                if (!flushing) onInputFrame?.invoke(frame.copyOf(read), level)
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        runCatching {
            echoCanceler?.release()
            noiseSuppressor?.release()
        }
        echoCanceler = null
        noiseSuppressor = null
        record?.let { recorder ->
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                recorder.release()
            }
        }
        record = null
    }

    // ---------------------------------------------------------------- playback

    fun startPlayback() {
        if (playbackJob?.isActive == true) return

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = if (minBuffer > 0) minBuffer * BUFFER_MULTIPLIER else DEFAULT_OUT_BUFFER

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_OUT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        newTrack.play()
        track = newTrack

        playbackJob = scope.launch(Dispatchers.IO) {
            for (chunk in playbackQueue) {
                val active = track ?: break
                var offset = 0
                while (offset < chunk.size && isActive) {
                    val written = active.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
            }
        }
    }

    /** Queues model audio. Dropped silently when playback is not running. */
    fun enqueueOutput(pcm: ByteArray) {
        if (track == null) return
        speaking = true
        playbackQueue.trySend(pcm)
    }

    /**
     * Stops Sara mid-sentence. Called on server `interrupted` and on local barge-in.
     * Pause + flush is the portable way to clear a MODE_STREAM track on API 28+.
     */
    fun flushOutput() {
        if (flushing) return
        flushing = true
        runCatching {
            // Drain anything already queued so stale audio never replays.
            while (playbackQueue.tryReceive().isSuccess) { /* drain */ }
            track?.let { active ->
                active.pause()
                active.flush()
                active.play()
            }
        }.onFailure { SaraLog.w("AudioTrack flush failed: ${it.message}") }
        speaking = false
        flushing = false
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        // Drain rather than close: the engine is reused across sessions, and a closed
        // channel would make the next session's playback loop exit immediately.
        while (playbackQueue.tryReceive().isSuccess) { /* drain */ }
        track?.let { active ->
            runCatching {
                active.pause()
                active.flush()
                active.stop()
                active.release()
            }
        }
        track = null
        speaking = false
    }

    fun release() {
        stopCapture()
        stopPlayback()
    }

    // ---------------------------------------------------------------- helpers

    private fun rmsLevel(buffer: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
            // 16-bit little-endian
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort()
            val value = abs(sample.toInt())
            sum += value.toDouble() * value
            count++
            index += 2
        }
        if (count == 0) return 0f
        val rms = kotlin.math.sqrt(sum / count)
        return min(1f, (rms / Short.MAX_VALUE).toFloat() * 3f)
    }

    companion object {
        const val SAMPLE_RATE_IN = 16_000
        const val SAMPLE_RATE_OUT = 24_000

        /** 20ms of 16kHz mono 16-bit audio = 640 bytes. */
        const val FRAME_BYTES_IN = 640

        private const val BUFFER_MULTIPLIER = 4
        private const val DEFAULT_OUT_BUFFER = 24_000
        private const val BARGE_IN_LEVEL = 0.35f
    }
}
