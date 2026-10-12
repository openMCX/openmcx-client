package org.openmcx.client.app.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import org.openmcx.client.mcvideo.H264Rtp
import org.openmcx.client.media.EncodedPicture
import org.openmcx.client.media.VideoCapture
import org.openmcx.client.media.VideoDevices
import org.openmcx.client.media.VideoFormat
import org.openmcx.client.media.VideoRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * MCVideo on Android: the camera (Camera2) feeds the hardware H.264 encoder through its input
 * surface, and received H.264 is decoded (MediaCodec) onto the call screen's surface, or onto
 * an off-screen one while no screen is shown. Constrained Baseline, level 3.1, as offered.
 */
class AndroidVideo(private val context: Context) : VideoDevices {
    /** The local preview while the camera runs; set by the call screen (the camera picks it up when it appears). */
    @Volatile var previewSurface: Surface? = null
        set(value) { field = value; captures.forEach { it.retarget() } }
    private val captures = java.util.concurrent.CopyOnWriteArrayList<Capture>()
    /** Where received video is drawn; the renderer follows it. */
    @Volatile var remoteSurface: Surface? = null
        set(value) { field = value; renderers.forEach { it.retarget() } }
    /** Which camera sends: set before a transmission, or [flip] during one. */
    @Volatile var useFrontCamera = false
    /** Camera switches during transmissions in this process (for the status view). */
    val switches = AtomicInteger()
    /** The camera the running capture uses (`front` or `back`), and the frames it drew since it started. */
    val activeCamera: String? get() = captures.firstOrNull()?.facing
    val activeCameraFrames: Int? get() = captures.firstOrNull()?.frames

