package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.CameraSettings
import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.TimeSyncManager
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt


class CustomCameraUI : Activity() , SlaveNetworkListener {
    private lateinit var textureView: AutoFitTextureView
    private lateinit var camera2: Camera2

    private var framePeriod: Double? = null // Frame period in milliseconds
    private var tau: Double? = null // Period in milliseconds
    private var tau0: Double? = null // Phase shift in milliseconds
    private var isTauCalculated = false
    private val handler = Handler(Looper.getMainLooper())
    private var calculateFramePeriodRunnable: Runnable? = null

    // متغیرها برای ذخیره مقادیر
    private var flashStatus: String? = null
    private var frameRate: String? = null
    private var duration: String? = null

    // متغیرهای ImageView برای دکمه‌ها
    private lateinit var ivFlashAuto: ImageView
    private lateinit var ivCaptureImage: ImageView
    private lateinit var ivVideoSaved: ImageView
    private lateinit var ivRotateCamera: ImageView

    private lateinit var txvtime: TextView

    // سه SeekBar برای Exposure, ISO و Focus
    private lateinit var exposureSlider: SeekBar
    private lateinit var isoSlider: SeekBar
    private lateinit var focusSlider: SeekBar
    private lateinit var zoomSlider: SeekBar
    private lateinit var controlLayout: ConstraintLayout

    // محدوده‌های تنظیمات (برای Exposure, ISO و Focus)
    private var exposureTimeRange: Range<Long>? = null   // محدوده مجاز زمان نوردهی (Exposure Time) بر حسب نانوثانیه
    private var isoRange: Range<Int>? = null
    private var focusRange: Range<Float>? = null

    private var isRecording = false
    private var exposureValue :Long = 30
    private var isoValue :Int = 30
    private var focusValue :Float = 30F

    var savedRole : String = ""


    private var currentZoom: Float = 1f  // ۱ یعنی بدون زوم
    private var maxZoom: Float = 4f

    companion object {
        private const val TAG = "CustomCameraUI"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // شفاف کردن Navigation Bar
        makeNavigationBarTransparent()

        setContentView(R.layout.activity_custom_camera_ui)


        // SharedPreferences برای ذخیره نقش
        val sharedPreferences: SharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        savedRole = sharedPreferences.getString("user_role", "slave").toString()

        // مقداردهی اولیه برای TextureView
        textureView = findViewById(R.id.camera_view)

        // مقداردهی اولیه Camera2
        camera2 = Camera2(this, textureView , savedRole)

        // فراخوانی تابع initialize برای دریافت و ذخیره مقادیر

        if (savedRole == "slave")
        {
            // تنظیم listener برای دریافت رویدادهای شبکه
            SlaveNetworkManager.setListener(this)
        }

        initialize(savedRole)

    }

    override fun onResume() {
        super.onResume()

        if (savedRole == "slave")
        {
            // تنظیم listener برای دریافت رویدادهای شبکه
            SlaveNetworkManager.setListener(this)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            calculateFramePeriod()
        }, 2000)

        camera2.onResume() // مدیریت باز کردن دوربین و شروع Thread
        Log.d(TAG, "Tau: $tau, Tau0: $tau0")


        exposureSlider.postDelayed({
            exposureTimeRange = camera2ExposureTimeRange() // متدی برای دریافت محدوده از Camera2 (یا مستقیماً استفاده از فیلد در Camera2 در صورت امکان)
            exposureTimeRange?.let {
                val lower = it.lower
                val upper = it.upper
                exposureSlider.max = 100 // مثلا اگر range = [-2, +2]، max = 4
                // تنظیم مقدار پیش‌فرض نوار در وسط
                val defaultProgress = 50
                exposureSlider.progress = defaultProgress
                // محاسبه مقدار اولیه به صورت میانه
                val fraction = defaultProgress.toFloat() / exposureSlider.max.toFloat()
                exposureValue = lower + ((upper - lower) * fraction).toLong()
            }
        }, 500)

