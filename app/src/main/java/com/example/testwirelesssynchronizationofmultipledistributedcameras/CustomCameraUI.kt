package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import androidx.core.content.ContextCompat
import androidx.core.util.TypedValueCompat.dpToPx

class CustomCameraUI : Activity() {
    private lateinit var textureView: AutoFitTextureView
    private lateinit var camera2: Camera2

    // متغیرها برای ذخیره مقادیر
    private var flashStatus: String? = null
    private var frameRate: String? = null
    private var duration: String? = null

    // متغیرهای ImageView برای دکمه‌ها
    private lateinit var ivFlashAuto: ImageView
    private lateinit var ivCaptureImage: ImageView
    private lateinit var ivVideoSaved: ImageView
    private lateinit var ivRotateCamera: ImageView

    private lateinit var exposureSlider: SeekBar
    private var aeRange: Range<Int>? = null  // محدوده‌ی exposure compensation

    private var isRecording = false
    private var exposureValue :Int = 30

    companion object {
        private const val TAG = "CustomCameraUI"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // شفاف کردن Navigation Bar
        makeNavigationBarTransparent()

        setContentView(R.layout.activity_custom_camera_ui)

        // مقداردهی اولیه برای TextureView
        textureView = findViewById(R.id.camera_view)

        // مقداردهی اولیه Camera2
        camera2 = Camera2(this, textureView)

        // SharedPreferences برای ذخیره نقش
        val sharedPreferences: SharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        val savedRole = sharedPreferences.getString("user_role", "slave")
        // فراخوانی تابع initialize برای دریافت و ذخیره مقادیر
        initialize(savedRole.toString())

    }

    override fun onResume() {
        super.onResume()
        camera2.onResume() // مدیریت باز کردن دوربین و شروع Thread



        // پس از باز شدن دوربین، ممکن است بخواهید محدوده‌ی exposure compensation را دریافت کنید.
        // اگر هنوز در دسترس نیست، می‌توانید یک تاخیر کوتاه داشته باشید یا آن را در callback مربوط به
        // setUpCameraOutputs داخل Camera2 ذخیره کنید و سپس از طریق یک متد به اکتیویتی برگردانید.
        // در اینجا فرض می‌کنیم که exposureCompensationRange پس از openCamera تنظیم شده است.
        exposureSlider.postDelayed({
            aeRange = camera2ExposureRange() // متدی برای دریافت محدوده از Camera2 (یا مستقیماً استفاده از فیلد در Camera2 در صورت امکان)
            aeRange?.let {
                val lower = it.lower
                val upper = it.upper
                exposureSlider.max = upper - lower  // مثلا اگر range = [-2, +2]، max = 4
                // تنظیم مقدار پیش‌فرض نوار در وسط
                exposureSlider.progress = (upper - lower) / 2
            }
        }, 500)

        // تنظیم listener برای SeekBar
        exposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                aeRange?.let {
                    val lower = it.lower
                    val newExposure = lower + progress
                    exposureValue = newExposure
                    camera2.setExposureCompensation(newExposure)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) { }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { }
        })



    }

    override fun onPause() {
        super.onPause()
        camera2.close() // بستن دوربین و Thread در هنگام توقف Activity
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
/*
            // اکشن برای دکمه ضبط
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()

            }*/
        }

        ivVideoSaved.setOnClickListener {
            // اکشن برای دکمه ویدئو ذخیره شده
            Toast.makeText(this, "Video Saved clicked", Toast.LENGTH_SHORT).show()
        }

        ivRotateCamera.setOnClickListener {
            camera2.switchCamera()
        }

        // نمایش مقادیر در Log برای بررسی
        Log.d(TAG, "Flash Status: $flashStatus")
        Log.d(TAG, "Frame Rate: $frameRate")
        Log.d(TAG, "Duration: $duration")

        val hideSliderRunnable = Runnable {
            exposureSlider.visibility = View.GONE
        }

        // اضافه کردن listener لمس روی preview
        textureView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {


                    // حذف هر delayed hide قبلی
                    exposureSlider.removeCallbacks(hideSliderRunnable)

                    // فراخوانی متد بهینه‌سازی نوردهی قبل از نمایش seekbar
                    camera2.autoOptimizeExposure()
                    aeRange?.let {
                        val lower = it.lower
                        val upper = it.upper
                        exposureSlider.max = upper - lower  // مثلا اگر range = [-2, +2]، max = 4
                        // تنظیم مقدار پیش‌فرض نوار در وسط
                        exposureSlider.progress = (upper - lower) / 2
                    }


                    // نمایش نوار و تنظیم موقعیت (این تنظیمات ممکن است بسته به نیاز تغییر کند)
                    exposureSlider.visibility = View.VISIBLE
                    // تنظیم مختصات نوار (برای مثال می‌توانید با تغییر x/y موقعیت دلخواه را بدهید)
                    exposureSlider.x = event.x - exposureSlider.width / 2
                    exposureSlider.y = event.y - exposureSlider.height / 2

                    // فراخوانی performClick() برای پشتیبانی از قابلیت‌های دسترسی
                    view.performClick()
                }
                MotionEvent.ACTION_MOVE -> {
                    // به‌روز‌رسانی موقعیت نوار در صورت نیاز
                    exposureSlider.x = event.x - exposureSlider.width / 2
                    exposureSlider.y = event.y - exposureSlider.height / 2
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {

                    // برنامه‌ریزی مخفی شدن نوار با تاخیر 10 ثانیه‌ای
                    exposureSlider.postDelayed(hideSliderRunnable, 10000)

/*
                    exposureSlider.postDelayed({
                        exposureSlider.visibility = View.GONE
                    }, 10000)*/
                }
            }
            true
        }

        if (Role == "slave")
        {
            ivCaptureImage.visibility = View.INVISIBLE
        }
        else
        {
            ivCaptureImage.visibility = View.VISIBLE
        }
    }


    // در صورت تمایل، می‌توانید یک متد کمکی برای دریافت محدوده exposure از Camera2 اضافه کنید:
    private fun camera2ExposureRange(): Range<Int>? {
        // اگر در کلاس Camera2 فیلد exposureCompensationRange عمومی (public) باشد
        // می‌توانید آن را مستقیماً بخوانید یا یک متد getter اضافه کنید.
        // به عنوان نمونه:
        return try {
            val field = Camera2::class.java.getDeclaredField("exposureCompensationRange")
            field.isAccessible = true
            field.get(camera2) as? Range<Int>
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun startRecording() {
/*        val videoDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!videoDirectory.exists()) {
            videoDirectory.mkdirs() // اگه پوشه وجود نداشت، ایجادش کن
        }
        val videoFile = File(videoDirectory, "DistributedCameras/video_${System.currentTimeMillis()}.mp4")

// ساخت پوشه داخل Downloads اگه وجود نداشت
        if (!videoFile.parentFile!!.exists()) {
            videoFile.parentFile!!.mkdirs()
        }
        val outputFilePath = videoFile.absolutePath*/

        val outputFilePath = getVideoOutputPath(this@CustomCameraUI)

// ادامه‌ی تنظیمات MediaRecorder یا سایر عملیات ذخیره‌سازی...




        camera2.prepareVideoRecordingSession(outputFilePath , exposureValue , frameRate?.toInt() ?: 30 , true)
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


}
