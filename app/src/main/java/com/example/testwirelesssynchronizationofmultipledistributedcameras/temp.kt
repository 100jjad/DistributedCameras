package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.Manifest
import android.app.Activity
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
    private var flash = FLASH.AUTO
    private var cameraState = STATE_PREVIEW
    private var surface: Surface? = null
    /**
     * Whether the current camera device supports Flash or not.
     */
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
                    } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        val aeState = captureResult[CaptureResult.CONTROL_AE_STATE]
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            cameraState = STATE_PICTURE_TAKEN
                        }
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
            java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            Toast.makeText(activity , "width : $width , height : $height" , Toast.LENGTH_LONG).show()
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
    internal fun openCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(
                textureView.context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            setUpCameraOutputs(width, height)
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } else {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            Log.e("Camera2", "دسترسی وجود ندارد")
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
                    // We fit the aspect ratio of TextureView to the size of preview we picked.
                    val orientation = activity.resources.configuration.orientation
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        textureView.setAspectRatio(
                            previewSize!!.width, previewSize!!.height
                        )
                    } else {
                        textureView.setAspectRatio(
                            previewSize!!.height, previewSize!!.width
                        )
                    }/*
                    // check flash support
                    val flashSupported = cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                    isFlashSupported = flashSupported == null ?: false*/
                    isFlashSupported = cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    this.cameraId = cameraId
                    return
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }
    private fun openBackgroundThread() {
        backgroundThread = HandlerThread("camera_background_thread")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }
    // Creates a new camera preview session
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
// When session is ready we start displaying preview.
                            this@Camera3.cameraCaptureSession = cameraCaptureSession
                            //     cameraSessionClosed = false
// Auto focus should be continuous for camera preview.
                            captureRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
// Finally, we start displaying the camera preview.
                            captureRequest = captureRequestBuilder!!.build()

                            this@Camera3.cameraCaptureSession!!.setRepeatingRequest(
                                captureRequest!!,
                                cameraCaptureCallBack,
                                backgroundHandler
                            )
                            /* Initially flash is automatically enabled when necessary. But In case activity is resumed and flash is set to fire
                            we set flash after the preview request is processed to ensure flash fires only during a still capture. */
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
    /* For some reason, The code for firing flash in both methods below which is prescribed doesn't work on API level below PIE it maybe a device-specific issue as very common with Camera API
       so I had to build my own code if the else block works well for your devices even below PIE I would recommend using it because that's
       the official way and code is available for all levels >=21 as mentioned.
    */
    private fun flashOn(captureRequestBuilder: CaptureRequest.Builder) {
        //  cameraManager.setTorchMode()
        if (Build.VERSION.SDK_INT > 28) {
            captureRequestBuilder.set(
                CaptureRequest.FLASH_MODE,
                CaptureRequest.FLASH_MODE_TORCH
            )
        } else {
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            )
        }

    }
    // sets flash mode for a capture request builder
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
    fun switchCamera() {
        cameraFacing = if (cameraFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        onResume()
    }
    fun setFlash(flash: FLASH) {
        this.flash = flash
        if (textureView.context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            when (cameraFacing) {
//     CameraCharacteristics.LENS_FACING_BACK -> setFlashMode()
                CameraCharacteristics.LENS_FACING_FRONT -> Log.e("Camera2", "Front Camera Flash isn't supported yet.")
            }
        }
    }
}