        // تنظیم listener برای SeekBar
        exposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                exposureTimeRange?.let { range ->
                    val lower = range.lower      // کمترین زمان نوردهی (نانوثانیه)
                    val upper = range.upper      // بیشترین زمان نوردهی (نانوثانیه)
                    // محاسبه درصد پیشرفت (0 تا 1)
                    val fraction = progress.toFloat() / exposureSlider.max.toFloat()
                    // محاسبه مقدار جدید بر اساس درصد
                    exposureValue = lower + ((upper - lower) * fraction).toLong()
                    camera2.setExposureTime(exposureValue)
                    scheduleCalculateFramePeriod()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) { }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { }
        })


        // تنظیمات مشابه برای isoSlider
        isoSlider.postDelayed({
            isoRange = camera2IsoRange()
            isoRange?.let {
                val lower = it.lower
                val upper = it.upper
                isoSlider.max = upper - lower
                isoSlider.progress = (upper - lower) / 2
            }
        }, 500)

        isoSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                isoRange?.let {
                    val lower = it.lower
                    isoValue = lower + progress
                    camera2.setISO(isoValue) // فرض بر این است که متد setIso در Camera2 وجود دارد
                    scheduleCalculateFramePeriod()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { }
        })

// تنظیمات مربوط به focusSlider
        focusSlider.postDelayed({
            focusRange = camera2FocusRange()
            focusRange?.let {
                val sliderMax = 10000
                focusSlider.max = sliderMax
                focusSlider.progress = sliderMax  // مقدار اولیه روی بی‌نهایت (دور)
            }
        }, 500)

        focusSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                focusRange?.let { range ->
                    val fraction = progress.toFloat() / focusSlider.max.toFloat()
                    // نگاشت معکوس: 0 -> minFocusDistance (نزدیک)، 1 -> 0 (بی‌نهایت)
                    focusValue = (1 - fraction) * range.upper + fraction * 0f
                    // اطمینان از اینکه وقتی به حداکثر می‌رسه، دقیقاً 0f باشه
                    if (progress == focusSlider.max) {
                        focusValue = 0.0f
                    }
                    camera2.setManualFocus(focusValue)
                    Log.d("FocusDebug", "Progress: $progress, Fraction: $fraction, FocusValue: $focusValue")
                    scheduleCalculateFramePeriod()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


        zoomSlider.postDelayed({
            val maxZoom = getMaxZoom() // دریافت حداکثر زوم
            zoomSlider.max = 100 // تنظیم حداکثر مقدار SeekBar
            zoomSlider.progress = 0 // مقدار اولیه (بدون زوم)

            zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val zoomLevel = 1 + (maxZoom!! - 1) * (progress / 100.0f) // محاسبه مقدار زوم
                    camera2.setZoom(zoomLevel) // اعمال زوم به دوربین
                    scheduleCalculateFramePeriod() // به‌روزرسانی دوره فریم در صورت نیاز
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    // می‌توانید عملیاتی هنگام شروع حرکت SeekBar اضافه کنید
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    // می‌توانید عملیاتی هنگام توقف حرکت SeekBar اضافه کنید
                }
            })
        }, 500) // تأخیر برای اطمینان از آماده بودن دوربین




    }

    override fun onPause() {
        super.onPause()
        camera2.close() // بستن دوربین و Thread در هنگام توقف Activity

        if (savedRole == "slave")
        {
            // تنظیم listener برای دریافت رویدادهای شبکه
            SlaveNetworkManager.removeListener(this)
        }
    }



    private fun makeNavigationBarTransparent() {
        val window: Window = this.window

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // برای اندروید 11 و بالاتر
            window.setDecorFitsSystemWindows(false)

            // بررسی کنید که getInsetsController مقدار غیر null بازگرداند
            window.decorView.windowInsetsController?.apply {
                setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS // برای متن یا آیکون‌ها
                )
            }
        } else {
            // برای اندروید 10 و پایین‌تر
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }

        // شفاف کردن رنگ پس‌زمینه Navigation Bar
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }


    private fun initialize(Role : String) {
        // مقداردهی ImageView‌ها
        ivFlashAuto = findViewById(R.id.iv_camera_flash_auto)
        ivCaptureImage = findViewById(R.id.iv_capture_image)
        ivVideoSaved = findViewById(R.id.iv_video_saved)
        ivRotateCamera = findViewById(R.id.iv_rotate_camera)
        exposureSlider = findViewById(R.id.exposure_slider)
        isoSlider = findViewById(R.id.iso_slider)
        focusSlider = findViewById(R.id.focus_slider)
        zoomSlider = findViewById(R.id.zoom_slider)
        controlLayout = findViewById(R.id.control_layout)
        txvtime = findViewById(R.id.tv_timestart)


        // دریافت مقادیر از Intent
        flashStatus = intent.getStringExtra("flash_status")
        frameRate = intent.getStringExtra("frame_rate")
        duration = intent.getStringExtra("duration")

        // پیدا کردن TextViewها
        val tvFps: TextView = findViewById(R.id.tv_fps)
        val tvTime: TextView = findViewById(R.id.tv_time)

// مقداردهی به TextViewها
        tvFps.text = frameRate
        tvTime.text = duration

        if (flashStatus=="روشن")
        {
            Handler(Looper.getMainLooper()).postDelayed({
                camera2.setFlash(Camera2.FLASH.ON)
                camera2.applyFlashChanges()
                ivFlashAuto.setImageResource(R.drawable.flashon)
                ivFlashAuto.setColorFilter(Color.YELLOW, PorterDuff.Mode.SRC_IN) // اضافه کردن ColorFilter زرد وقتی فلاش روشن است
            }, 500) // 500 میلی‌ثانیه تأخیر


        }

/*
        ivFlashAuto.setOnClickListener {
            //فعال کردن فلاش دوربین در حالت پیش نمایش
            camera2.setFlash(Camera2.FLASH.ON)
            camera2.applyFlashChanges() // اعمال تغییرات
            // فعال‌کردن فلاش ویدئو در حالت ضبط ویدئو به طور خود کار
            //camera2.enableVideoFlash()
        }*/

        ivFlashAuto.setOnClickListener {
            if (camera2.getFlash() == Camera2.FLASH.ON) {
                camera2.setFlash(Camera2.FLASH.OFF)
                ivFlashAuto.setImageResource(R.drawable.flashoff)
                ivFlashAuto.clearColorFilter() // حذف ColorFilter وقتی فلاش خاموش است
            } else {
                camera2.setFlash(Camera2.FLASH.ON)
                ivFlashAuto.setImageResource(R.drawable.flashon)
                ivFlashAuto.setColorFilter(Color.YELLOW, PorterDuff.Mode.SRC_IN) // اضافه کردن ColorFilter زرد وقتی فلاش روشن است
            }
            camera2.applyFlashChanges()
        }

        ivCaptureImage.setOnClickListener {

            Toast.makeText(this, "Video Recorde clicked", Toast.LENGTH_SHORT).show()
            MasterNetworkManager.sendMessageToAllClients("READY_FOR_RECORDING_STATUS_2")
            // اکشن برای دکمه ضبط
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()

            }
        }

        ivCaptureImage.setOnLongClickListener{
            if (!isRecording) {
                // ضبط زمان‌بندی‌شده
                startScheduledRecording()
                true // لمس طولانی رو مصرف می‌کنیم
            } else {
                // توقف ضبط
                stopRecording()
                true // لمس طولانی رو مصرف می‌کنیم
            }
        }

        ivVideoSaved.setOnClickListener {
            // اکشن برای دکمه ویدئو ذخیره شده
            Toast.makeText(this, "Video Saved clicked", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, VideoListActivity::class.java)
            startActivity(intent)
        }

        ivVideoSaved.setOnLongClickListener {
            calculateFramePeriod()
            true }

        ivRotateCamera.setOnClickListener {
            camera2.switchCamera()
        }

        // نمایش مقادیر در Log برای بررسی
        Log.d(TAG, "Flash Status: $flashStatus")
        Log.d(TAG, "Frame Rate: $frameRate")
        Log.d(TAG, "Duration: $duration")

        val hideControlLayoutRunnable = Runnable {
            controlLayout.visibility = View.GONE
        }

        // اضافه کردن listener لمس روی preview
        textureView.setOnTouchListener { view, event ->
            if (event.pointerCount == 1) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {


                        // حذف هر برنامه‌ی مخفی‌سازی قبلی
                        controlLayout.removeCallbacks(hideControlLayoutRunnable)

                        // نمایش کل لایه‌ی کنترل
                        controlLayout.visibility = View.VISIBLE

                        // تنظیم موقعیت لایه نسبت به لمس کاربر
                        controlLayout.x = event.x - controlLayout.width / 2
                        controlLayout.y = event.y - controlLayout.height / 2

                        // فراخوانی performClick() برای پشتیبانی از قابلیت‌های دسترسی
                        view.performClick()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // به‌روز‌رسانی موقعیت نوار در صورت نیاز
                        controlLayout.x = event.x - controlLayout.width / 2
                        controlLayout.y = event.y - controlLayout.height / 2
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {

                        // برنامه‌ریزی مخفی شدن نوار با تاخیر 10 ثانیه‌ای
                        controlLayout.postDelayed(hideControlLayoutRunnable, 20000)

                    }
                }
            }
            true
        }

        if (Role == "slave")
        {
            SlaveNetworkManager.sendOffsetToMaster(TimeSyncManager.getOffset())
            ivCaptureImage.visibility = View.INVISIBLE
        }
        else
        {
            ivCaptureImage.visibility = View.VISIBLE
        }
    }


    // در صورت تمایل، می‌توانید یک متد کمکی برای دریافت محدوده exposure از Camera2 اضافه کنید:
    private fun camera2ExposureTimeRange(): Range<Long>? {
        // اگر در کلاس Camera2 فیلد exposureCompensationRange عمومی (public) باشد
        // می‌توانید آن را مستقیماً بخوانید یا یک متد getter اضافه کنید.
        // به عنوان نمونه:
        return try {
            val field = Camera2::class.java.getDeclaredField("exposureCompensationRange")
            field.isAccessible = true
            field.get(camera2) as? Range<Long>
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun camera2IsoRange(): Range<Int>? {
        return try {
            val field = Camera2::class.java.getDeclaredField("isoRange")
            field.isAccessible = true
            field.get(camera2) as? Range<Int>
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    private fun camera2FocusRange(): Range<Float>? {
        return try {
            val field = Camera2::class.java.getDeclaredField("focusRange")
            field.isAccessible = true
            field.get(camera2) as? Range<Float>
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getMaxZoom(): Float? {
        return try {
            val field = Camera2::class.java.getDeclaredField("maxDigitalZoom")
            field.isAccessible = true
            field.get(camera2) as? Float
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun startRecording() {
        val outputFilePath = getVideoOutputPath(this@CustomCameraUI)

// ادامه‌ی تنظیمات MediaRecorder یا سایر عملیات ذخیره‌سازی...

        camera2.prepareVideoRecordingSession(this@CustomCameraUI , outputFilePath , exposureValue,isoValue , focusValue , frameRate?.toInt() ?: 30 , true)
        isRecording = true
        ivCaptureImage.setImageResource(R.drawable.stoprecordbutton)
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        camera2.stopRecordingVideo()
        isRecording = false
        ivCaptureImage.setImageResource(R.drawable.recordbutton)
        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }


    fun getVideoOutputPath(context: Context): String {
        val folderName = "DistributedCameras" // نام پوشه دلخواه شما
        var videoDirectory: File? = null

        // تلاش برای استفاده از پوشه DCIM
        val dcimDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        if (dcimDirectory.exists() || dcimDirectory.mkdirs()) {
            val primaryDirectory = File(dcimDirectory, folderName)
            if (primaryDirectory.exists() || primaryDirectory.mkdirs()) {
                videoDirectory = primaryDirectory
            } else {
                Log.e("FolderCreation", "ساخت پوشه $folderName در DCIM با خطا مواجه شد.")
            }
        } else {
            Log.e("FolderCreation", "دسترسی یا ساخت پوشه DCIM با خطا مواجه شد.")
        }

        // در صورت عدم موفقیت در ایجاد پوشه در DCIM، استفاده از مسیر پشتیبان در Android/data
        if (videoDirectory == null) {
            val fallbackDir = context.getExternalFilesDir(null)
            if (fallbackDir != null && (fallbackDir.exists() || fallbackDir.mkdirs())) {
                val fallbackDirectory = File(fallbackDir, folderName)
                if (fallbackDirectory.exists() || fallbackDirectory.mkdirs()) {
                    videoDirectory = fallbackDirectory
                } else {
                    Log.e("FolderCreation", "ساخت پوشه $folderName در مسیر پشتیبان با خطا مواجه شد.")
                }
            } else {
                Log.e("FolderCreation", "دسترسی یا ساخت پوشه پشتیبان در Android/data با خطا مواجه شد.")
            }
        }

        // اگر هنوز پوشه‌ای ایجاد نشده باشد، از پوشه fallbackDir (یا در نهایت filesDir) استفاده می‌کنیم
        if (videoDirectory == null) {
            Toast.makeText(this@CustomCameraUI , "اگر هنوز پوشه\u200Cای ایجاد نشده باشد، از پوشه fallbackDir (یا در نهایت filesDir) استفاده می\u200Cکنیم" , Toast.LENGTH_LONG).show()
            videoDirectory = context.getExternalFilesDir(null) ?: context.filesDir
        }

        // ایجاد فایل ویدئو با نام یکتا
        val videoFile = File(videoDirectory, "video_${System.currentTimeMillis()}.mp4")
        return videoFile.absolutePath
    }



    // پیاده‌سازی ScaleGestureDetector به عنوان inner class
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            currentZoom *= detector.scaleFactor
            currentZoom = currentZoom.coerceIn(1f, maxZoom)
            // فراخوانی تابع setZoom در کلاس دوربین برای اعمال زوم جدید
            camera2.setZoom(currentZoom)
            scheduleCalculateFramePeriod()
            return true
        }
    }

    override fun onMasterIpReceived(masterIp: String) {
        TODO("Not yet implemented")
    }

    override fun onConnectionStatusChanged(status: String) {
        TODO("Not yet implemented")
    }

    override fun onCameraSettingsReceived(settings: CameraSettings) {
        TODO("Not yet implemented")
    }

    override fun onTimeSyncUpdated(delay: Long, offset: Long) {
        Log.d("CustomCameraUI", "Time sync updated: delay=$delay, offset=$offset")
    }

    override fun onReadyForRecording(message : String) {
        if(message=="READY_FOR_RECORDING_STATUS_2")
        {
            runOnUiThread {
                Toast.makeText(this@CustomCameraUI, "Video Recorde clicked", Toast.LENGTH_SHORT).show()

                // اکشن برای دکمه ضبط
                if (!isRecording) {
                    startRecording()
                } else {
                    stopRecording()

                }
            }
        }
        else if (message.startsWith("READY_FOR_RECORDING_STATUS_3:")) {
                val triggerTimeStr = message.removePrefix("READY_FOR_RECORDING_STATUS_3:")
                val triggerTime = triggerTimeStr.toLongOrNull()
                if (triggerTime != null) {
                    scheduleRecording(triggerTime, applyOffset = true)
                } else {
                    Toast.makeText(this@CustomCameraUI, "زمان شروع نامعتبر است", Toast.LENGTH_SHORT).show()
                }
        }
    }

    override fun onError(errorMessage: String) {
        TODO("Not yet implemented")
    }

    private fun startScheduledRecording() {
        if (savedRole == "master") {
            tau?.let { T ->
                val currentTime = System.currentTimeMillis()
                val delay = 5000L // 5 seconds ahead
                val k = ((delay / T).toLong() + 1)
                val triggerTime = currentTime + (k * T).toLong()
                MasterNetworkManager.sendMessageToAllClients("READY_FOR_RECORDING_STATUS_3:$triggerTime")
                scheduleRecording(triggerTime, applyOffset = false)
                Toast.makeText(this, "Scheduled recording at $triggerTime", Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(this, "خطا: دوره فریم (tau) محاسبه نشده است", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "در حال آماده‌سازی ضبط زمان‌بندی‌شده", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleRecording(triggerTime: Long, applyOffset: Boolean = true) {
        if (!isTauCalculated) {
            Toast.makeText(this, "لطفاً صبر کنید تا دوره فریم محاسبه شود", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ scheduleRecording(triggerTime, applyOffset) }, 500)
            return
        }

        val localTriggerTime = if (applyOffset) {
            triggerTime - TimeSyncManager.getOffset()
        } else {
            triggerTime
        }
        val outputFilePath = getVideoOutputPath(this)

        tau?.let { T ->
            tau0?.let { tauZero ->
                val k = ceil((localTriggerTime - tauZero) / T).toLong()
                val startTime = tauZero + k * T
                val delay = (startTime - System.currentTimeMillis()).toLong()
                if (delay > 0) {
                    Thread {
                        Thread.sleep(delay)
                        runOnUiThread {
                            val localStartTime = System.currentTimeMillis()
                            displayLocalTime(localStartTime)
                            camera2.startRecordingVideo()
                            isRecording = true
                            ivCaptureImage.setImageResource(R.drawable.stoprecordbutton)
                            Toast.makeText(this, "Recording started at $startTime", Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                    runOnUiThread {
                        camera2.prepareVideoRecordingSession(
                            context = this,
                            outputFile = outputFilePath,
                            currentExposureValue = exposureValue,
                            currentIsoValue = isoValue,
                            currentFocusValue = focusValue,
                            framerate = frameRate?.toInt() ?: 30,
                            autostart = false
                        )
                    }
                } else {
                    runOnUiThread {
                        val localStartTime = System.currentTimeMillis()
                        displayLocalTime(localStartTime)
                        camera2.prepareVideoRecordingSession(
                            context = this,
                            outputFile = outputFilePath,
                            currentExposureValue = exposureValue,
                            currentIsoValue = isoValue,
                            currentFocusValue = focusValue,
                            framerate = frameRate?.toInt() ?: 30,
                            autostart = true
                        )
                        isRecording = true
                        ivCaptureImage.setImageResource(R.drawable.stoprecordbutton)
                        Toast.makeText(this, "Recording started immediately at $startTime", Toast.LENGTH_SHORT).show()
                    }
                }
                Log.d(TAG, "Tau: $T, Tau0: $tauZero, Delay: $delay, StartTime: $startTime")
            } ?: run {
                Toast.makeText(this, "خطا: فاز (tau0) محاسبه نشده است", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "خطا: دوره فریم (tau) محاسبه نشده است", Toast.LENGTH_SHORT).show()
        }

    }

    private fun displayLocalTime(localTime: Long) {
        val adjustedTime = if (savedRole == "slave") {
            localTime + TimeSyncManager.getOffset() // اعمال افست برای اسلیو
        } else {
            localTime // برای مستر بدون تغییر
        }
        val formattedTime = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(adjustedTime)
        txvtime.text = formattedTime
    }

    private fun scheduleCalculateFramePeriod() {
        calculateFramePeriodRunnable?.let { handler.removeCallbacks(it) } // حذف فراخوانی قبلی
        calculateFramePeriodRunnable = Runnable { calculateFramePeriod() }
        handler.postDelayed(calculateFramePeriodRunnable!!, 500) // تأخیر 500 میلی‌ثانیه
    }

    private fun calculateFramePeriod() {
        if (camera2.cameraCaptureSession == null) {
            Log.e(TAG, "Camera capture session is not ready")
            Toast.makeText(this, "خطا: دوربین آماده نیست", Toast.LENGTH_SHORT).show()
            return
        }
        camera2.collectTimestampsWithoutStopping(50) { timestampsNs ->
            if (timestampsNs.size >= 2) {
                val tauNs = estimateTau(timestampsNs)
                tau = tauNs / 1_000_000.0
                val t1 = timestampsNs[0].toDouble()
                val N = timestampsNs.map { ((it - t1) / tauNs).roundToInt() }
                tau0 = (timestampsNs.zip(N).map { (t, n) -> (t - n * tauNs) }.average()) / 1_000_000.0
                isTauCalculated = true
                Log.d(TAG, "Estimated tau: $tau ms, tau0: $tau0 ms for role: $savedRole")
                Toast.makeText(this, "Tau: $tau ms, Tau0: $tau0 ms", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Not enough timestamps: ${timestampsNs.size}")
                Toast.makeText(this, "خطا: تعداد timestampها کافی نیست (${timestampsNs.size})", Toast.LENGTH_SHORT).show()
                // تلاش مجدد برای جمع‌آوری
                Handler(Looper.getMainLooper()).postDelayed({ calculateFramePeriod() }, 1000)
            }
        }
    }

    fun estimateTau(timestampsNs: List<Long>): Double {
        if (timestampsNs.size < 2) return 0.0
        // محاسبه اختلافات بین زمان‌ها به‌صورت نانوثانیه
        val deltas = timestampsNs.zipWithNext { a, b -> (b - a).toDouble() }
        // مقدار اولیه τ را بر اساس کمترین اختلاف محاسبه می‌کنیم
        val tauInit = deltas.minOrNull() ?: return 0.0
        // تخمین مقدار ΔN_i با تقسیم هر اختلاف بر τ اولیه و گرد کردن به عدد صحیح
        val deltaNs = deltas.map { (it / tauInit).roundToInt() }
        // خوشه‌بندی اختلافات بر اساس مقدار ΔN_i
        val clusters = deltaNs.distinct().associateWith { k ->
            deltas.filterIndexed { index, _ -> deltaNs[index] == k }
        }
        // حل زیرمسائل: محاسبه τ_k برای هر خوشه
        val tauK = clusters.mapValues { (k, deltasK) ->
            deltasK.average() / k
        }
        // محاسبه وزن هر خوشه بر اساس تعداد داده‌ها و مقدار k^2
        val weights = clusters.mapValues { (k, deltasK) -> k.toDouble().pow(2) * deltasK.size }
        val totalWeight = weights.values.sum()
        // اگر مجموع وزن‌ها مثبت باشد، میانگین وزنی τ_k محاسبه و بازگردانده می‌شود
        return if (totalWeight > 0) {
            tauK.entries.sumOf { (k, tauK) -> weights[k]!! * tauK } / totalWeight
        } else {
            tauInit // در غیر این صورت مقدار اولیه τ برگردانده می‌شود
        }
    }

}
