package com.example.pathfinderindoor.hazard
import android.content.Context
import android.graphics.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.ByteArrayOutputStream

// This is the "contract" Person 4 implements to receive alerts.
interface HazardListener {
    fun onHazardDetected(label: String, distanceLevel: String, isUrgent: Boolean)
    fun onBoxesUpdated(bitmap: Bitmap)
}

class HazardDetector(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val viewFinder: PreviewView,
    private val listener: HazardListener
) {

    private val tfliteDetector by lazy {
        val baseOptions = BaseOptions.builder().setNumThreads(4).build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(5)
            .setScoreThreshold(0.5f)
            .build()
        ObjectDetector.createFromFileAndOptions(context, "efficientdet_lite0.tflite", options)
    }

    private var lastSpokenTime = 0L
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    processFrame(imageProxy)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                isRunning = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        isRunning = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProviderFuture.get().unbindAll()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
                val tensorImage = TensorImage.fromBitmap(rotated)
                val results = tfliteDetector.detect(tensorImage)

                listener.onBoxesUpdated(drawBoxes(results, rotated.width, rotated.height))
                notifyTopResult(results, rotated.height)
            }
        } catch (e: Exception) {
            // Swallow per-frame errors so one bad frame doesn't crash the pipeline
        } finally {
            imageProxy.close()
        }
    }

    private fun notifyTopResult(results: List<org.tensorflow.lite.task.vision.detector.Detection>, frameHeight: Int) {
        if (results.isEmpty()) return
        val top = results.maxByOrNull { it.boundingBox.height() } ?: return
        val label = top.categories.firstOrNull()?.label ?: "object"
        val boxHeightRatio = top.boundingBox.height() / frameHeight.toFloat()

        val (distanceLevel, isUrgent) = when {
            boxHeightRatio > 0.6f -> "very close" to true
            boxHeightRatio > 0.3f -> "nearby" to false
            else -> "ahead" to false
        }

        val now = System.currentTimeMillis()
        val minGap = if (isUrgent) 1000L else 2500L
        if (now - lastSpokenTime > minGap) {
            lastSpokenTime = now
            listener.onHazardDetected(label, distanceLevel, isUrgent)
        }
    }

    private fun drawBoxes(results: List<org.tensorflow.lite.task.vision.detector.Detection>, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f }
        val textPaint = Paint().apply { color = Color.YELLOW; textSize = 40f }
        for (result in results) {
            canvas.drawRect(result.boundingBox, paint)
            val label = result.categories.firstOrNull()?.label ?: "object"
            canvas.drawText(label, result.boundingBox.left, result.boundingBox.top - 10, textPaint)
        }
        return bitmap
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining(); val uSize = uBuffer.remaining(); val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}