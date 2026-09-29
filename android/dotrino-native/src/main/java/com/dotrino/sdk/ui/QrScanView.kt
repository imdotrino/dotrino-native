package com.dotrino.sdk.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * READ A QR WITH THE CAMERA (`<dotrino-qr-scan>` in native): the back camera's preview, and
 * each frame's luminance goes to [DotrinoQr]. Everything on the phone.
 *
 * The CAMERA permission is the app's: it declares it in its manifest and asks for it before
 * [start] (the library does not declare it, or every app using it would ask for a camera it
 * does not need). Without it, [start] says so through `onError("no-camera-permission")`.
 * The fallback for no camera / no permission is reading a photo ([DotrinoQr.decode]).
 */
class QrScanView(context: Context) : FrameLayout(context) {
    private val texture = TextureView(context)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val done = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    init { addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    /**
     * Starts reading. [onResult] is called ONCE, on the main thread, with the first QR read;
     * after that the camera stops. [onError] gets a code: `no-camera-permission`,
     * `no-camera`, `camera-failed`.
     */
    fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("no-camera-permission"); return
        }
        done.set(false)
        val t = HandlerThread("dotrino-qr").also { it.start() }
        thread = t; handler = Handler(t.looper)
        if (texture.isAvailable) open(onResult, onError)
        else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = open(onResult, onError)
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true.also { stop() }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun open(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull()
        if (id == null) { onError("no-camera"); return }
        val sizes = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        // A mid resolution: enough for a QR at arm's length, light enough to analyse every frame.
        val size = sizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height } ?: Size(640, 480)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (done.get()) return@setOnImageAvailableListener
                val plane = img.planes[0]
                val w = img.width; val h = img.height
                val row = plane.rowStride
                val buf = plane.buffer
                val y = ByteArray(w * h)
                for (i in 0 until h) { buf.position(i * row); buf.get(y, i * w, w) }
                val text = DotrinoQr.decode(PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false))
                if (text != null && done.compareAndSet(false, true)) main.post { stop(); onResult(text) }
            } finally { img.close() }
        }, handler)
        texture.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
        val preview = Surface(texture.surfaceTexture)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview); addTarget(r.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    val exec = Executor { handler?.post(it) }
                    cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(preview), OutputConfiguration(r.surface)), exec,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                runCatching { s.setRepeatingRequest(req.build(), null, handler) }
                                    .onFailure { main.post { onError("camera-failed") } }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { main.post { onError("camera-failed") } }
                        }))
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); camera = null }
                override fun onError(cam: CameraDevice, error: Int) { cam.close(); camera = null; main.post { onError("camera-failed") } }
            }, handler)
        } catch (e: Exception) { onError("camera-failed") }
    }

    /** Stops the camera. Safe to call more than once. */
    fun stop() {
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
        thread?.quitSafely(); thread = null; handler = null
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}
