package de.loicezt.stickers

import android.os.SystemClock
import de.loicezt.stickers.video.CropAndScale
import de.loicezt.stickers.video.OverlayAndEncode
import de.loicezt.stickers.video.ProgressState
import de.loicezt.stickers.video.WebPConfig
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : FlutterActivity() {
    private val METHOD_CHANNEL_NAME = "de.loicezt.stickers/methods"
    private val TRIM_CHANNEL_NAME = "de.loicezt.stickers/progress_trim"
    private val ECODE_CHANNEL_NAME = "de.loicezt.stickers/progress_encode"

    /** Minimum time between RUNNING progress events sent to Flutter. */
    private val PROGRESS_INTERVAL_MS = 100L

    private lateinit var cropAndScale: CropAndScale
    private lateinit var overlayAndEncode: OverlayAndEncode

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        cropAndScale = CropAndScale(applicationContext)
        overlayAndEncode = OverlayAndEncode()

        // 1. MethodChannel: commands from Flutter
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL_NAME
        ).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "startTrim" -> {
                        val inputFile = File(call.stringArg("inputFile"))
                        val outputFile = File(call.stringArg("outputFile"))
                        val startTimeUs = call.longArg("startTimeUs")
                        val endTimeUs = call.longArg("endTimeUs")
                        require(inputFile.isFile) { "Input file does not exist: ${inputFile.path}" }
                        require(endTimeUs > startTimeUs) { "End time must be after start time." }
                        if (cropAndScale.start(inputFile, outputFile, startTimeUs, endTimeUs, 24)) {
                            result.success(null)
                        } else {
                            result.error("BUSY", "A trim is already in progress.", null)
                        }
                    }

                    "startOverlay" -> {
                        val videoFile = File(call.stringArg("videoFile"))
                        val overlayFile = File(call.stringArg("overlayFile"))
                        val outputFile = File(call.stringArg("outputFile"))
                        val config = call.argument<Map<*, *>>("config")
                            ?: throw IllegalArgumentException("Missing argument 'config'")
                        val fps = call.longArg("fps").toInt()
                        require(videoFile.isFile) { "Video file does not exist: ${videoFile.path}" }
                        require(overlayFile.isFile) { "Overlay file does not exist: ${overlayFile.path}" }
                        require(fps > 0) { "fps must be positive." }
                        if (overlayAndEncode.start(
                                videoFile, overlayFile, outputFile, WebPConfig.fromMap(config), fps
                            )
                        ) {
                            result.success(null)
                        } else {
                            result.error("BUSY", "An encode is already in progress.", null)
                        }
                    }

                    "cancelOverlay" -> {
                        overlayAndEncode.cancel()
                        result.success(null)
                    }

                    "cancelTrim" -> {
                        cropAndScale.cancel()
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            } catch (e: IllegalArgumentException) {
                result.error("INVALID_ARGUMENTS", e.message, null)
            } catch (e: Exception) {
                result.error("INTERNAL_ERROR", e.message, null)
            }
        }

        // 2. EventChannels: progress streamed to Flutter
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, TRIM_CHANNEL_NAME)
            .setStreamHandler(progressHandler(cropAndScale.status, cropAndScale.progress))
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, ECODE_CHANNEL_NAME)
            .setStreamHandler(progressHandler(overlayAndEncode.status, overlayAndEncode.progress))
    }

    /**
     * Streams status + progress. RUNNING updates are throttled; status changes and terminal
     * states are always delivered immediately.
     */
    private fun progressHandler(
        status: Flow<Enum<*>>,
        progress: Flow<ProgressState>
    ): EventChannel.StreamHandler = object : EventChannel.StreamHandler {
        private var eventScope: CoroutineScope? = null

        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            if (events == null) return
            eventScope?.cancel()
            val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            eventScope = scope
            scope.launch {
                var lastStatus: String? = null
                var lastEmitMs = 0L
                status.combine(progress) { s, p -> s.name to p }.collect { (name, p) ->
                    val now = SystemClock.elapsedRealtime()
                    if (name != lastStatus || name != "RUNNING" ||
                        now - lastEmitMs >= PROGRESS_INTERVAL_MS
                    ) {
                        lastStatus = name
                        lastEmitMs = now
                        events.success(
                            mapOf(
                                "status" to name,
                                "progress" to p.progress,
                                "currentFrame" to p.currentFrame,
                                "totalFrames" to p.totalFrames
                            )
                        )
                    }
                }
            }
        }

        override fun onCancel(arguments: Any?) {
            eventScope?.cancel()
            eventScope = null
        }
    }

    private fun MethodCall.stringArg(key: String): String =
        argument<Any>(key)?.toString()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing argument '$key'")

    /** Accepts numbers or numeric strings (Dart sends microseconds as strings). */
    private fun MethodCall.longArg(key: String): Long =
        when (val value = argument<Any>(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: throw IllegalArgumentException("Missing or invalid argument '$key'")

    override fun onDestroy() {
        super.onDestroy()
        if (this::cropAndScale.isInitialized) cropAndScale.release()
        if (this::overlayAndEncode.isInitialized) overlayAndEncode.release()
    }
}
