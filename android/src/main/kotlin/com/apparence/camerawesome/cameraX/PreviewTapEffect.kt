package com.apparence.camerawesome.cameraX

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.DynamicRange
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.utils.MatrixExt
import androidx.camera.core.processing.OpenGlRenderer
import io.flutter.plugin.common.EventChannel
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental (off by default, see [CameraXState.useGpuPreviewTap]).
 *
 * Feeds the analysis stream (the frames Dart sends to a remote device) from
 * the Preview stream on the GPU instead of a separate ImageAnalysis use case.
 * In video mode that drops the camera from three streams (Preview +
 * VideoCapture + YUV analysis) to two, which camera2 always allows at full
 * size - so VideoCapture can record 4K instead of being capped around
 * 1080p. The recording itself never passes through this code: the effect
 * targets PREVIEW only.
 *
 * Emits the same map as [ImageAnalysisBuilder]'s JPEG path (same sink, fps
 * cap and ack), so the Dart side cannot tell the difference.
 */
@SuppressLint("RestrictedApi")
class PreviewTapEffect private constructor(
    executor: Executor,
    val processor: PreviewTapProcessor,
) : CameraEffect(PREVIEW, executor, processor, { e ->
    Log.e(TAG, "preview tap effect error", e)
}) {
    companion object {
        private const val TAG = "PreviewTap"

        fun create(analysis: ImageAnalysisBuilder, sensorRotation: () -> Int): PreviewTapEffect {
            val processor = PreviewTapProcessor(analysis, sensorRotation)
            return PreviewTapEffect(processor.glExecutor, processor)
        }
    }
}

