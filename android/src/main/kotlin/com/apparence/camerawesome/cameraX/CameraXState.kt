package com.apparence.camerawesome.cameraX

import android.annotation.SuppressLint
import android.app.Activity
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.internal.compat.CameraCharacteristicsCompat
import androidx.camera.camera2.internal.compat.quirk.CamcorderProfileResolutionQuirk
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.apparence.camerawesome.CamerawesomePlugin
import com.apparence.camerawesome.models.FlashMode
import com.apparence.camerawesome.sensors.SensorOrientation
import com.apparence.camerawesome.utils.isMultiCamSupported
import io.flutter.plugin.common.EventChannel
import io.flutter.view.TextureRegistry
import java.util.concurrent.Executor

/// Hold the settings of the camera and use cases in this class and
/// call updateLifecycle() to refresh the state
data class CameraXState(
    private var cameraProvider: ProcessCameraProvider,
    val textureEntries: Map<String, TextureRegistry.SurfaceTextureEntry>,
//    var cameraSelector: CameraSelector,
    var sensors: List<PigeonSensor>,
    var imageCaptures: MutableList<ImageCapture> = mutableListOf(),
    var videoCaptures: MutableMap<PigeonSensor, VideoCapture<Recorder>> = mutableMapOf(),
    var previews: MutableList<Preview>? = null,
    var concurrentCamera: ConcurrentCamera? = null,
    var previewCamera: Camera? = null,
    private var currentCaptureMode: CaptureModes,
    var enableAudioRecording: Boolean = true,
    var recordings: MutableList<Recording>? = null,
    var enableImageStream: Boolean = false,
    var photoSize: Size? = null,
    var previewSize: Size? = null,
    var aspectRatio: Int? = null,
    // Rational is used only in ratio 1:1
    var rational: Rational = Rational(3, 4),
    var flashMode: FlashMode = FlashMode.NONE,
    val onStreamReady: (state: CameraXState) -> Unit,
    var mirrorFrontCamera: Boolean = false,
    val videoRecordingQuality: VideoRecordingQuality?,
    val videoOptions: AndroidVideoOptions?,
) : EventChannel.StreamHandler, SensorOrientation {

    var imageAnalysisBuilder: ImageAnalysisBuilder? = null
    private var imageAnalysis: ImageAnalysis? = null

    companion object {
        /**
         * Experimental, off by default: in video mode, feed the analysis
         * stream from the Preview stream on the GPU (see [PreviewTapEffect])
         * so VideoCapture isn't capped by the 3-stream combination rules.
         * Set through the "camerawesome/experimental" method channel.
         */
        @JvmStatic
        var useGpuPreviewTap = false

        /**
         * 4K recording plus the GPU tap needs memory: on a 2 GB phone
         * (Nexus 5X) the low-memory killer took the app down seconds after
         * binding. Below 3 GB, or on devices Android flags as low-RAM, keep
         * the proven ImageAnalysis path (~1080p cap).
         */
        @JvmStatic
        fun gpuPreviewTapSupported(context: android.content.Context): Boolean {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            if (am.isLowRamDevice) return false
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return info.totalMem >= 3L * 1024 * 1024 * 1024 * 9 / 10
        }
    }

    /** Live while [useGpuPreviewTap] serves the analysis stream. */
    var previewTap: PreviewTapEffect? = null
        private set
    private var tapTargetRotation = Surface.ROTATION_0

    /**
     * Binds Preview + VideoCapture with the GPU tap standing in for
     * ImageAnalysis. False (camera left unbound) if this device rejects it,
     * so the caller falls back to the regular ImageAnalysis path.
     */
    @SuppressLint("RestrictedApi")
    private fun bindWithPreviewTap(
        activity: Activity,
        cameraSelector: CameraSelector,
        useCaseGroupBuilder: UseCaseGroup.Builder,
    ): Boolean {
        // The preview output arrives already upright for the portrait
        // display, so the tap only rotates by how far the device is turned
        // from that (0 held upright, +-90 in landscape) - the same turn the
        // old ImageAnalysis frames followed via targetRotation.
        val tap = PreviewTapEffect.create(imageAnalysisBuilder!!) {
            val info = previewCamera?.cameraInfo ?: return@create 0
            (info.getSensorRotationDegrees(tapTargetRotation) -
                info.getSensorRotationDegrees(Surface.ROTATION_0) + 360) % 360
        }
        return try {
            useCaseGroupBuilder.setViewPort(ViewPort.Builder(rational, Surface.ROTATION_0).build())
            useCaseGroupBuilder.addEffect(tap)
            imageAnalysis = null
            concurrentCamera = null
            previewCamera = cameraProvider.bindToLifecycle(
                activity as LifecycleOwner,
                cameraSelector,
                useCaseGroupBuilder.build(),
            )
            previewTap = tap
            Log.i(CamerawesomePlugin.TAG, "Video bound with the GPU preview tap")
            true
        } catch (e: Exception) {
            Log.w(CamerawesomePlugin.TAG, "GPU preview tap not supported here, using ImageAnalysis: $e")
            tap.processor.release()
            cameraProvider.unbindAll()
            false
        }
    }

    private val mainCameraInfos: CameraInfo
        @SuppressLint("RestrictedApi") get() {
            if (previewCamera == null && concurrentCamera == null) {
                throw Exception("Trying to access main camera infos before setting the preview")
            }
            return previewCamera?.cameraInfo ?: concurrentCamera?.cameras?.first()?.cameraInfo!!
        }

    private val mainCameraControl: CameraControl
        @SuppressLint("RestrictedApi") get() {
            if (previewCamera == null && concurrentCamera == null) {
                throw Exception("Trying to access main camera control before setting the preview")
            }
            return previewCamera?.cameraControl
                ?: concurrentCamera?.cameras?.first()?.cameraControl!!
        }

    val maxZoomRatio: Double
        @SuppressLint("RestrictedApi") get() = mainCameraInfos.zoomState.value!!.maxZoomRatio.toDouble()


    val minZoomRatio: Double
        get() = mainCameraInfos.zoomState.value!!.minZoomRatio.toDouble()


    val portrait: Boolean
        get() = mainCameraInfos.sensorRotationDegrees % 180 == 0

    fun executor(activity: Activity): Executor {
        return ContextCompat.getMainExecutor(activity)
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError")
    fun updateLifecycle(activity: Activity) {
        previews = mutableListOf()
        imageCaptures.clear()
        videoCaptures.clear()
        val resolutionSelector = getResolutionSelector(aspectRatio ?: AspectRatio.RATIO_4_3)
        if (cameraProvider.isMultiCamSupported() && sensors.size > 1) {
            val singleCameraConfigs = mutableListOf<ConcurrentCamera.SingleCameraConfig>()
            var isFirst = true
            for ((index, sensor) in sensors.withIndex()) {
                val useCaseGroupBuilder = UseCaseGroup.Builder()

                val cameraSelector =
                    if (isFirst) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                // TODO Find cameraSelectors based on the sensor and the cameraProvider.availableConcurrentCameraInfos
//                val cameraSelector = CameraSelector.Builder()
//                    .requireLensFacing(if (sensor.position == PigeonSensorPosition.FRONT) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
//                    .addCameraFilter(CameraFilter { cameraInfos ->
//                        val list = mutableListOf<CameraInfo>()
//                        cameraInfos.forEach { cameraInfo ->
//                            Camera2CameraInfo.from(cameraInfo).let {
//                                if (it.getPigeonPosition() == sensor.position && (it.getSensorType() == sensor.type || it.getSensorType() == PigeonSensorType.UNKNOWN)) {
//                                    list.add(cameraInfo)
//                                }
//                            }
//                        }
//                        if (list.isEmpty()) {
//                            // If no camera found, only filter based on the sensor position and ignore sensor type
//                            cameraInfos.forEach { cameraInfo ->
//                                Camera2CameraInfo.from(cameraInfo).let {
//                                    if (it.getPigeonPosition() == sensor.position) {
//                                        list.add(cameraInfo)
//                                    }
//                                }
//                            }
//                        }
//                        return@CameraFilter list
//                    })
//                    .build()


                val preview = if (aspectRatio != null) {
                    Preview.Builder().setTargetAspectRatio(aspectRatio!!)
                        .build()
                } else {
                    Preview.Builder().build()
                }

                useCaseGroupBuilder.addUseCase(preview)
                previews!!.add(preview)

                if (currentCaptureMode == CaptureModes.PHOTO) {
                    val imageCapture = ImageCapture.Builder()
//                .setJpegQuality(100)
                        .apply {
                            if (rational.denominator != rational.numerator) {
                                setResolutionSelector(resolutionSelector)
                            }

                            setFlashMode(
                                if (isFirst) when (flashMode) {
                                    FlashMode.ALWAYS, FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                                    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                                    else -> ImageCapture.FLASH_MODE_OFF
                                }
                                else ImageCapture.FLASH_MODE_OFF
                            )
                        }.build()
                    useCaseGroupBuilder.addUseCase(imageCapture)
                    imageCaptures.add(imageCapture)
                } else {
                    val videoCapture = buildVideoCapture(videoOptions)
                    useCaseGroupBuilder.addUseCase(videoCapture)
                    videoCaptures[sensor] = videoCapture
                }
                if (isFirst && enableImageStream && imageAnalysisBuilder != null) {
                    imageAnalysis = imageAnalysisBuilder!!.build()
                    useCaseGroupBuilder.addUseCase(imageAnalysis!!)
                } else {
                    imageAnalysis = null
                }

                isFirst = false
                useCaseGroupBuilder.setViewPort(
                    ViewPort.Builder(rational, Surface.ROTATION_0).build()
                )
                singleCameraConfigs.add(
                    ConcurrentCamera.SingleCameraConfig(
                        cameraSelector,
                        useCaseGroupBuilder.build(), activity as LifecycleOwner,
                    )
                )
            }

            cameraProvider.unbindAll()
            previewCamera = null
            concurrentCamera = cameraProvider.bindToLifecycle(
                singleCameraConfigs
            )
            // Only set flash to the main camera (the first one)
            concurrentCamera!!.cameras.first().cameraControl.enableTorch(flashMode == FlashMode.ALWAYS)
        } else {
            val useCaseGroupBuilder = UseCaseGroup.Builder()
            // Handle single camera
            val cameraSelector =
                if (sensors.first().position == PigeonSensorPosition.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            // Preview
            // With the GPU tap the preview no longer shares a capped stream:
            // keep it near 1080p instead of letting it run at full sensor
            // size (pointless GPU load; the recording is a separate stream).
            val tapPlanned = useGpuPreviewTap && gpuPreviewTapSupported(activity) && enableImageStream &&
                imageAnalysisBuilder != null && currentCaptureMode == CaptureModes.VIDEO
            if (currentCaptureMode != CaptureModes.ANALYSIS_ONLY) {
                previews!!.add(
                    if (aspectRatio != null) {
                        Preview.Builder()
                            .setResolutionSelector(
                                if (tapPlanned) tapPreviewSelector(aspectRatio!!) else resolutionSelector
                            )
                            .build()
                    } else {
                        Preview.Builder().build()
                    }
                )

                previews!!.first().setSurfaceProvider(
                    surfaceProvider(executor(activity), sensors.first().deviceId ?: "0")
                )
                useCaseGroupBuilder.addUseCase(previews!!.first())
            }

            if (currentCaptureMode == CaptureModes.PHOTO) {
                val imageCapture = ImageCapture.Builder()
//                .setJpegQuality(100)
                    .apply {
                        //photoSize?.let { setTargetResolution(it) }
                        if (rational.denominator != rational.numerator) {
                            setResolutionSelector(resolutionSelector)
                        }
                        setFlashMode(
                            when (flashMode) {
                                FlashMode.ALWAYS, FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                                FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                        )
                    }.build()
                useCaseGroupBuilder.addUseCase(imageCapture)
                imageCaptures.add(imageCapture)
            } else if (currentCaptureMode == CaptureModes.VIDEO) {
                val videoCapture = buildVideoCapture(videoOptions)
                useCaseGroupBuilder.addUseCase(videoCapture)
                videoCaptures[sensors.first()] = videoCapture
            }


            val addAnalysisUseCase = enableImageStream && imageAnalysisBuilder != null
            cameraProvider.unbindAll()
            previewTap?.processor?.release()
            previewTap = null
            if (tapPlanned && addAnalysisUseCase &&
                currentCaptureMode == CaptureModes.VIDEO && previews!!.isNotEmpty() &&
                bindWithPreviewTap(activity, cameraSelector, useCaseGroupBuilder)
            ) {
                previewCamera!!.cameraControl.enableTorch(flashMode == FlashMode.ALWAYS)
                return
            }
            imageAnalysis = if (addAnalysisUseCase) imageAnalysisBuilder!!.build() else null
            // TODO Orientation might be wrong, to be verified
            useCaseGroupBuilder.setViewPort(ViewPort.Builder(rational, Surface.ROTATION_0).build())
            val baseGroup = useCaseGroupBuilder.build()

            concurrentCamera = null
            val analysis = imageAnalysis
            previewCamera = if (analysis != null) {
                // Since CameraX 1.3, StreamSharing resolves use-case combinations
                // beyond the camera2 guaranteed tables (e.g. Preview + VideoCapture
                // + ImageAnalysis on sub-LEVEL_3 devices), so bind optimistically
                // instead of pre-gating on hardware level. If this device truly
                // cannot support the combination, fall back to binding without
                // image analysis (the old behavior).
                val withAnalysis = UseCaseGroup.Builder().apply {
                    baseGroup.viewPort?.let { setViewPort(it) }
                    baseGroup.useCases.forEach { addUseCase(it) }
                    addUseCase(analysis)
                }.build()
                try {
                    cameraProvider.bindToLifecycle(
                        activity as LifecycleOwner,
                        cameraSelector,
                        withAnalysis,
                    )
                } catch (e: IllegalArgumentException) {
                    Log.w(
                        CamerawesomePlugin.TAG,
                        "Use-case combination with image analysis not supported on this device, retrying without it: $e"
                    )
                    imageAnalysis = null
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        activity as LifecycleOwner,
                        cameraSelector,
                        baseGroup,
                    )
                }
            } else {
                cameraProvider.bindToLifecycle(
                    activity as LifecycleOwner,
                    cameraSelector,
                    baseGroup,
                )
            }
            previewCamera!!.cameraControl.enableTorch(flashMode == FlashMode.ALWAYS)
        }
    }

    private fun tapPreviewSelector(aspectRatio: Int): ResolutionSelector {
        val bound = if (aspectRatio == AspectRatio.RATIO_16_9) Size(1920, 1080) else Size(1440, 1080)
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (aspectRatio == AspectRatio.RATIO_16_9) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            )
            .setResolutionStrategy(
                ResolutionStrategy(bound, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
    }

    private fun getResolutionSelector(aspectRatio: Int): ResolutionSelector {
        val resolutionStrategy = when (aspectRatio) {
            AspectRatio.RATIO_16_9 -> ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
            AspectRatio.RATIO_4_3 -> ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
            else -> ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
        }

        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                when (aspectRatio) {
                    AspectRatio.RATIO_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                    AspectRatio.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    else -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                }
            )
            .setResolutionStrategy(resolutionStrategy)
            .build()
    }

    private fun buildVideoCapture(videoOptions: AndroidVideoOptions?): VideoCapture<Recorder> {
        val recorderBuilder = Recorder.Builder()
        // Aspect ratio is handled by the setViewPort on the UseCaseGroup
        if (videoRecordingQuality != null) {
            val quality = when (videoRecordingQuality) {
                VideoRecordingQuality.LOWEST -> Quality.LOWEST
                VideoRecordingQuality.SD -> Quality.SD
                VideoRecordingQuality.HD -> Quality.HD
                VideoRecordingQuality.FHD -> Quality.FHD
                VideoRecordingQuality.UHD -> Quality.UHD
                else -> Quality.HIGHEST
            }
            recorderBuilder.setQualitySelector(
                QualitySelector.from(
                    quality,
                    if (videoOptions?.fallbackStrategy == QualityFallbackStrategy.LOWER) FallbackStrategy.lowerQualityOrHigherThan(
                        quality
                    )
                    else FallbackStrategy.higherQualityOrLowerThan(quality)
                )
            )
        }
        if (videoOptions?.bitrate != null) {
            recorderBuilder.setTargetVideoEncodingBitRate(videoOptions.bitrate.toInt())
        }
        val recorder = recorderBuilder.build()
        return VideoCapture.Builder<Recorder>(recorder)
            .setMirrorMode(if (mirrorFrontCamera) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            .build()
    }

    @SuppressLint("RestrictedApi")
    private fun surfaceProvider(executor: Executor, cameraId: String): Preview.SurfaceProvider {
//        Log.d("SurfaceProviderCamX", "Creating surface provider for $cameraId")
        return Preview.SurfaceProvider { request: SurfaceRequest ->
            val resolution = request.resolution
            //Log.d("CameraX", "surfaceProvider -> Preview size: width=${resolution.width}, height=${resolution.height}")
            val texture = textureEntries[cameraId]!!.surfaceTexture()
            texture.setDefaultBufferSize(resolution.width, resolution.height)
            val surface = Surface(texture)
            request.provideSurface(surface, executor) {
//                Log.d("CameraX", "Surface request result: ${it.resultCode}")
                surface.release()
            }
        }
    }

    fun setLinearZoom(zoom: Float) {
        mainCameraControl.setLinearZoom(zoom)
    }

    fun startFocusAndMetering(autoFocusAction: FocusMeteringAction) {
        mainCameraControl.startFocusAndMetering(autoFocusAction)
    }

    fun setCaptureMode(captureMode: CaptureModes) {
        currentCaptureMode = captureMode
        when (currentCaptureMode) {
            CaptureModes.PHOTO -> {
                // Release video related stuff
                videoCaptures.clear()
                recordings?.forEach { it.close() }
                recordings = null

            }

            CaptureModes.VIDEO -> {
                // Release photo related stuff
                imageCaptures.clear()
            }

            else -> {
                // Preview and analysis only modes

                // Release video related stuff
                videoCaptures.clear()
                recordings?.forEach { it.close() }
                recordings = null

                // Release photo related stuff
                imageCaptures.clear()
            }
        }
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError")
    fun previewSizes(): List<Size> {
        val characteristics = CameraCharacteristicsCompat.toCameraCharacteristicsCompat(
            Camera2CameraInfo.extractCameraCharacteristics(mainCameraInfos),
            Camera2CameraInfo.from(mainCameraInfos).cameraId
        )
        return CamcorderProfileResolutionQuirk(characteristics).supportedResolutions
    }

    fun qualityAvailableSizes(): List<String> {
        val supportedQualities = QualitySelector.getSupportedQualities(mainCameraInfos)
        return supportedQualities.map {
            when (it) {
                Quality.UHD -> {
                    "UHD"
                }

                Quality.HIGHEST -> {
                    "HIGHEST"
                }

                Quality.FHD -> {
                    "FHD"
                }

                Quality.HD -> {
                    "HD"
                }

                Quality.LOWEST -> {
                    "LOWEST"
                }

                Quality.SD -> {
                    "SD"
                }

                else -> {
                    "unknown"
                }
            }
        }
    }

    fun stop() {
        cameraProvider.unbindAll()
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        val previous = imageAnalysisBuilder?.previewStreamSink
        imageAnalysisBuilder?.previewStreamSink = events
        if (previous == null && events != null) {
            onStreamReady(this)
        }
    }

    override fun onCancel(arguments: Any?) {
        this.imageAnalysisBuilder?.previewStreamSink?.endOfStream()
        this.imageAnalysisBuilder?.previewStreamSink = null
    }

    override fun onOrientationChanged(orientation: Int) {
        val rotation = when (orientation) {
            in 225 until 315 -> {
                Surface.ROTATION_90
            }

            in 135 until 225 -> {
                Surface.ROTATION_180
            }

            in 45 until 135 -> {
                Surface.ROTATION_270
            }

            else -> {
                Surface.ROTATION_0
            }
        }
        imageAnalysis?.targetRotation = rotation
        tapTargetRotation = rotation
    }

    fun updateAspectRatio(newAspectRatio: String) {
        // In CameraX, aspect ratio is an Int. RATIO_4_3 = 0 (default), RATIO_16_9 = 1
        aspectRatio = if (newAspectRatio == "RATIO_16_9") 1 else 0
        rational = when (newAspectRatio) {
            "RATIO_16_9" -> Rational(9, 16)
            "RATIO_1_1" -> Rational(1, 1)
            else -> Rational(3, 4)
        }
    }
}