    /** Use the other camera: at once in a running transmission (the encoder and its stream go on), else for the next one. */
    fun flip() {
        useFrontCamera = !useFrontCamera
        captures.forEach { it.switchCamera(useFrontCamera) }
    }
    /** Decoded pictures in this process (for the status view). */
    val decoded = AtomicInteger()
    /** The received picture's size, once the decoder knows it: the call screen keeps its shape. */
    val remoteSize = kotlinx.coroutines.flow.MutableStateFlow<Size?>(null)
    /** How the phone is held (0, 90, 180, 270 degrees from its natural orientation), while the camera runs. */
    @Volatile var deviceOrientation = 0
        private set
    /** The rotation the last transmission started with, and its picture size, for the status view. */
    @Volatile var sending: String? = null
        private set
    private val orientation = object : android.view.OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees != ORIENTATION_UNKNOWN) deviceOrientation = ((degrees + 45) / 90 * 90) % 360
        }
    }
    @Volatile var lastError: String? = null
    private val renderers = java.util.concurrent.CopyOnWriteArrayList<Renderer>()

    override fun capture(format: VideoFormat, output: (EncodedPicture) -> Unit): VideoCapture = Capture(format, output).also { captures += it }
    override fun renderer(): VideoRenderer = Renderer().also { renderers += it }

    private fun fail(what: String, e: Throwable) { lastError = "$what: ${e.message}"; Log.e(TAG, what, e) }

    @SuppressLint("MissingPermission") // the call screen asks for CAMERA before a video call
    private inner class Capture(private val format: VideoFormat, private val output: (EncodedPicture) -> Unit) : VideoCapture {
        private val thread = HandlerThread("mcvideo-camera").apply { start() }
        private val handler = Handler(thread.looper)
        private val encoder: MediaCodec
        private val input: Surface
        private val gl: CameraGl
        private var camera: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        @Volatile private var running = true
        @Volatile private var parameterSets: List<ByteArray> = emptyList()
        private val drain: Thread

        private val manager = context.getSystemService(CameraManager::class.java)
        @Volatile var facing: String = "back"
            private set
        val frames: Int get() = gl.frames.get()

        /** A camera facing [front] (or the first one), its sensor orientation, facing and capture size. */
        private fun choose(front: Boolean): Triple<String, Int, Pair<Boolean, Size>> {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.first()
            val characteristics = manager.getCameraCharacteristics(id)
            val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val isFront = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(MediaCodec::class.java)
            val size = sizes.filter { it.width <= 1280 && it.height <= 720 }
                .minByOrNull { abs(it.width * it.height - format.width * format.height) + abs(it.width - format.width) } ?: Size(format.width, format.height)
            return Triple(id, sensor, isFront to size)
        }

        init {
            val (id, sensor, chosen) = choose(useFrontCamera)
            val (front, size) = chosen
            facing = if (front) "front" else "back"
            orientation.enable()
            // The picture is sent upright: portrait (the camera size turned) when the phone is held upright.
            val turn = VideoOrientation.uprightRotation(sensor, deviceOrientation, front)
            val encoded = if (turn % 180 != 0) Size(size.height, size.width) else size
            sending = "${encoded.width}x${encoded.height}, turned $turn°"
            val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encoded.width, encoded.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, format.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // a receiver that lost a picture recovers within a second
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = encoder.createInputSurface()
            encoder.start()
            gl = CameraGl(size, input, { VideoOrientation.uprightRotation(sensor, deviceOrientation, front) }, sensor, front, { previewSurface })
            drain = thread(name = "mcvideo-encoder") { drainEncoder() }
            open(id)
            Log.i(TAG, "capture ${size.width}x${size.height} from camera $id (sensor $sensor°), sending $sending")
        }

        /** Open camera [id] onto the GL stage; [wait] for it (not on [handler], where the camera's callbacks run). */
        private fun open(id: String, wait: Boolean = true) {
            val opened = CountDownLatch(1)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!running) { device.close(); opened.countDown(); return }
                    camera = device; startSession(device, format.frameRate); opened.countDown()
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); opened.countDown() }
                override fun onError(device: CameraDevice, error: Int) { lastError = "camera error $error"; device.close(); opened.countDown() }
            }, handler)
            if (wait) opened.await(3, TimeUnit.SECONDS)
        }

        /**
         * Switch to the camera facing [front] without stopping the transmission: close this camera, point the GL stage at
         * the other camera's frames (its size, rotation and mirroring), open it onto the same input, and send a key picture
         * so receivers get a clean one from the new camera at once.
         */
        fun switchCamera(front: Boolean) = handler.post {
            if (!running || (facing == "front") == front) return@post
            val (id, sensor, chosen) = choose(front)
            val (isFront, size) = chosen
            runCatching { session?.close() }; session = null
            runCatching { camera?.close() }; camera = null
            gl.switchSource(size, { VideoOrientation.uprightRotation(sensor, deviceOrientation, isFront) }, sensor, isFront)
            facing = if (isFront) "front" else "back"
            open(id, wait = false)
            switches.incrementAndGet()
            handler.postDelayed({ requestKeyFrame() }, 300)
            Log.i(TAG, "switched to camera $id ($facing, ${size.width}x${size.height})")
        }

        private var frameRate = format.frameRate

        /** The preview surface changed: the GL stage draws to whatever surface is current, nothing to rebuild. */
        fun retarget() = Unit

        private fun startSession(device: CameraDevice, frameRate: Int) {
            // The camera draws only into the GL stage, which feeds the encoder and the preview.
            val targets = listOf(gl.input)
            @Suppress("DEPRECATION")
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        targets.forEach { addTarget(it) }
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(frameRate, frameRate.coerceAtLeast(15)))
                    }.build()
                    runCatching { s.setRepeatingRequest(request, null, handler) }.onFailure { fail("camera request", it) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { lastError = "camera session failed" }
            }, handler)
        }

        private fun drainEncoder() {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val index = try { encoder.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) { break }
                if (index < 0) continue
                val buffer = encoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                    val nals = H264Rtp.splitAnnexB(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) parameterSets = nals
                    else {
                        // Parameter sets in-band before every IDR, as the receivers expect.
                        val picture = if (H264Rtp.isKeyFrame(nals) && nals.none { H264Rtp.nalType(it) == 7 }) parameterSets + nals else nals
                        runCatching { output(EncodedPicture(picture, info.presentationTimeUs)) }.onFailure { fail("send", it) }
                    }
                }
                runCatching { encoder.releaseOutputBuffer(index, false) }
            }
        }

        override fun requestKeyFrame() {
            runCatching { encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }

        override fun close() {
            if (!running) return
            running = false
            captures.remove(this)
            runCatching { session?.close() }
            runCatching { camera?.close() }
            drain.join(500)
            runCatching { gl.close() }
            if (captures.isEmpty()) orientation.disable()
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            input.release()
            thread.quitSafely()
        }
    }

    private inner class Renderer : VideoRenderer {
        // Without a visible surface the pictures go here and are dropped at once, on a thread of their own: a busy main
        // thread would leave the decoder without free output buffers, and then without input ones.
        private val offscreenThread = HandlerThread("mcvideo-offscreen").apply { start() }
        private val offscreen = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 4).apply {
            setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(offscreenThread.looper))
        }
        // A decoder that fails to start is released here: phones have few hardware decoders, and a leaked one also
        // keeps the surface, so that every later decoder fails too.
        private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { c ->
            try {
                c.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480), target(), null, 0)
                c.start()
            } catch (e: Exception) {
                runCatching { c.release() }; offscreen.close(); offscreenThread.quitSafely()
                throw e
            }
        }
        private val info = MediaCodec.BufferInfo()
        @Volatile private var closed = false

        private fun target(): Surface = remoteSurface?.takeIf { it.isValid } ?: offscreen.surface

        fun retarget() { if (!closed) runCatching { codec.setOutputSurface(target()) }.onFailure { fail("decoder surface", it) } }

        override fun render(nals: List<ByteArray>, presentationUs: Long): Boolean {
            if (closed) return false
            val data = H264Rtp.joinAnnexB(nals)
            // A decoder still starting (just after the first key picture) or behind has no input buffer free for a
            // moment: wait for one, handing decoded pictures on meanwhile, rather than drop this picture, which the
            // following ones refer to (they would show broken until the next key picture).
            var index = codec.dequeueInputBuffer(20_000)
            val deadline = System.nanoTime() + 400_000_000L
            while (index < 0 && !closed && System.nanoTime() < deadline) {
                drain(0)
                index = codec.dequeueInputBuffer(10_000)
            }
            if (index < 0) return false
            codec.getInputBuffer(index)!!.apply { clear(); put(data) }
            codec.queueInputBuffer(index, 0, data.size, presentationUs, 0)
            drain(5_000)
            return true
        }

        /** Another transmitter: drop what the decoder holds of the last stream; the next key picture starts the new one. */
        override fun reset() { if (!closed) codec.flush() }

        /** Hand every decoded picture to the surface; wait up to [firstUs] for the first. */
        private fun drain(firstUs: Long) {
            var shown = false
            while (true) {
                val out = codec.dequeueOutputBuffer(info, if (shown) 0 else firstUs)
                // Shown now: the stream's own times start again with each transmission and would hold the picture back.
                if (out >= 0) { codec.releaseOutputBuffer(out, System.nanoTime()); decoded.incrementAndGet(); shown = true }
                else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                    remoteSize.value = Size(w, h)
                    continue
                }
                else break
            }
        }

        override fun close() {
            closed = true
            renderers.remove(this)
            if (renderers.isEmpty()) remoteSize.value = null
            runCatching { codec.stop() }; runCatching { codec.release() }
            offscreen.close()
            offscreenThread.quitSafely()
        }
    }

    companion object { private const val TAG = "mcx.video" }
}
