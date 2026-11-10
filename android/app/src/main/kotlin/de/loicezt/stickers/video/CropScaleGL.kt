package de.loicezt.stickers.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Renders decoder frames into an encoder input surface. All methods must be called
 * from the same thread (the one that owns the EGL context).
 */
class CropScaleGL : SurfaceTexture.OnFrameAvailableListener {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programHandle = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var transformMatrixHandle = 0
    private var videoTextureHandle = 0

    private val vertexBuffer: FloatBuffer = GlHelper.floatBuffer(GlHelper.QUAD_VERTICES)
    private val texCoordBuffer: FloatBuffer = GlHelper.floatBuffer(GlHelper.QUAD_TEX_COORDS)
    private val transformMatrix = FloatArray(16)

    private var decoderSurfaceTexture: SurfaceTexture? = null
    private var _decoderInputSurface: Surface? = null
    val decoderInputSurface: Surface
        get() = _decoderInputSurface ?: throw IllegalStateException("setup() not called")

    private val frameSemaphore = Semaphore(0)

    private var targetWidth: Int = 0
    private var targetHeight: Int = 0

    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameSemaphore.release()
    }

    fun setup(encoderSurface: Surface, width: Int, height: Int) {
        targetWidth = width
        targetHeight = height

        eglDisplay = GlHelper.initEglDisplay()
        val config = GlHelper.chooseEglConfig(eglDisplay, pbuffer = false)
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        GlHelper.checkEgl("eglCreateContext")
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        GlHelper.checkEgl("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            GlHelper.checkEgl("eglMakeCurrent")
        }

        programHandle = GlHelper.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(programHandle, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programHandle, "aTexCoord")
        transformMatrixHandle = GLES20.glGetUniformLocation(programHandle, "uTransformMatrix")

        videoTextureHandle = GlHelper.createExternalOesTexture()
        val texture = SurfaceTexture(videoTextureHandle)
        texture.setOnFrameAvailableListener(this)
        decoderSurfaceTexture = texture
        _decoderInputSurface = Surface(texture)
    }

    fun awaitNewFrame() {
        if (!frameSemaphore.tryAcquire(2, TimeUnit.SECONDS)) {
            throw TimeoutException("Timeout waiting for new video frame")
        }
    }

    fun drawFrame(timestampNs: Long) {
        val texture = decoderSurfaceTexture ?: throw IllegalStateException("setup() not called")
        texture.updateTexImage()
        texture.getTransformMatrix(transformMatrix)

        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programHandle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureHandle)
        GLES20.glUniformMatrix4fv(transformMatrixHandle, 1, false, transformMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
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

    private val VERTEX_SHADER = """
        uniform mat4 uTransformMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTransformMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()
}
