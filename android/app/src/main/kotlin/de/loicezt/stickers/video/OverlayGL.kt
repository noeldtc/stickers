package de.loicezt.stickers.video

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Draws decoder frames plus a static overlay into an off-screen FBO and reads the
 * result back as RGBA. When OpenGL ES 3 is available the read-back is double-buffered
 * through pixel buffer objects, so the GPU never stalls waiting for the CPU; the
 * frame returned by [submitReadback] is then the previous one (see [readyTimestampMs]).
 * All methods must be called from the thread that owns the EGL context.
 */
class OverlayGL : SurfaceTexture.OnFrameAvailableListener {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var videoProgramHandle = 0
    private var overlayProgramHandle = 0
    private var videoTextureHandle = 0
    private var overlayTextureHandle = 0
    private var fboHandle = 0
    private var fboTextureHandle = 0

    // Cached attribute / uniform locations.
    private var videoPosLoc = 0
    private var videoTexLoc = 0
    private var videoMatrixLoc = 0
    private var overlayPosLoc = 0
    private var overlayTexLoc = 0
    private var overlaySamplerLoc = 0

    private val vertexBuffer: FloatBuffer = GlHelper.floatBuffer(GlHelper.QUAD_VERTICES)
    private val texCoordBuffer: FloatBuffer = GlHelper.floatBuffer(GlHelper.QUAD_TEX_COORDS)
    private val transformMatrix = FloatArray(16)
    private val flipMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)

    private var decoderSurfaceTexture: SurfaceTexture? = null
    private var _decoderInputSurface: Surface? = null
    val decoderInputSurface: Surface
        get() = _decoderInputSurface ?: throw IllegalStateException("setup() not called")

    private val frameSemaphore = Semaphore(0)

    private var outputWidth = 0
    private var outputHeight = 0
    private var viewportX = 0
    private var viewportY = 0
    private var viewportWidth = 0
    private var viewportHeight = 0

    // Read-back state.
    private var usesPbo = false
    private val pbos = IntArray(2)
    private val pboTimestamps = IntArray(2)
    private var writeIdx = 0
    private var pendingIdx = -1
    private var mappedIdx = -1
    private var syncBuffer: ByteBuffer? = null

    /** Timestamp (ms) of the buffer most recently returned by a read-back call. */
    var readyTimestampMs = 0
        private set

    init {
        Matrix.setIdentityM(flipMatrix, 0)
        Matrix.translateM(flipMatrix, 0, 0f, 1f, 0f)
        Matrix.scaleM(flipMatrix, 0, 1f, -1f, 1f)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameSemaphore.release()
    }

    /**
     * @param rotationDegrees rotation metadata of the source video (0/90/180/270); used so
     * the letterbox viewport matches the displayed orientation.
     */
    fun setup(
        outputWidth: Int,
        outputHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        rotationDegrees: Int,
        overlayBitmap: Bitmap
    ) {
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            calculateViewport(videoHeight, videoWidth)
        } else {
            calculateViewport(videoWidth, videoHeight)
        }

        eglDisplay = GlHelper.initEglDisplay()
        val config = GlHelper.chooseEglConfig(eglDisplay, pbuffer = true)
        eglContext = createContext(config, 3)
        usesPbo = eglContext != EGL14.EGL_NO_CONTEXT
        if (!usesPbo) {
            eglContext = createContext(config, 2)
            GlHelper.checkEgl("eglCreateContext")
        }
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, outputWidth, EGL14.EGL_HEIGHT, outputHeight, EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttribs, 0)
        GlHelper.checkEgl("eglCreatePbufferSurface")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            GlHelper.checkEgl("eglMakeCurrent")
        }
        if (usesPbo) {
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            usesPbo = version.startsWith("OpenGL ES 3")
        }

        videoProgramHandle = GlHelper.createProgram(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
        overlayProgramHandle = GlHelper.createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        videoPosLoc = GLES20.glGetAttribLocation(videoProgramHandle, "aPosition")
        videoTexLoc = GLES20.glGetAttribLocation(videoProgramHandle, "aTexCoord")
        videoMatrixLoc = GLES20.glGetUniformLocation(videoProgramHandle, "uTransformMatrix")
        overlayPosLoc = GLES20.glGetAttribLocation(overlayProgramHandle, "aPosition")
        overlayTexLoc = GLES20.glGetAttribLocation(overlayProgramHandle, "aTexCoord")
        overlaySamplerLoc = GLES20.glGetUniformLocation(overlayProgramHandle, "sTexture")

        videoTextureHandle = GlHelper.createExternalOesTexture()
        uploadOverlay(overlayBitmap)
        setupFBO()
        setupReadback()

        val texture = SurfaceTexture(videoTextureHandle)
        texture.setOnFrameAvailableListener(this)
        decoderSurfaceTexture = texture
        _decoderInputSurface = Surface(texture)
    }

    private fun createContext(config: android.opengl.EGLConfig, version: Int): EGLContext {
        val attribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, attribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) EGL14.eglGetError() // clear the error flag
        return context
    }

    private fun calculateViewport(videoWidth: Int, videoHeight: Int) {
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        if (videoAspect > 1f) {
            viewportWidth = outputWidth
            viewportHeight = (outputWidth / videoAspect).toInt()
            viewportX = 0
            viewportY = (outputHeight - viewportHeight) / 2
        } else {
            viewportHeight = outputHeight
            viewportWidth = (outputHeight * videoAspect).toInt()
            viewportY = 0
            viewportX = (outputWidth - viewportWidth) / 2
        }
    }

    fun awaitNewFrame() {
        if (!frameSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
            throw TimeoutException("Timeout waiting for new video frame")
        }
    }

    fun drawFrame() {
        val texture = decoderSurfaceTexture ?: throw IllegalStateException("setup() not called")
        texture.updateTexImage()
        texture.getTransformMatrix(transformMatrix)
        Matrix.multiplyMM(finalMatrix, 0, transformMatrix, 0, flipMatrix, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle)

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 0f) // Transparent black
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
        drawVideo()

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        drawOverlay()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Starts reading back the frame just drawn. Returns a direct RGBA buffer that is ready
     * to be encoded, or null if none is ready yet (PBO mode returns the previous frame, so
     * the first call returns null). Its timestamp is [readyTimestampMs]. Call
     * [releaseReadback] when finished with the buffer.
     */
    fun submitReadback(timestampMs: Int): ByteBuffer? {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle)
        if (!usesPbo) {
            val buffer = syncBuffer!!
            buffer.clear()
            GLES20.glReadPixels(
                0, 0, outputWidth, outputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            buffer.rewind()
            readyTimestampMs = timestampMs
            return buffer
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[writeIdx])
        GLES30.glReadPixels(
            0, 0, outputWidth, outputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0
        )
        pboTimestamps[writeIdx] = timestampMs
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val ready = if (pendingIdx >= 0) mapPbo(pendingIdx) else null
        pendingIdx = writeIdx
        writeIdx = 1 - writeIdx
        return ready
    }

    /** Returns the last pending frame once the stream has ended (PBO mode only). */
    fun finishReadback(): ByteBuffer? {
        if (!usesPbo || pendingIdx < 0) return null
        val ready = mapPbo(pendingIdx)
        pendingIdx = -1
        return ready
    }

    /** Releases the buffer returned by [submitReadback] / [finishReadback]. */
    fun releaseReadback() {
        if (!usesPbo || mappedIdx < 0) return
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[mappedIdx])
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        mappedIdx = -1
    }

    private fun mapPbo(index: Int): ByteBuffer {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[index])
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, outputWidth * outputHeight * 4,
            GLES30.GL_MAP_READ_BIT
        ) as? ByteBuffer
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        if (mapped == null) throw RuntimeException("glMapBufferRange failed")
        mappedIdx = index
        readyTimestampMs = pboTimestamps[index]
        return mapped
    }

    /** Safe to call even if [setup] failed halfway, and more than once. */
    fun release() {
        _decoderInputSurface?.release()
        _decoderInputSurface = null
        decoderSurfaceTexture?.release()
        decoderSurfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun drawVideo() {
        GLES20.glUseProgram(videoProgramHandle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureHandle)
        GLES20.glUniformMatrix4fv(videoMatrixLoc, 1, false, finalMatrix, 0)
        renderQuad(videoPosLoc, videoTexLoc)
    }

    private fun drawOverlay() {
        GLES20.glUseProgram(overlayProgramHandle)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureHandle)
        GLES20.glUniform1i(overlaySamplerLoc, 1)
        renderQuad(overlayPosLoc, overlayTexLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun renderQuad(posHandle: Int, texHandle: Int) {
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    /** The overlay never changes, so it is uploaded once instead of once per frame. */
    private fun uploadOverlay(bitmap: Bitmap) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        overlayTextureHandle = textures[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureHandle)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GlHelper.checkGl("uploadOverlay")
    }

    private fun setupFBO() {
        val fbo = IntArray(1)
        val fboTex = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glGenTextures(1, fboTex, 0)
        fboHandle = fbo[0]
        fboTextureHandle = fboTex[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureHandle)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outputWidth, outputHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
            fboTextureHandle, 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Framebuffer is not complete")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun setupReadback() {
        val size = outputWidth * outputHeight * 4
        if (!usesPbo) {
            syncBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            return
        }
        GLES30.glGenBuffers(2, pbos, 0)
        for (pbo in pbos) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, size, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GlHelper.checkGl("setupReadback")
    }

    private val VIDEO_VERTEX_SHADER = """
        uniform mat4 uTransformMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTransformMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val VIDEO_FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    private val OVERLAY_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val OVERLAY_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()
}
