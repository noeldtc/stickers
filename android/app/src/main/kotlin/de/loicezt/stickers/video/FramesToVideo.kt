package de.loicezt.stickers.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Encodes a sequence of PNG frames, each with its own duration, into an H.264 MP4.
 *
 * Used to turn GIFs / animated WebPs into a video the rest of the pipeline already understands.
 */
class FramesToVideo {

    suspend fun run(framesDir: File, outputFile: File, durationsMs: List<Int>) =
        withContext(Dispatchers.Default) {
            val frames = framesDir.listFiles { f -> f.extension.equals("png", true) }
                ?.sortedBy { it.name } ?: emptyList()
            require(frames.isNotEmpty() && frames.size == durationsMs.size) {
                "Expected ${durationsMs.size} frames in $framesDir, found ${frames.size}"
            }

            val first = BitmapFactory.decodeFile(frames[0].absolutePath)
                ?: throw IllegalStateException("Cannot decode first frame")
            val width = (first.width and 1.inv()).coerceAtLeast(2)
            val height = (first.height and 1.inv()).coerceAtLeast(2)
            first.recycle()

            // Presentation time of every frame, plus a repeat of the last one at the very end so
            // that the last real frame keeps its duration.
            val ptsUs = LongArray(frames.size + 1)
            var acc = 0L
            for (i in frames.indices) {
                ptsUs[i] = acc
                acc += durationsMs[i].coerceAtLeast(1) * 1000L
            }
            ptsUs[frames.size] = acc

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            var muxer: MediaMuxer? = null
            var surface: Surface? = null
            var muxerStarted = false
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                surface = inputSurface
                encoder.start()

                val mux = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mux
                val info = MediaCodec.BufferInfo()
                var track = -1
                var outIndex = 0

                fun drain(untilEos: Boolean) {
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (true) {
                        val idx = encoder.dequeueOutputBuffer(info, if (untilEos) 10_000L else 0L)
                        when {
                            idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                if (!untilEos) return
                                if (System.nanoTime() > deadline) {
                                    throw IllegalStateException("Timed out waiting for the encoder")
                                }
                            }

                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                track = mux.addTrack(encoder.outputFormat)
                                mux.start()
                                muxerStarted = true
                            }

                            idx >= 0 -> {
                                val buffer = encoder.getOutputBuffer(idx)!!
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                                if (info.size > 0) {
                                    check(muxerStarted) { "Muxer not started" }
                                    // The surface stamps frames with the wall clock; use the real timeline.
                                    info.presentationTimeUs = ptsUs[min(outIndex, frames.size)]
                                    outIndex++
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    mux.writeSampleData(track, buffer, info)
                                }
                                encoder.releaseOutputBuffer(idx, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                            }
                        }
                    }
                }

                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val dst = Rect(0, 0, width, height)
                fun post(file: File) {
                    val bitmap: Bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        ?: throw IllegalStateException("Cannot decode ${file.name}")
                    val canvas = inputSurface.lockCanvas(null)
                    try {
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(bitmap, null, dst, paint)
                    } finally {
                        inputSurface.unlockCanvasAndPost(canvas)
                        bitmap.recycle()
                    }
                }

                for (file in frames) {
                    post(file)
                    drain(false)
                }
                post(frames.last())
                encoder.signalEndOfInputStream()
                drain(true)
            } finally {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                runCatching { if (muxerStarted) muxer?.stop() }
                runCatching { muxer?.release() }
                runCatching { surface?.release() }
            }
        }
}
