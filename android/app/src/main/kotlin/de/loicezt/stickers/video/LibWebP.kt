package de.loicezt.stickers.video

import androidx.annotation.Keep
import java.nio.ByteBuffer

@Keep
enum class WebPImageHint {
    DEFAULT,
    PICTURE,
    PHOTO,
    GRAPH,
    LAST
}

@Keep
data class WebPConfig(
    val lossless: Boolean?,
    val quality: Float?,
    val method: Int?,
    val imageHint: WebPImageHint?,
    val targetSize: Int?,
    val targetPSNR: Float?,
    val segments: Int?,
    val snsStrength: Int?,
    val filterStrength: Int?,
    val filterSharpness: Int?,
    val filterType: Int?,
    val autofilter: Int?,
    val alphaCompression: Int?,
    val alphaFiltering: Int?,
    val alphaQuality: Int?,
    val pass: Int?,
    val showCompressed: Int?,
    val preprocessing: Int?,
    val partitions: Int?,
    val partitionLimit: Int?,
    val emulateJpegSize: Int?,
    val threadLevel: Int?,
    val lowMemory: Int?,
    val nearLossless: Int?,
    val exact: Int?,
){
    companion object {
        fun fromMap(map: Map<*, *>): WebPConfig {
            fun boolToInt(value: Any?): Int? = (value as? Boolean)?.let { if (it) 1 else 0 }

            val imageHint = (map["imageHint"] as? String)?.let {
                when (it) {
                    "defaultHint" -> WebPImageHint.DEFAULT
                    "picture" -> WebPImageHint.PICTURE
                    "photo" -> WebPImageHint.PHOTO
                    "graph" -> WebPImageHint.GRAPH
                    else -> null
                }
            }

            return WebPConfig(
                lossless = map["lossless"] as? Boolean,
                quality = (map["quality"] as? Double)?.toFloat(),
                method = map["method"] as? Int,
                imageHint = imageHint,
                targetSize = map["targetSize"] as? Int,
                targetPSNR = (map["targetPSNR"] as? Double)?.toFloat(),
                segments = map["segments"] as? Int,
                snsStrength = map["snsStrength"] as? Int,
                filterStrength = map["filterStrength"] as? Int,
                filterSharpness = map["filterSharpness"] as? Int,
                filterType = map["filterType"] as? Int,
                autofilter = boolToInt(map["autofilter"]),
                alphaCompression = map["alphaCompression"] as? Int,
                alphaFiltering = map["alphaFiltering"] as? Int,
                alphaQuality = map["alphaQuality"] as? Int,
                pass = map["pass"] as? Int,
                showCompressed = boolToInt(map["showCompressed"]),
                preprocessing = map["preprocessing"] as? Int,
                partitions = map["partitions"] as? Int,
                partitionLimit = map["partitionLimit"] as? Int,
                emulateJpegSize = boolToInt(map["emulateJpegSize"]),
                threadLevel = boolToInt(map["threadLevel"]),
                lowMemory = boolToInt(map["lowMemory"]),
                nearLossless = map["nearLossless"] as? Int,
                exact = boolToInt(map["exact"])
            )
        }
    }
}

class LibWebP {
    /**
     * Retrieves the width and height of a WebP image.
     * @param data The raw byte array of the .webp file.
     * @return An IntArray containing [width, height], or null on failure.
     */
    external fun nativeGetInfo(data: ByteArray): IntArray?

    /**
     * Decodes a WebP image into a pre-allocated raw RGBA pixel buffer.
     * @param data The raw byte array of the .webp file.
     * @param outBuffer A direct ByteBuffer to write the RGBA pixel data into.
     * @param stride The number of bytes per row in the output buffer (width * 4).
     * @return True if decoding was successful.
     */
    external fun nativeDecode(data: ByteArray, outBuffer: ByteBuffer, stride: Int): Boolean

    /**
     * Creates a WebP animation encoder.
     * @return An opaque handle, or 0 if initialization failed.
     */
    external fun nativeInitEncoder(width: Int, height: Int, config: WebPConfig): Long

    /**
     * Adds a single RGBA frame to the WebP animation.
     * @param frameBuffer A direct ByteBuffer containing width*height*4 bytes of RGBA.
     * @param timestampMs The timestamp for this frame in milliseconds (strictly increasing).
     * @return False if the frame could not be encoded.
     */
    external fun nativeAddFrame(handle: Long, frameBuffer: ByteBuffer, timestampMs: Int): Boolean

    /**
     * Assembles the animation and frees the encoder; the handle is invalid afterwards.
     * @param endTimestampMs Time at which the last frame stops being shown.
     * @return The encoded file bytes, or null on failure.
     */
    external fun nativeReleaseEncoder(handle: Long, endTimestampMs: Int): ByteArray?

    /** Frees an encoder without assembling it (error and cancel paths). */
    external fun nativeDestroyEncoder(handle: Long)

    companion object {
        init {
            System.loadLibrary("stickers")
        }
    }
}