@SuppressLint("RestrictedApi")
class PreviewTapProcessor(
    private val analysis: ImageAnalysisBuilder,
    /** Degrees to rotate a sensor-oriented frame upright (follows device orientation). */
    private val sensorRotation: () -> Int,
) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {
    companion object {
        private const val TAG = "PreviewTap"
        private const val TAP_WIDTH = 640
        private const val JPEG_QUALITY = 80
        private const val ACK_TIMEOUT_MS = 1000L
    }

    private val glThread = HandlerThread("PreviewTapGL").apply { start() }
    private val glHandler = Handler(glThread.looper)
    val glExecutor: Executor = Executor { glHandler.post(it) }
    private val encodeExecutor = Executors.newSingleThreadExecutor()

    private val renderer = OpenGlRenderer()
    private val outputs = LinkedHashMap<SurfaceOutput, Surface>()
    private val textureMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private var inputSize: Size? = null
    private var inputCount = 0
    private var initialized = false
    private val releaseRequested = AtomicBoolean(false)
    private var released = false

    /** A frame is with Dart until it acks (receivedImageFromStream). */
    private val awaitingAck = AtomicBoolean(false)
    private var lastEmitMs = 0L
    private var loggedGeometry = false

    init {
        glExecutor.execute {
            try {
                renderer.init(DynamicRange.SDR)
                initialized = true
            } catch (e: Exception) {
                Log.e(TAG, "GL init failed", e)
            }
        }
    }

    /** Dart finished with the last frame (mirrors ImageAnalysisBuilder.lastFrameAnalysisFinished). */
    fun ack() {
        awaitingAck.set(false)
    }

    override fun onInputSurface(request: SurfaceRequest) {
        glExecutor.execute {
            if (!initialized || releaseRequested.get()) {
                request.willNotProvideSurface()
                return@execute
            }
            inputCount++
            val texture = SurfaceTexture(renderer.textureName)
            texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            inputSize = request.resolution
            val surface = Surface(texture)
            request.provideSurface(surface, glExecutor) {
                texture.setOnFrameAvailableListener(null)
                texture.release()
                surface.release()
                inputCount--
                releaseIfDone()
            }
            texture.setOnFrameAvailableListener(this, glHandler)
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        glExecutor.execute {
            if (!initialized || releaseRequested.get()) {
                output.close()
                return@execute
            }
            val surface = output.getSurface(glExecutor) {
                output.close()
                outputs.remove(output)?.let { renderer.unregisterOutputSurface(it) }
                releaseIfDone()
            }
            renderer.registerOutputSurface(surface)
            outputs[output] = surface
        }
    }

    override fun onFrameAvailable(texture: SurfaceTexture) {
        if (releaseRequested.get()) return
        texture.updateTexImage()
        texture.getTransformMatrix(textureMatrix)
        var tapSource: SurfaceOutput? = null
        for ((output, surface) in outputs) {
            output.updateTransformMatrix(outputMatrix, textureMatrix)
            renderer.render(texture.timestamp, outputMatrix, surface)
            if (tapSource == null) tapSource = output
        }
        // The preview output's matrix: crops to the ViewPort (what is being
        // recorded) and carries the GL flip CameraX's snapshot math expects.
        tapSource?.let {
            it.updateTransformMatrix(outputMatrix, textureMatrix)
            maybeTap(outputMatrix, it.size)
        }
    }

    private fun maybeTap(previewMatrix: FloatArray, previewSize: Size) {
        val sink = analysis.previewStreamSink ?: return
        val maxFps = analysis.maxFps
        val now = System.currentTimeMillis()
        if (maxFps != null && now - lastEmitMs < 1000 / maxFps) return
        // A frame dropped across a stream re-subscribe is never acked; don't
        // let that freeze the remote preview.
        if (awaitingAck.get() && now - lastEmitMs > ACK_TIMEOUT_MS) awaitingAck.set(false)
        if (!awaitingAck.compareAndSet(false, true)) return
        lastEmitMs = now

        val rotation = sensorRotation()
        // Exactly CameraX DefaultSurfaceProcessor's snapshot transform: rotate
        // upright, then revert the GL flip SurfaceOutput adds.
        val snapshotMatrix = previewMatrix.clone()
        MatrixExt.preRotate(snapshotMatrix, rotation.toFloat(), 0.5f, 0.5f)
        MatrixExt.preVerticalFlip(snapshotMatrix, 0.5f)
        // Long side TAP_WIDTH, like the 640x480 ImageAnalysis frames.
        val longSide = maxOf(previewSize.width, previewSize.height)
        val rawWidth = previewSize.width * TAP_WIDTH / longSide
        val rawHeight = previewSize.height * TAP_WIDTH / longSide
        val outSize = if (rotation % 180 == 0) Size(rawWidth, rawHeight) else Size(rawHeight, rawWidth)
        if (!loggedGeometry) {
            loggedGeometry = true
            Log.i(TAG, "tap geometry: input=$inputSize preview=$previewSize rotation=$rotation out=$outSize")
        }
        val bitmap: Bitmap = try {
            renderer.snapshot(outSize, snapshotMatrix)
        } catch (e: Exception) {
            Log.e(TAG, "snapshot failed", e)
            awaitingAck.set(false)
            return
        }
        encodeExecutor.execute {
            val jpeg = ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                it.toByteArray()
            }
            bitmap.recycle()
            // Raw (sensor-oriented) size + rotation, like an ImageProxy; the
            // JPEG itself is already upright, as ImageUtil's EXIF-rotated
            // output decodes.
            val frame = mutableMapOf<String, Any>(
                "height" to rawHeight,
                "width" to rawWidth,
                "format" to "jpeg",
                "rotation" to "rotation${rotation}deg",
                "jpegImage" to jpeg,
                "cropRect" to mapOf(
                    "left" to 0, "top" to 0, "right" to rawWidth, "bottom" to rawHeight,
                ),
            )
            analysis.executor.execute {
                val current: EventChannel.EventSink? = analysis.previewStreamSink
                if (current == null) {
                    awaitingAck.set(false)
                } else {
                    current.success(frame)
                }
            }
        }
    }

    /** Release once CameraX has closed every surface it handed us. */
    fun release() {
        releaseRequested.set(true)
        glExecutor.execute { releaseIfDone() }
    }

    private fun releaseIfDone() {
        if (released || !releaseRequested.get()) return
        if (inputCount > 0 || outputs.isNotEmpty()) return
        released = true
        if (initialized) renderer.release()
        encodeExecutor.shutdown()
        glThread.quitSafely()
    }
}
