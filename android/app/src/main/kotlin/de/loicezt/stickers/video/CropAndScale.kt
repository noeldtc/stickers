package de.loicezt.stickers.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Trims a video and scales it so its longest side is [TARGET_LONGEST_SIDE], re-encoding to H.264.
 * Observe [status] and [progress] for updates.
 *
 * The input is read with Media3's [MediaExtractorCompat] (a Java MP4 parser) because the
 * Realme/ColorOS system MediaExtractor returns 0 tracks for valid H.264 MP4s.
 */
@OptIn(UnstableApi::class)
class CropAndScale(private val context: Context) {

    enum class State {
        IDLE,       // Not doing anything
        RUNNING,    // Transcoding is in progress
        SUCCESS,    // Transcoding finished successfully
        FAILED,     // Transcoding failed with an error
        CANCELLED   // Transcoding was cancelled by the user
    }

    private val _status = MutableStateFlow(State.IDLE)
    val status = _status.asStateFlow()

    private val _progress = MutableStateFlow(ProgressState())
    val progress = _progress.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var transcodeJob: Job? = null

    companion object {
        private const val LOG_TAG = "CropAndScale"
        private const val TARGET_LONGEST_SIDE = 512
        private const val BIT_RATE = 2_000_000
        private const val MAX_CONSECUTIVE_TIMEOUTS = 3
    }

    /**
     * Starts the transcode; non-blocking.
     * @return false if a transcode is already running (the request is ignored).
     */
    @Synchronized
    fun start(
        inputFile: File,
        outputFile: File,
        startTimeUs: Long,
        endTimeUs: Long,
        maxFps: Int
    ): Boolean {
        if (_status.value == State.RUNNING) {
            Log.w(LOG_TAG, "Transcoding is already in progress. Ignoring new request.")
            return false
        }
        _progress.value = ProgressState()
        _status.value = State.RUNNING

        transcodeJob = scope.launch {
            try {
                doTranscode(inputFile, outputFile, startTimeUs, endTimeUs, maxFps)
                _progress.value = _progress.value.copy(
                    progress = 1f, currentFrame = _progress.value.totalFrames
                )
                _status.value = State.SUCCESS
                Log.d(LOG_TAG, "Transcoding finished successfully.")
            } catch (e: CancellationException) {
                outputFile.delete()
                _status.value = State.CANCELLED
                Log.d(LOG_TAG, "Transcoding was cancelled.")
            } catch (e: Exception) {
                outputFile.delete()
                _status.value = State.FAILED
                Log.e(LOG_TAG, "Transcoding failed with an exception.", e)
            }
        }
        return true
    }

    fun cancel() {
        transcodeJob?.cancel()
    }

    fun release() {
        scope.cancel()
    }

    /**
     * Runs the pipeline on a dedicated thread: the EGL context is bound to the thread that
     * created it, so setup, drawing and teardown must all happen on the same one.
     */
    private suspend fun doTranscode(
        inputFile: File,
        outputFile: File,
        startTimeUs: Long,
        endTimeUs: Long,
        maxFps: Int
    ) {
        val glExecutor = Executors.newSingleThreadExecutor()
        val drainExecutor = Executors.newSingleThreadExecutor()
        try {
            withContext(glExecutor.asCoroutineDispatcher()) {
                transcode(
                    inputFile, outputFile, startTimeUs, endTimeUs, maxFps,
                    drainExecutor.asCoroutineDispatcher()
                )
            }
        } finally {
            glExecutor.shutdown()
            drainExecutor.shutdown()
        }
    }

    private suspend fun transcode(
        inputFile: File,
        outputFile: File,
        startTimeUs: Long,
        endTimeUs: Long,
        maxFps: Int,
        drainDispatcher: CoroutineDispatcher
    ) {
        val extractor = MediaExtractorCompat(context)
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val glProcessor = CropScaleGL()
        val muxerStarted = AtomicBoolean(false)

        try {
            extractor.setDataSource(Uri.fromFile(inputFile), 0L)
            val videoTrackIndex = (0 until extractor.trackCount).indexOfFirst {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            }
            if (videoTrackIndex == -1) {
                val mimes = (0 until extractor.trackCount).map {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                }
                throw IllegalStateException(
                    "No video track found (size=${inputFile.length()}, tracks=$mimes)"
                )
            }

            val inputFormat = extractor.getTrackFormat(videoTrackIndex)
            extractor.selectTrack(videoTrackIndex)

            // MediaExtractorCompat may omit KEY_DURATION; the requested end is already clamped by Dart.
            val originalDurationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                endTimeUs
            }
            val effectiveStartTimeUs = max(0L, startTimeUs)
            // WhatsApp rejects animated stickers longer than 10 s.
            val effectiveEndTimeUs =
                min(min(endTimeUs, originalDurationUs), effectiveStartTimeUs + 10_000_000L)
            val trimmedDurationUs = effectiveEndTimeUs - effectiveStartTimeUs
            if (trimmedDurationUs <= 0) throw IllegalArgumentException("End time must be after start time.")

            extractor.seekTo(effectiveStartTimeUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val videoWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val videoHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)

            val originalFrameRate = if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                // MediaExtractorCompat reports the frame rate as a Float.
                runCatching { inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }
                    .getOrElse { inputFormat.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }
            } else {
                24
            }
            // The container's KEY_FRAME_RATE is unreliable (a ~57 fps clip reported 15 on a Realme),
            // so target maxFps: frame dropping never raises the rate of a slower source.
            val targetFrameRate = maxFps.coerceAtLeast(1)
            Log.i(LOG_TAG, "Frame rate: reported=$originalFrameRate target=$targetFrameRate")
            val totalFrames = ((trimmedDurationUs / 1_000_000.0) * targetFrameRate).toInt()
            _progress.value = ProgressState(totalFrames = totalFrames)

