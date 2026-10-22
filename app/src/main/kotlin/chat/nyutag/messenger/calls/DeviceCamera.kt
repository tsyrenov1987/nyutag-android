// Нютаг. Камера телефона для видеозвонка: Camera2, кадры YUV 640×480 повёрнуты в портрет 480×640 (I420), как у
// iOS-клиента; до 24 кадров в секунду. Своё превью — маленькая картинка 120×160 из тех же кадров: отдельный вывод
// камеры на экран пришлось бы пересобирать при каждом повороте экрана звонка.

package chat.nyutag.messenger.calls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import chat.nyutag.core.calls.VideoFormat
import chat.nyutag.core.calls.VideoPicture
import chat.nyutag.core.calls.VideoPipe
import chat.nyutag.core.calls.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow

class DeviceCamera(context: Context, private val preview: MutableStateFlow<CameraPreview?>) : VideoSource {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val worker = HandlerThread("camera").apply { start() }
    private val handler = Handler(worker.looper)
    /** Только в потоке камеры. */
    private var pipe: VideoPipe? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var front = true
    private var rotation = 0
    private val width = VideoFormat.WIDTH
    private val height = VideoFormat.HEIGHT
    private val y = ByteArray(width * height)
    private val u = ByteArray(width * height / 4)
    private val v = ByteArray(width * height / 4)
    private var started = 0L
    private var frames = 0L
    private val shots = arrayOf(Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888),
                                Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888))
    private val pixels = IntArray(PREVIEW_W * PREVIEW_H)
    /** Копии плоскостей кадра камеры: из прямого буфера по байту читать дольше. */
    private val copies = arrayOf(ByteArray(0), ByteArray(0), ByteArray(0))

    override fun start(pipe: VideoPipe) {
        handler.post {
            this.pipe = pipe
            open()
        }
    }

    override fun stop() {
        handler.post {
            pipe = null
            close()
            preview.value = null
            Log.d("calls", "камера: остановлена")
        }
        worker.quitSafely()
    }

    override fun switchCamera() {
        handler.post {
            if (pipe == null) return@post
            close()
            front = !front
            open()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val id = manager.cameraIdList.firstOrNull { facing(it) == if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK }
            ?: manager.cameraIdList.firstOrNull() ?: return
        val traits = manager.getCameraCharacteristics(id)
        front = traits.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        rotation = traits.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val sizes = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        val size = sizes.firstOrNull { it.width == 640 && it.height == 480 }
            ?: sizes.filter { it.width * 3 == it.height * 4 && it.width >= 480 }.minByOrNull { it.width }
            ?: sizes.minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) } ?: Size(640, 480)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader.setOnImageAvailableListener({ took(it) }, handler)
        this.reader = reader
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (pipe == null) return camera.close()
                    device = camera
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(made: CameraCaptureSession) {
                            if (device !== camera) return made.close()
                            session = made
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(reader.surface)
                                pace(traits)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            }
                            runCatching { made.setRepeatingRequest(request.build(), null, handler) }
                            Log.d("calls", "камера: запущена (${if (front) "фронтальная" else "основная"}, ${size.width}×${size.height}, поворот $rotation)")
                        }

                        override fun onConfigureFailed(made: CameraCaptureSession) {
                            Log.w("calls", "камера: сессия не собралась")
                        }
                    }, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w("calls", "камера: ошибка $error")
                    camera.close()
                    if (device === camera) device = null
                }
            }, handler)
        } catch (e: Exception) {
            Log.w("calls", "камера не открылась: $e")
        }
    }

    private fun facing(id: String) = runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) }.getOrNull()

    /** Ровно 24 кадра в секунду, если камера умеет; иначе ближайший диапазон до 30. */
    private fun pace(traits: CameraCharacteristics): Range<Int>? {
        val ranges = traits.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.firstOrNull { it.lower == VideoFormat.FRAME_RATE && it.upper == VideoFormat.FRAME_RATE }
            ?: ranges.filter { it.upper in VideoFormat.FRAME_RATE..30 }.maxByOrNull { it.lower }
    }

    private fun close() {
        session?.close()
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
    }

    private fun took(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        val pipe = pipe
        try {
            if (pipe == null) return
            convert(image)
        } finally {
            image.close()
        }
        if (started == 0L) started = System.nanoTime()
        frames += 1
        pipe.capture(VideoPicture(width, height, y, u, v, (System.nanoTime() - started) / 1_000))
        if (frames % 2 == 0L) showPreview()
    }

    /** YUV_420_888 камеры → I420 портрет 480×640: поворот на угол датчика по часовой и, если размер другой, — ближайший пиксель. */
    private fun convert(image: android.media.Image) {
        val sw = image.width
        val sh = image.height
        val turned = rotation == 90 || rotation == 270
        val rw = if (turned) sh else sw
        val rh = if (turned) sw else sh
        val planes = image.planes
        fun plane(index: Int, out: ByteArray, w: Int, h: Int, scale: Int) {
            val buffer = planes[index].buffer
            val row = planes[index].rowStride
            val step = planes[index].pixelStride
            val srcW = sw / scale
            val srcH = sh / scale
            val size = buffer.remaining()
            if (copies[index].size < size) copies[index] = ByteArray(size)
            val bytes = copies[index]
            buffer.get(bytes, 0, size)
            var o = 0
            for (oy in 0 until h) {
                val dy = oy * (rh / scale) / h
                for (ox in 0 until w) {
                    val dx = ox * (rw / scale) / w
                    val sx: Int
                    val sy: Int
                    when (rotation) {
                        90 -> { sx = dy; sy = srcH - 1 - dx }
                        180 -> { sx = srcW - 1 - dx; sy = srcH - 1 - dy }
                        270 -> { sx = srcW - 1 - dy; sy = dx }
                        else -> { sx = dx; sy = dy }
                    }
                    val at = sy * row + sx * step
                    out[o++] = if (at < size) bytes[at] else 0
                }
            }
        }
        plane(0, y, width, height, 1)
        plane(1, u, width / 2, height / 2, 2)
        plane(2, v, width / 2, height / 2, 2)
    }

    /** Превью 120×160 из I420: каждый четвёртый пиксель. */
    private fun showPreview() {
        var o = 0
        for (py in 0 until PREVIEW_H) {
            val sy = py * 4
            for (px in 0 until PREVIEW_W) {
                val sx = px * 4
                val l = (y[sy * width + sx].toInt() and 0xFF)
                val cb = (u[(sy / 2) * (width / 2) + sx / 2].toInt() and 0xFF) - 128
                val cr = (v[(sy / 2) * (width / 2) + sx / 2].toInt() and 0xFF) - 128
                val r = (l + 1.402f * cr).toInt().coerceIn(0, 255)
                val g = (l - 0.344f * cb - 0.714f * cr).toInt().coerceIn(0, 255)
                val b = (l + 1.772f * cb).toInt().coerceIn(0, 255)
                pixels[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val shot = shots[(frames / 2 % 2).toInt()]
        shot.setPixels(pixels, 0, PREVIEW_W, 0, 0, PREVIEW_W, PREVIEW_H)
        preview.value = CameraPreview(shot, front)
    }

    private companion object {
        const val PREVIEW_W = 120
        const val PREVIEW_H = 160
    }
}

/** Своё превью; `mirrored` — фронтальная камера, показывать как в зеркале. */
class CameraPreview(val bitmap: Bitmap, val mirrored: Boolean)
