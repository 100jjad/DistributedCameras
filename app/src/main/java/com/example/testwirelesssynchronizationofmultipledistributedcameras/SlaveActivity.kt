package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.CameraSettings
import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.TimeSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class SlaveActivity : AppCompatActivity(), SlaveNetworkListener {

    private lateinit var supportedFps: List<Int>
    private lateinit var tvLocalTime: TextView
    private var updateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "SlaveDevice"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slave_status)

        supportedFps = getSupportedFps()
        tvLocalTime = findViewById(R.id.tvlocaltime)

        val btnConfirmSettings: Button = findViewById(R.id.btnConfirmSettings)
        btnConfirmSettings.setOnClickListener {
            // ارسال تایید تنظیمات به مستر
            SlaveNetworkManager.sendConfirmation()
            Toast.makeText(this, "تنظیمات تایید شد!", Toast.LENGTH_SHORT).show()
        }

        // تنظیم listener برای دریافت رویدادهای شبکه
        SlaveNetworkManager.listener = this

        // شروع به گوش دادن برای دریافت IP مستر
        SlaveNetworkManager.listenForMasterIp(7463)
        // شروع به همگام‌سازی زمان
        SlaveNetworkManager.synchronizeTime()
    }

    /**
     * دریافت IP مستر از طریق SlaveNetworkManager
     */
    override fun onMasterIpReceived(masterIp: String) {
        runOnUiThread {
            val tvMasterName: TextView = findViewById(R.id.tvMasterName)
            tvMasterName.text = masterIp
        }
    }

    /**
     * به‌روزرسانی وضعیت اتصال
     */
    override fun onConnectionStatusChanged(status: String) {
        runOnUiThread {
            findViewById<TextView>(R.id.tvConnectionStatus).text = status
        }
        SlaveNetworkManager.sendSupportedFps(SlaveNetworkManager.getLocalIpAddress().toString(), supportedFps)
    }

    /**
     * دریافت تنظیمات دوربین از مستر و به‌روزرسانی UI
     */
    override fun onCameraSettingsReceived(settings: CameraSettings) {
        runOnUiThread {
            findViewById<TextView>(R.id.tvFlashStatus).text =
                if (settings.flashEnabled) "روشن" else "خاموش"
            findViewById<TextView>(R.id.tvFrameRate).text = "${settings.frameRate}"
            findViewById<TextView>(R.id.tvDuration).text = "${settings.videoDuration} ثانیه"
            Toast.makeText(this, "تنظیمات دوربین دریافت شد", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * به‌روزرسانی همگام‌سازی زمان در UI
     */
    override fun onTimeSyncUpdated(delay: Long, offset: Long) {
        updateLocalTimePeriodically()
    }

    /**
     * فراخوانی انتقال تنظیمات جهت ضبط ویدئو پس از اعلام مستر
     */
    override fun onReadyForRecording() {
        runOnUiThread {
            transferCameraSettings()
        }
    }

    /**
     * دریافت خطاها از شبکه و نمایش Toast
     */
    override fun onError(errorMessage: String) {
        runOnUiThread {
            Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * به‌روز‌رسانی زمان محلی (بر اساس offset دریافتی از مستر)
     */
    private fun updateLocalTime(delay: Long, offset: Long) {
        val currentTime = System.currentTimeMillis()
        val masterTime = currentTime + offset
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val formattedTime = dateFormat.format(Date(masterTime))
        runOnUiThread {
            tvLocalTime.text = "Master Time: $formattedTime\nDelay: $delay ms\nOffset: $offset ms"
        }
    }

    private fun updateLocalTimePeriodically() {
        // اگر Job قبلی در حال اجرا است، آن را لغو کن
        updateJob?.cancel()
        // ایجاد Job جدید برای بروز رسانی زمان
        updateJob = scope.launch {
            while (isActive) { // اطمینان از اجرای امن در زمان لغو Job
                updateLocalTime(TimeSyncManager.getDelay(), TimeSyncManager.getOffset())
                delay(1000)
            }
        }
    }

    /**
     * استخراج FPS های قابل پشتیبانی از دوربین
     */
    private fun getSupportedFps(): List<Int> {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val fpsList = mutableListOf<Int>()
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val fpsRanges =
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                fpsRanges?.forEach { range ->
                    // اگر upper مقدار معتبر داشته باشد، به لیست اضافه کنید
                    val upper = (range).upper
                    if (upper > 0) {
                        fpsList.add(upper)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("FPS_ERROR", "خطا در دریافت FPS: ${e.message}")
        }
        return fpsList.distinct().sortedDescending()
    }

    /**
     * انتقال تنظیمات به صفحه ضبط ویدئو
     */
    private fun transferCameraSettings() {
        val flashStatus = findViewById<TextView>(R.id.tvFlashStatus).text.toString()
        val frameRate = findViewById<TextView>(R.id.tvFrameRate).text.toString()
        val duration = findViewById<TextView>(R.id.tvDuration).text.toString()

        if (flashStatus.isEmpty() || frameRate.isEmpty() || duration.isEmpty()) {
            Toast.makeText(this, "تنظیمات از مستر دریافت نشد", Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(this, CustomCameraUI::class.java)
            intent.putExtra("flash_status", flashStatus)
            intent.putExtra("frame_rate", frameRate)
            intent.putExtra("duration", duration)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // بستن اتصال شبکه
        SlaveNetworkManager.closeConnection()
    }
}
