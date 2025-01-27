package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.CameraSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Range
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MasterActivity : AppCompatActivity(), NetworkManager.FpsUpdateListener  {



    private val connectedSlaves = mutableListOf<String>() // لیست اسلیوهای متصل
    private val commonFpsList = mutableListOf<Int>() // لیست FPS
    private lateinit var switchFlash: Switch
    private lateinit var spinnerFrameRate: Spinner
    private lateinit var etDuration: EditText
    private lateinit var slaveListAdapter: ArrayAdapter<String>
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private lateinit var tvLocalTime: TextView

    private val scope = CoroutineScope(Dispatchers.Main) // برای آپدیت UI

    companion object {
        private const val TAG = "MasterStatusActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_master_status)

        val lvSlaves: ListView = findViewById(R.id.lvSlaves)
        val btnSendSettings: Button = findViewById(R.id.btnSendSettings)
        val btnVideoRecord: Button = findViewById(R.id.btnVideoRecord)
        switchFlash = findViewById(R.id.switchFlash)
        spinnerFrameRate = findViewById(R.id.spinnerFrameRate)
        etDuration = findViewById(R.id.etDuration)
        tvLocalTime = findViewById(R.id.tvlocaltime)

        // مقداردهی آداپتر لیست اسلیوها
        slaveListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, connectedSlaves)
        lvSlaves.adapter = slaveListAdapter


        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFrameRate.adapter = spinnerAdapter

        // استفاده از instance موجود NetworkManager و تنظیم listener
        NetworkManager.setFpsUpdateListener(this)

        // مقداردهی FPS
        initializeMasterFps()

        // به‌روزرسانی زمان محلی
        updateLocalTimePeriodically()

        NetworkManager.broadcastMasterIp(7463 , NetworkManager.getLocalIpAddress())
        // راه‌اندازی سرور با استفاده از NetworkManager
        NetworkManager.startServer(
            port = 12345,
            onClientConnected = { clientAddress ->
                // کدی که هنگام اتصال کلاینت جدید اجرا می‌شود
                connectedSlaves.add(clientAddress)
                runOnUiThread {
                    slaveListAdapter.notifyDataSetChanged()
                }
                Log.d("MasterStatusActivity", "Client connected: $clientAddress")
            },
            onClientDisconnected = { clientAddress ->
                // کدی که هنگام قطع اتصال کلاینت اجرا می‌شود
                Log.d("MasterStatusActivity", "Client disconnected: $clientAddress")
            }
        )

        // ارسال تنظیمات
        btnSendSettings.setOnClickListener {
            val flashEnabled = switchFlash.isChecked
            val frameRate = spinnerFrameRate.selectedItem.toString().toIntOrNull() ?: 30
            val duration = etDuration.text.toString().toIntOrNull() ?: 60
            val settings = CameraSettings(flashEnabled, frameRate, duration)

            // ارسال تنظیمات به تمام اسلیوها
            val jsonSettings = Json.encodeToString(settings)
            NetworkManager.sendMessageToAllClients("Camera_Setting:$jsonSettings")
        }

        // شروع ضبط ویدیو
        btnVideoRecord.setOnClickListener {
            startVideoRecording()
        }
    }

    private fun startVideoRecording() {
        // ارسال پیام به همه اسلیوها
        NetworkManager.sendMessageToAllClients("READY_FOR_RECORDING")

        val flashEnabled = switchFlash.isChecked
        val frameRate = spinnerFrameRate.selectedItem.toString().toIntOrNull() ?: 30
        val duration = etDuration.text.toString().toIntOrNull() ?: 60
        val flashStatus = if (flashEnabled) "روشن" else "خاموش"

        // انتقال به صفحه بعدی با intent
        val intent = Intent(this, CustomCameraUI::class.java)
        intent.putExtra("flash_status", flashStatus)
        intent.putExtra("frame_rate", frameRate.toString())
        intent.putExtra("duration", duration.toString())
        startActivity(intent)
    }

    private fun initializeMasterFps() {
        val masterFpsList = getMasterSupportedFps()
        commonFpsList.addAll(masterFpsList)
        spinnerAdapter.addAll(masterFpsList.map { it.toString() })
        spinnerAdapter.notifyDataSetChanged()
    }


    private fun getMasterSupportedFps(): List<Int> {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val fpsList = mutableListOf<Int>()

        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                fpsRanges?.forEach { range ->
                    if (range is Range<*>) {
                        val upper = (range as Range<Int>).upper
                        if (upper != null && upper > 0) {
                            fpsList.add(upper)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Error fetching FPS: ${e.message}")
        }

        return fpsList.distinct().sortedDescending()
    }

    private fun updateCommonFps(fpsList: List<Int>) {
        commonFpsList.retainAll(fpsList) // به روز رسانی لیست FPS‌های مشترک

        runOnUiThread {
            spinnerAdapter.clear()
            if (commonFpsList.isNotEmpty()) {
                spinnerAdapter.addAll(commonFpsList.map { it.toString() })
            } else {
                spinnerAdapter.add("No Common FPS")
            }
            spinnerAdapter.notifyDataSetChanged()
        }
    }



    private fun updateLocalTimePeriodically() {
        scope.launch {
            while (true) {
                val currentTime = System.currentTimeMillis()
                val formattedTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(
                    Date(currentTime)
                )
                runOnUiThread {
                    tvLocalTime.text = "Master Time: $formattedTime"
                }
                delay(1000)
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        // توقف سرور هنگام تخریب Activity
        NetworkManager.stopServer()
    }

    override fun onFpsListUpdated(fpsList: List<Int>) {
        updateCommonFps(fpsList)
    }


}
