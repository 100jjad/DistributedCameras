package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.Matrix.ScaleToFit
import android.hardware.camera2.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*
import java.util.Collections.singletonList
import kotlin.Comparator
import android.media.MediaRecorder
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Arrays


class Camera3(private val activity: Activity, private val textureView: AutoFitTextureView) {
    private var onBitmapReady: (Bitmap) -> Unit = {}
    private val cameraManager: CameraManager =
        textureView.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraFacing = CameraCharacteristics.LENS_FACING_BACK
    private var previewSize: Size? = null
    private var cameraId = "-1"
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var captureRequest: CaptureRequest? = null
    private var flash = FLASH.OFF
    private var cameraState = STATE_PREVIEW
    private var exposureCompensationRange: Range<Int>? = null
    private var isoRange: Range<Int>? = null
    private var focusRange : Range<Float>? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingVideo: Boolean = false
    private var surface: Surface? = null
    private var isFlashSupported = true
    private var mSensorOrientation = 0
    private val cameraCaptureCallBack = object : CameraCaptureSession.CaptureCallback() {
        private fun process(captureResult: CaptureResult) {
            when (cameraState) {
                STATE_PREVIEW -> {
                }
                STATE_WAITING_LOCK -> {
                    val afState = captureResult[CaptureResult.CONTROL_AF_STATE]
                    if (afState == null) {
                        captureStillPicture()
                    } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        val aeState = captureResult[CaptureResult.CONTROL_AE_STATE]
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            cameraState = STATE_PICTURE_TAKEN
                            captureStillPicture()
                        } else runPrecaptureSequence()
                    }
                }
                STATE_WAITING_PRECAPTURE -> {
                    val aeState = captureResult[CaptureResult.CONTROL_AE_STATE]
                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        cameraState = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    val aeState = captureResult[CaptureResult.CONTROL_AE_STATE]
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        cameraState = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }
    }
    internal companion object {
        private const val STATE_PREVIEW = 0
        private const val STATE_WAITING_LOCK = 1
        private const val STATE_WAITING_PRECAPTURE = 2
        private const val STATE_WAITING_NON_PRECAPTURE = 3
        private const val STATE_PICTURE_TAKEN = 4
        internal const val REQUEST_CAMERA_PERMISSION = 1001
        private val ORIENTATIONS = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
        private fun chooseOptimalSize(
            choices: Array<Size>, textureViewWidth: Int,
            textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: Size
        ): Size {
            val bigEnough = arrayListOf<Size>()
            val notBigEnough = arrayListOf<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight &&
                    option.height == option.width * h / w
                ) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }
            return when {
                bigEnough.isNotEmpty() -> Collections.min(bigEnough, compareSizesByArea)
                notBigEnough.isNotEmpty() -> Collections.max(notBigEnough, compareSizesByArea)
                else -> {
                    Log.e("Camera", "Couldn't find any suitable preview size")
                    choices[0]
                }
            }
        }
        private val compareSizesByArea = Comparator<Size> { lhs, rhs ->
            // We cast here to ensure the multiplications won't overflow
            java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            //Toast.makeText(activity , "width : $width , height : $height" , Toast.LENGTH_LONG).show()
            configureTransform(width, height)
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return true
        }
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }
    }
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            this@Camera3.cameraDevice = camera
            createPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            this@Camera3.cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
        }
    }
    fun onResume() {
        openBackgroundThread()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else textureView.surfaceTextureListener = surfaceTextureListener
    }
    fun close() {
        closeCamera()
        closeBackgroundThread()
    }
    private fun closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession!!.close()
            cameraCaptureSession = null
            //   cameraSessionClosed = true
        }

        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    private fun closeBackgroundThread() {
        if (backgroundHandler != null) {
            backgroundThread!!.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        }
    }


    internal fun openCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(
                textureView.context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            setUpCameraOutputs(width, height)
            configureTransform(width, height)

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)


        }
    }

    private fun setUpCameraOutputs(width: Int, height: Int) {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                val cameraFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)

                if (cameraFacing == this.cameraFacing) {
                    val streamConfigurationMap = cameraCharacteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    )
                    exposureCompensationRange = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    isoRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val minFocusDistance = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    focusRange = Range(0f, minFocusDistance)
                    val largest = Collections.max(
                        streamConfigurationMap?.getOutputSizes(ImageFormat.JPEG)?.toList(),
                        compareSizesByArea
                    )
                    val displayRotation = activity.windowManager.defaultDisplay.rotation
                    mSensorOrientation = cameraCharacteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
                    var swappedDimensions = false
                    when (displayRotation) {
                        Surface.ROTATION_0 -> {
                        }
                        Surface.ROTATION_90 -> {
                        }
                        Surface.ROTATION_180 -> {
                            swappedDimensions = mSensorOrientation == 90 || mSensorOrientation == 270
                        }
                        Surface.ROTATION_270 -> {
                            swappedDimensions = mSensorOrientation == 0 || mSensorOrientation == 180
                        }
                        else -> Log.e("Camera2", "Display rotation is invalid: $displayRotation")
                    }
                    val displaySize = Point()
                    activity.windowManager.defaultDisplay.getSize(displaySize)
                    var rotatedPreviewWidth = width
                    var rotatedPreviewHeight = height
                    var maxPreviewWidth = displaySize.x
                    var maxPreviewHeight = displaySize.y
                    if (swappedDimensions) {
                        rotatedPreviewWidth = height
                        rotatedPreviewHeight = width
                        maxPreviewWidth = displaySize.y
                        maxPreviewHeight = displaySize.x
                    }
                    if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                        maxPreviewWidth = MAX_PREVIEW_WIDTH
                    }
                    if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                        maxPreviewHeight = MAX_PREVIEW_HEIGHT
                    }
                    previewSize = chooseOptimalSize(
                        streamConfigurationMap!!.getOutputSizes(SurfaceTexture::class.java),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest
                    )
                    val orientation = activity.resources.configuration.orientation

                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        textureView.setAspectRatio(
                            previewSize!!.width, previewSize!!.height
                        )
                    } else {
                        textureView.setAspectRatio(
                            previewSize!!.height, previewSize!!.width
                        )
                    }
                    isFlashSupported = cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    this.cameraId = cameraId

                    return
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize!!.height,
                viewWidth.toFloat() / previewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }
    private fun getOrientation(rotation: Int) =
        (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360
    private fun openBackgroundThread() {
        backgroundThread = HandlerThread("camera_background_thread")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }
    private fun createPreviewSession() {

        try {

            val surfaceTexture = textureView.surfaceTexture
            surfaceTexture?.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            if (surface == null)
                surface = Surface(surfaceTexture)
            val previewSurface = surface
            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder!!.addTarget(previewSurface!!)
            cameraDevice!!.createCaptureSession(
                singletonList(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            return
                        }
                        try {
                            this@Camera3.cameraCaptureSession = cameraCaptureSession
                            captureRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            captureRequest = captureRequestBuilder!!.build()
                            this@Camera3.cameraCaptureSession!!.setRepeatingRequest(
                                captureRequest!!,
                                cameraCaptureCallBack,
                                backgroundHandler
                            )
                            setFlashMode(captureRequestBuilder!!, true)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }
                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }
    private fun flashOn(captureRequestBuilder: CaptureRequest.Builder) {
        // برای API level 28 و بالاتر
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            captureRequestBuilder.set(
                CaptureRequest.FLASH_MODE,
                CaptureRequest.FLASH_MODE_TORCH
            )
        } else {
            // برای API level های پایین‌تر
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            )
            captureRequestBuilder.set(
                CaptureRequest.FLASH_MODE,
                CaptureRequest.FLASH_MODE_TORCH
            )
        }
    }
    private fun setFlashMode(
        captureRequestBuilder: CaptureRequest.Builder,
        trigger: Boolean
    ) {
        if (trigger) {
            // This is how to tell the camera to trigger.
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
        }
        when (flash) {
            FLASH.ON -> flashOn(captureRequestBuilder)
            FLASH.AUTO -> {
                captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                )
            }
            else -> captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
    }
    enum class FLASH {
        ON, OFF, AUTO
    }
    private fun lockPreview() {
        try {
            captureRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            cameraState = STATE_WAITING_LOCK
            cameraCaptureSession!!.capture(
                captureRequestBuilder!!.build(), cameraCaptureCallBack, backgroundHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unlockPreview() {
        try {
// Reset the auto-focus trigger
            captureRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            setFlashMode(captureRequestBuilder!!, false)
            cameraCaptureSession!!.capture(
                captureRequestBuilder!!.build(), cameraCaptureCallBack, backgroundHandler
            )
            cameraState = STATE_PREVIEW
            cameraCaptureSession!!.setRepeatingRequest(
                captureRequest!!, cameraCaptureCallBack,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }
    private fun runPrecaptureSequence() {
        try {
            cameraState = STATE_WAITING_PRECAPTURE
            setFlashMode(captureRequestBuilder!!, true)
            cameraCaptureSession!!.capture(captureRequestBuilder!!.build(), cameraCaptureCallBack, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun captureBitmap() {
        if (textureView.isAvailable) {
            textureView.bitmap?.let { bitmap ->
                onBitmapReady(bitmap) // Only if the bitmap is not null
            }
        }
    }
    private fun captureStillPicture() {
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(surface!!)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            setFlashMode(captureBuilder, true)
            val rotation = activity.windowManager.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))
            cameraCaptureSession!!.stopRepeating()
            cameraCaptureSession!!.abortCaptures()
            cameraCaptureSession!!.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {

                    captureBitmap()
                    unlockPreview()
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    fun setExposureCompensation(value: Int) {
        // توجه داشته باشید که در حالت preview ممکن است builder موجود باشد
        captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value)
        cameraCaptureSession?.setRepeatingRequest(captureRequestBuilder!!.build(), cameraCaptureCallBack, backgroundHandler)
    }


    fun autoOptimizeExposure() {
        // اگر captureRequestBuilder آماده است
        captureRequestBuilder?.let { builder ->
            // تنظیم حالت AE به خودکار (با flash در صورت نیاز)
            //builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            // تنظیم مقدار Exposure Compensation به صفر (یا مقدار دلخواه)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            // به‌روزرسانی درخواست تکراری
            cameraCaptureSession?.setRepeatingRequest(builder.build(), cameraCaptureCallBack, backgroundHandler)
        }
    }

    fun setISO(value: Int) {
        // غیرفعال کردن نوردهی خودکار برای کنترل دستی ISO
        captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        // تنظیم دستی ISO
        captureRequestBuilder?.set(CaptureRequest.SENSOR_SENSITIVITY, value)
        cameraCaptureSession?.setRepeatingRequest(captureRequestBuilder!!.build(), cameraCaptureCallBack, backgroundHandler)
    }

    fun autoOptimizeISO() {
        val defaultISO = 100 // مقدار پیش‌فرض؛ در صورت نیاز می‌توانید این مقدار را تغییر دهید
        setISO(defaultISO)
    }

    fun setManualFocus(focusDistance: Float) {
        // غیرفعال کردن فوکوس خودکار
        captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        // تنظیم دستی فاصله فوکوس
        captureRequestBuilder?.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
        cameraCaptureSession?.setRepeatingRequest(captureRequestBuilder!!.build(), cameraCaptureCallBack, backgroundHandler)
    }

    fun autoOptimizeFocus() {
        // تنظیم فوکوس به حالت بی‌نهایت (معمولاً مقدار 0 در Camera2 API نشان‌دهنده فوکوس بی‌نهایت است)
        setManualFocus(0f)
    }
}