            val rotation = if (inputFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                inputFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }
            val rotatedWidth = if (rotation == 90 || rotation == 270) videoHeight else videoWidth
            val rotatedHeight = if (rotation == 90 || rotation == 270) videoWidth else videoHeight

            val outputWidth: Int
            val outputHeight: Int
            if (rotatedWidth > rotatedHeight) {
                outputWidth = TARGET_LONGEST_SIDE
                outputHeight =
                    (TARGET_LONGEST_SIDE * (rotatedHeight.toFloat() / rotatedWidth.toFloat())).toInt()
            } else {
                outputHeight = TARGET_LONGEST_SIDE
                outputWidth =
                    (TARGET_LONGEST_SIDE * (rotatedWidth.toFloat() / rotatedHeight.toFloat())).toInt()
            }
            // H.264 needs even dimensions.
            val finalOutputWidth = max(2, outputWidth and 1.inv())
            val finalOutputHeight = max(2, outputHeight and 1.inv())

            val mux = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val outputFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, finalOutputWidth, finalOutputHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            enc.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = enc.createInputSurface()
            enc.start()

            glProcessor.setup(encoderInputSurface, finalOutputWidth, finalOutputHeight)
            val dec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(inputFormat, glProcessor.decoderInputSurface, null, 0)
            dec.start()

            // coroutineScope makes the drain job a child: if it fails, the main loop is
            // cancelled; if the main loop is cancelled, the drain job stops too.
            coroutineScope {
                val drainJob = launch(drainDispatcher) {
                    val bufferInfo = MediaCodec.BufferInfo()
                    var trackIndex = -1
                    while (isActive) {
                        val index = enc.dequeueOutputBuffer(bufferInfo, 10_000L)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            trackIndex = mux.addTrack(enc.outputFormat)
                            mux.start()
                            muxerStarted.set(true)
                        } else if (index >= 0) {
                            val data = enc.getOutputBuffer(index)!!
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0 // config data is carried by the track format
                            }
                            if (bufferInfo.size > 0) {
                                check(muxerStarted.get()) { "Muxer not started" }
                                mux.writeSampleData(trackIndex, data, bufferInfo)
                            }
                            enc.releaseOutputBuffer(index, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }

                val decoderBufferInfo = MediaCodec.BufferInfo()
                var isInputDone = false
                var isDecoderOutputDone = false
                var currentFrame = 0
                var consecutiveTimeouts = 0
                var lastRenderedTimestampNs = -1L
                val frameIntervalNs = 1_000_000_000L / targetFrameRate

                while (!isDecoderOutputDone && isActive) {
                    if (!isInputDone) {
                        val inputBufferIndex = dec.dequeueInputBuffer(10_000L)
                        if (inputBufferIndex >= 0) {
                            val sampleTime = extractor.sampleTime
                            if (sampleTime < 0 || sampleTime > effectiveEndTimeUs) {
                                dec.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputDone = true
                            } else {
                                val sampleSize = extractor.readSampleData(
                                    dec.getInputBuffer(inputBufferIndex)!!, 0
                                )
                                dec.queueInputBuffer(inputBufferIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputBufferIndex = dec.dequeueOutputBuffer(decoderBufferInfo, 10_000L)
                    if (outputBufferIndex >= 0) {
                        if (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            enc.signalEndOfInputStream()
                            isDecoderOutputDone = true
                        }

                        val isFrameInRange = decoderBufferInfo.size > 0 &&
                                decoderBufferInfo.presentationTimeUs >= effectiveStartTimeUs

                        // Frame dropping to honour the target frame rate.
                        var renderThisFrame = isFrameInRange
                        if (isFrameInRange) {
                            val currentTimestampNs = decoderBufferInfo.presentationTimeUs * 1000
                            if (lastRenderedTimestampNs != -1L &&
                                currentTimestampNs - lastRenderedTimestampNs < frameIntervalNs
                            ) {
                                renderThisFrame = false
                            } else {
                                lastRenderedTimestampNs = currentTimestampNs
                            }
                        }

                        dec.releaseOutputBuffer(outputBufferIndex, renderThisFrame)

                        if (renderThisFrame) {
                            try {
                                glProcessor.awaitNewFrame()
                                consecutiveTimeouts = 0
                                val adjustedTimestampUs =
                                    decoderBufferInfo.presentationTimeUs - effectiveStartTimeUs
                                glProcessor.drawFrame(adjustedTimestampUs * 1000)

                                currentFrame++
                                val fraction = (adjustedTimestampUs.toFloat() /
                                        trimmedDurationUs.toFloat()).coerceIn(0f, 1f)
                                _progress.value = ProgressState(fraction, currentFrame, totalFrames)
                            } catch (e: TimeoutException) {
                                if (++consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) throw e
                                Log.w(LOG_TAG, "Timeout waiting for frame; the frame was dropped.")
                            }
                        }
                    }
                }
                drainJob.join()
            }

            if (!muxerStarted.get()) throw IllegalStateException("No frames were encoded")
            // Stop explicitly so a failure here is reported instead of leaving a corrupt file.
            mux.stop()
            muxerStarted.set(false)
        } finally {
            runCatching { extractor.release() }
            decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            encoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            muxer?.let {
                if (muxerStarted.get()) runCatching { it.stop() }
                runCatching { it.release() }
            }
            runCatching { glProcessor.release() }
        }
    }
}
