package de.loicezt.stickers.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Overlays a static WebP on every frame of a video and encodes the result as an animated
 * WebP. Observe [status] and [progress] for updates.
 */
class OverlayAndEncode {

    enum class State {
        IDLE, RUNNING, SUCCESS, FAILED, CANCELLED
    }

    private val _status = MutableStateFlow(State.IDLE)
    val status = _status.asStateFlow()

    private val _progress = MutableStateFlow(ProgressState())
    val progress = _progress.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var encodeJob: Job? = null

    companion object {
        private const val LOG_TAG = "OverlayAndEncode"
        private const val OUTPUT_DIMENSION = 512
        private const val MAX_CONSECUTIVE_TIMEOUTS = 3
        private const val MAX_DURATION_MS = 10_000
    }

    /**
     * Starts the overlay + WebP encode; non-blocking.
     * @param maxFps The maximum frames per second of the output.
     * @return false if an encode is already running (the request is ignored).
     */
    @Synchronized
    fun start(
        videoFile: File,
        overlayFile: File,
        outputFile: File,
        config: WebPConfig,
        maxFps: Int,
        fitMode: String = "fit"
    ): Boolean {
        if (_status.value == State.RUNNING) {
            Log.w(LOG_TAG, "Encoding is already in progress. Ignoring new request.")
            return false
        }
        _progress.value = ProgressState()
        _status.value = State.RUNNING

        encodeJob = scope.launch {
            // Write to a temp file and rename, so a failure never leaves a partial output.
            val tempFile = File(outputFile.path + ".tmp")
            try {
                val webpData = doOverlayAndEncode(videoFile, overlayFile, config, maxFps, fitMode)
                    ?: throw IllegalStateException("Encoding produced no data.")
                tempFile.writeBytes(webpData)
                if (!tempFile.renameTo(outputFile)) {
                    outputFile.delete()
                    if (!tempFile.renameTo(outputFile)) {
                        throw IllegalStateException("Could not move result to ${outputFile.path}")
                    }
                }
                _progress.value = _progress.value.copy(
                    progress = 1f, currentFrame = _progress.value.totalFrames
                )
                _status.value = State.SUCCESS
                Log.d(LOG_TAG, "Encoding finished successfully.")
            } catch (e: CancellationException) {
                tempFile.delete()
                _status.value = State.CANCELLED
                Log.d(LOG_TAG, "Encoding was cancelled.")
            } catch (e: Exception) {
                tempFile.delete()
                _status.value = State.FAILED
                Log.e(LOG_TAG, "Encoding failed with an exception.", e)
            }
        }
        return true
    }

    fun cancel() {
        encodeJob?.cancel()
    }

    fun release() {
        scope.cancel()
    }

