package org.openmcx.client.app.media

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Between the camera and the encoder: camera frames arrive on a SurfaceTexture, whose transform
 * matrix already turns them upright for the phone's natural orientation (and mirrors the front
 * camera, see [VideoOrientation]), and are drawn, turned for the way the phone is held, onto the
 * encoder's input surface (the front camera's mirror undone) and onto the local preview (mirrored
 * for the front camera, as a selfie view). Receivers therefore get an upright picture without any
 * orientation signalling.
 */
class CameraGl(
    @Volatile private var cameraSize: Size,
    encoderSurface: Surface,
    /** How far to turn each camera frame clockwise to make it upright (0, 90, 180, 270), read per frame. */
    @Volatile private var rotation: () -> Int,
    /** The camera's sensor orientation, which the SurfaceTexture's transform matrix already applies. */
    @Volatile private var sensorOrientation: Int,
    @Volatile private var frontFacing: Boolean,
    private val preview: () -> Surface?,
) : AutoCloseable {
    /** Camera frames drawn since the last [switchSource]: the new camera delivers once this grows. */
    val frames = java.util.concurrent.atomic.AtomicInteger()
    private val thread = HandlerThread("mcvideo-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var config: EGLConfig
    private lateinit var encoderEgl: EGLSurface
    private var previewEgl: EGLSurface? = null
    private var previewTarget: Surface? = null
    private var program = 0
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    /** Where the camera draws. */
    lateinit var input: Surface
        private set
    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    @Volatile private var closed = false

    init {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try { setUp(encoderSurface) } catch (e: Throwable) { failure = e }
            ready.countDown()
        }
        ready.await(3, TimeUnit.SECONDS)
        failure?.let { close(); throw it }
    }

    private fun setUp(encoderSurface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        encoderEgl = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, encoderEgl, encoderEgl, context)) { "eglMakeCurrent" }
        program = link(VERTEX, FRAGMENT)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(cameraSize.width, cameraSize.height)
            setOnFrameAvailableListener({ draw() }, handler)
        }
        input = Surface(surfaceTexture)
    }

    /**
     * Another camera now draws into [input] (a switch during a transmission): its frame size, rotation and facing.
     * The encoder keeps its picture size; a different camera aspect is letterboxed.
     */
    fun switchSource(size: Size, rotation: () -> Int, sensorOrientation: Int, frontFacing: Boolean) {
        val done = CountDownLatch(1)
        handler.post {
            cameraSize = size
            this.rotation = rotation
            this.sensorOrientation = sensorOrientation
            this.frontFacing = frontFacing
            if (this::surfaceTexture.isInitialized) surfaceTexture.setDefaultBufferSize(size.width, size.height)
            frames.set(0)
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
    }

    private fun draw() {
        if (closed) return
        frames.incrementAndGet()
        EGL14.eglMakeCurrent(display, encoderEgl, encoderEgl, context)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        val upright = rotation()
        val turn = VideoOrientation.textureTurn(upright, sensorOrientation, frontFacing)
        val sideways = upright % 180 != 0
        render(encoderEgl, turn, sideways, mirror = VideoOrientation.mirrorSent(frontFacing))
        EGLExt.eglPresentationTimeANDROID(display, encoderEgl, surfaceTexture.timestamp)
        EGL14.eglSwapBuffers(display, encoderEgl)
        val target = preview()?.takeIf { it.isValid }
        if (target !== previewTarget) {
            previewEgl?.let { EGL14.eglDestroySurface(display, it) }
            previewEgl = target?.let { runCatching { EGL14.eglCreateWindowSurface(display, config, it, intArrayOf(EGL14.EGL_NONE), 0) }.getOrNull() }
            previewTarget = target
        }
        previewEgl?.let { surface ->
            if (EGL14.eglMakeCurrent(display, surface, surface, context)) {
                render(surface, turn, sideways, mirror = false) // mirrored already for the front camera
                EGL14.eglSwapBuffers(display, surface)
            }
        }
    }

    /**
     * Draw the camera frame, as the transform matrix gives it, turned by [turn] degrees clockwise and fitted
     * (letterboxed) into [surface]; [sideways]: the upright picture has the camera size's sides swapped.
     */
    private fun render(surface: EGLSurface, turn: Int, sideways: Boolean, mirror: Boolean) {
        val size = IntArray(2)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, size, 1)
        GLES20.glViewport(0, 0, size[0], size[1])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // Aspect of the upright picture against the surface's: scale one axis down to letterbox.
        val content = if (sideways) cameraSize.height.toFloat() / cameraSize.width else cameraSize.width.toFloat() / cameraSize.height
        val target = size[0].toFloat() / size[1]
        val sx = if (content > target) 1f else content / target
        val sy = if (content > target) target / content else 1f
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, if (mirror) -sx else sx, sy, 1f)
        Matrix.rotateM(mvp, 0, -turn.toFloat(), 0f, 0f, 1f) // clockwise on screen
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, TEXCOORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun close() {
        if (closed) return
        closed = true
        val done = CountDownLatch(1)
        handler.post {
            runCatching {
                previewEgl?.let { EGL14.eglDestroySurface(display, it) }
                if (this::encoderEgl.isInitialized) EGL14.eglDestroySurface(display, encoderEgl)
                if (this::surfaceTexture.isInitialized) { input.release(); surfaceTexture.release() }
                if (this::context.isInitialized) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private val QUAD = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEXCOORDS = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        private const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uTex;
            attribute vec4 aPos;
            attribute vec4 aTex;
            varying vec2 vTex;
            void main() { gl_Position = uMvp * aPos; vTex = (uTex * aTex).xy; }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        private fun link(vertex: String, fragment: String): Int {
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(program)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(program) }
            return program
        }
    }
}