    /**
     * Runs on a dedicated thread: the EGL context is bound to the thread that created it,
     * so setup, drawing and teardown must all happen on the same one.
     */
    private suspend fun doOverlayAndEncode(
        videoFile: File,
        overlayFile: File,
        config: WebPConfig,
        maxFps: Int,
        fitMode: String = "fit"
    ): ByteArray? {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return withContext(executor.asCoroutineDispatcher()) {
                encode(videoFile, overlayFile, config, maxFps, fitMode)
            }
        } finally {
            executor.shutdown()
        }
    }

    private suspend fun encode(
        videoFile: File,
        overlayFile: File,
        config: WebPConfig,
        maxFps: Int,
        fitMode: String = "fit"
    ): ByteArray? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val glProcessor = OverlayGL()
        val webpEncoder = LibWebP()
        var encoderHandle = 0L

        try {
            // 1. Extractor setup
            extractor.setDataSource(videoFile.absolutePath)
            val videoTrackIndex = (0 until extractor.trackCount).indexOfFirst {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            }
            if (videoTrackIndex == -1) throw IllegalStateException("No video track found")

            val inputFormat = extractor.getTrackFormat(videoTrackIndex)
            extractor.selectTrack(videoTrackIndex)

            val videoWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val videoHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val rotation = if (inputFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                inputFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }

            val originalFrameRate = if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else {
                30
            }
            // The container's KEY_FRAME_RATE is unreliable (it reported 10 for a 15+ fps clip on a
            // Realme), so target maxFps: frame dropping never raises the rate of a slower source.
            val targetFrameRate = maxFps.coerceAtLeast(1)
            Log.i(LOG_TAG, "Frame rate: reported=$originalFrameRate target=$targetFrameRate")
            val totalFrames = ((durationUs / 1_000_000.0) * targetFrameRate).toInt()
            _progress.value = ProgressState(totalFrames = totalFrames)

            // 2. Overlay bitmap (uploaded to the GPU once, then recycled)
            val webpBytes = overlayFile.readBytes()
            val webpInfo = webpEncoder.nativeGetInfo(webpBytes)
                ?: throw IllegalStateException("Could not read WebP overlay info.")
            val overlayWidth = webpInfo[0]
            val overlayHeight = webpInfo[1]

            val overlayBitmap = createBitmap(overlayWidth, overlayHeight)
            try {
                val pixelBufferForOverlay =
                    ByteBuffer.allocateDirect(overlayWidth * overlayHeight * 4)
                if (!webpEncoder.nativeDecode(webpBytes, pixelBufferForOverlay, overlayWidth * 4)) {
                    throw IllegalStateException("Failed to decode WebP overlay image.")
                }
                pixelBufferForOverlay.rewind()
                overlayBitmap.copyPixelsFromBuffer(pixelBufferForOverlay)

                glProcessor.setup(
                    OUTPUT_DIMENSION, OUTPUT_DIMENSION, videoWidth, videoHeight, rotation,
                    overlayBitmap, fitMode
                )
            } finally {
                overlayBitmap.recycle()
            }

            encoderHandle = webpEncoder.nativeInitEncoder(OUTPUT_DIMENSION, OUTPUT_DIMENSION, config)
            if (encoderHandle == 0L) throw IllegalStateException("Could not initialize WebP encoder.")

            val dec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(inputFormat, glProcessor.decoderInputSurface, null, 0)
            dec.start()

            val handle = encoderHandle
            var lastTimestampMs = -1
            fun addFrame(buffer: ByteBuffer, timestampMs: Int) {
                // The encoder requires strictly increasing timestamps.
                val ts = if (timestampMs <= lastTimestampMs) lastTimestampMs + 1 else timestampMs
                if (!webpEncoder.nativeAddFrame(handle, buffer, ts)) {
                    throw IllegalStateException("Failed to encode frame at $ts ms.")
                }
                lastTimestampMs = ts
            }

            // 3. Decode -> draw -> read back -> encode
            val decoderBufferInfo = MediaCodec.BufferInfo()
            var isInputDone = false
            var isDecoderOutputDone = false
            var currentFrame = 0
            var consecutiveTimeouts = 0
            var firstPtsUs = -1L
            var lastProcessedTimestampUs = -1L
            val frameIntervalUs = 1_000_000L / targetFrameRate
            // The last frame stays visible for one frame interval.
            val lastFrameDurationMs = max(1, (1000.0 / targetFrameRate).roundToInt())

            while (!isDecoderOutputDone && currentCoroutineContext().isActive) {
                if (!isInputDone) {
                    val inputBufferIndex = dec.dequeueInputBuffer(10_000L)
                    if (inputBufferIndex >= 0) {
                        val sampleSize =
                            extractor.readSampleData(dec.getInputBuffer(inputBufferIndex)!!, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isInputDone = true
                        } else {
                            dec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputBufferIndex = dec.dequeueOutputBuffer(decoderBufferInfo, 10_000L)
                if (outputBufferIndex >= 0) {
                    if (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isDecoderOutputDone = true
                    }

                    var processThisFrame = decoderBufferInfo.size > 0
                    if (processThisFrame) {
                        val currentTimestampUs = decoderBufferInfo.presentationTimeUs
                        if (lastProcessedTimestampUs != -1L &&
                            currentTimestampUs - lastProcessedTimestampUs < frameIntervalUs
                        ) {
                            processThisFrame = false // Drop frame
                        } else {
                            lastProcessedTimestampUs = currentTimestampUs
                        }
                    }
                    // WhatsApp rejects animated stickers longer than 10 s: stop once the next frame
                    // (plus the last frame's own duration) would exceed that.
                    if (processThisFrame && firstPtsUs >= 0) {
                        val nextMs = (decoderBufferInfo.presentationTimeUs - firstPtsUs + 500) / 1000
                        if (nextMs + lastFrameDurationMs > MAX_DURATION_MS) {
                            processThisFrame = false
                            isDecoderOutputDone = true
                        }
                    }

                    dec.releaseOutputBuffer(outputBufferIndex, processThisFrame)

                    if (processThisFrame) {
                        try {
                            glProcessor.awaitNewFrame()
                            consecutiveTimeouts = 0
                            glProcessor.drawFrame()

                            val ptsUs = decoderBufferInfo.presentationTimeUs
                            if (firstPtsUs < 0) firstPtsUs = ptsUs
                            // Relative to the first frame, rounded to the nearest ms.
                            val timestampMs = ((ptsUs - firstPtsUs + 500) / 1000).toInt()

                            glProcessor.submitReadback(timestampMs)?.let { ready ->
                                addFrame(ready, glProcessor.readyTimestampMs)
                                glProcessor.releaseReadback()
                            }

                            currentFrame++
                            val fraction = if (durationUs > 0) {
                                (ptsUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            _progress.value = ProgressState(fraction, currentFrame, totalFrames)
                        } catch (e: TimeoutException) {
                            if (++consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) throw e
                            Log.w(LOG_TAG, "Timeout waiting for frame; the frame was dropped.")
                        }
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            glProcessor.finishReadback()?.let { ready ->
                addFrame(ready, glProcessor.readyTimestampMs)
                glProcessor.releaseReadback()
            }
            if (currentFrame == 0) throw IllegalStateException("No frames were encoded")

            val result = webpEncoder.nativeReleaseEncoder(
                encoderHandle, lastTimestampMs + lastFrameDurationMs
            )
            encoderHandle = 0L // the native side frees the encoder even when it fails
            return result
        } finally {
            if (encoderHandle != 0L) webpEncoder.nativeDestroyEncoder(encoderHandle)
            runCatching { extractor.release() }
            decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { glProcessor.release() }
        }
    }
}
