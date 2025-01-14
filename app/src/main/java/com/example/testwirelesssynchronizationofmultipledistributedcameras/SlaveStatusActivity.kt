package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.util.Range
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.awt.font.NumericShaper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Enumeration
import java.util.Locale

class SlaveStatusActivity : AppCompatActivity() {
    // ارتباطات و مسیر ورودی و خروجی
    private var slaveSocket: Socket? = null
    private var output: PrintWriter? = null
    private var input: BufferedReader? = null
    private var masterIp: String? = null

    //متغیر های مربوط به همزمانی زمان محلی و کنترل تبادل پیام ها و تعداد
    private var totalDelay = 0L
    private var totalOffset = 0L
    private var validResponses = 0
    private var requestId = 0L
    private val requestTimes = mutableMapOf<Long, Long>()
    private var requestReciveTime = mutableMapOf<Long, Long>()
    private var updateJob: Job? = null

    //ترد های مربوط به پردازش موازی و اسکوپ های مختلف برنامه
    private val scope = CoroutineScope(Dispatchers.IO)
    private val scope2 = CoroutineScope(Dispatchers.IO)
    private val scope3 = CoroutineScope(Dispatchers.IO)

    //متغیر های مربوط به تنظیمات مشترک و نمایش
    private lateinit var supportedFps: List<Int>
    private lateinit var tvLocalTime: TextView

    companion object {
        private const val TAG = "SlaveDevice"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slave_status)

        supportedFps = getSupportedFps()


        val btnConfirmSettings: Button = findViewById(R.id.btnConfirmSettings)
        tvLocalTime = findViewById(R.id.tvlocaltime)

        // گوش دادن به Broadcast برای دریافت IP مستر
        listenForMasterIp(7463) // شماره پورتی که مستر Broadcast می‌کند


        // دکمه تایید دریافت تنظیمات
        btnConfirmSettings.setOnClickListener {
            // در اینجا می‌توانید کدی برای ارسال تایید به مستر اضافه کنید
            sendConfirmationToMaster()
        }

        // همگام‌سازی زمان
        synchronizeTimeWithMaster()

    }


    private fun connectToMaster(host: String, port: Int) {
        scope.launch {
            try {
                slaveSocket = Socket(host, port)
                output = PrintWriter(slaveSocket!!.getOutputStream(), true)
                input = BufferedReader(InputStreamReader(slaveSocket!!.getInputStream()))

                runOnUiThread {
                    findViewById<TextView>(R.id.tvConnectionStatus).text = "اسلیو متصل است"
                }

                sendSupportedFpsToMaster()
                // دریافت پیام‌های سرور
                while (true) {
                    val message = input?.readLine() ?: break
                    handleMasterMessage(message)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity,
                        "خطا در اتصال به سرور" + e.message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun handleMasterMessage(message: String) {

        if (message.startsWith("MASTER_IP:")) {
            masterIp = message.removePrefix("MASTER_IP:")
        } else if (message.startsWith("Camera_Setting:")) {
            // پیام‌های JSON تنظیمات را پردازش کنید
            val settings = parseCameraSettings(message.removePrefix("Camera_Setting:"))
            runOnUiThread {
                updateUIWithSettings(settings)
            }
        } else if (message.startsWith("Camera_Setting:")) {
            // پیام‌های JSON تنظیمات را پردازش کنید
            val settings = parseCameraSettings(message.removePrefix("Camera_Setting:"))
            runOnUiThread {
                updateUIWithSettings(settings)
                Toast.makeText(
                    this@SlaveStatusActivity,
                    "Cameara Parse ",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else if (message.startsWith("TIME_RESPONSE:")) {
            Log.w(TAG, "TIME_RESPONSE : $message")
            // فراخوانی ادامه محاسبات همگام‌سازی زمان
            processTimeResponse(message)
        } else if (message.startsWith("READY_FOR_RECORDING")) {
            Log.w(TAG, "Slave READY FOR RECORDING: $message")
            // انتقال به صفحه ضبط ویدئو برای اسلیو
            val intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)
        } else {
            Log.w(TAG, "پیام ناشناس دریافت شد: $message")
            // پیام‌های JSON تنظیمات را پردازش کنید
            /*            val settings = parseCameraSettings(message)
                        runOnUiThread {
                            updateUIWithSettings(settings)
                        }*/
        }
    }


    private fun listenForMasterIp(port: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket(port)
                socket.reuseAddress = true
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                while (true) {
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length)

                    if (message.startsWith("MASTER_IP:")) {
                        val masterIp = message.substringAfter("MASTER_IP:")

                        runOnUiThread {
                            val tvMasterName: TextView = findViewById(R.id.tvMasterName)
                            tvMasterName.text = masterIp
                        }
                        connectToMaster(masterIp, 12345)
                        break // پس از دریافت IP از حلقه خارج شوید
                    } else {
                        Log.d(
                            TAG,
                            "Unable to find local IP address : Try Field and message is : ${message}"
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Unable to find local IP address${e.message}")
                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity,
                        "خطا در دریافت IP مستر",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun parseCameraSettings(message: String): CameraSettings {
        // فرض بر این است که پیام از نوع JSON است
        val settings = Json.decodeFromString<CameraSettings>(message)
        return settings
    }


    private fun updateUIWithSettings(settings: CameraSettings) {
        findViewById<TextView>(R.id.tvFlashStatus).text =
            if (settings.flashEnabled) "روشن" else "خاموش"
        findViewById<TextView>(R.id.tvFrameRate).text = "${settings.frameRate} فریم بر ثانیه"
        findViewById<TextView>(R.id.tvDuration).text = "${settings.videoDuration} ثانیه"
    }


    private fun sendConfirmationToMaster() {
        scope.launch {
            try {
                //output?.println("CONFIRM_SETTINGS")
                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity,
                        "تنظیمات تایید شد!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity,
                        "خطا در ارسال تاییدیه",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // استخراج FPS قابل پشتیبانی
    private fun getSupportedFps(): List<Int> {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val fpsList = mutableListOf<Int>()

        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                // دریافت رنج‌های FPS
                val fpsRanges =
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                fpsRanges?.forEach { range ->
                    // بررسی می‌کنیم که مقدار از نوع android.util.Range باشد
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
            Log.e("FPS_ERROR", "خطا در دریافت FPS: ${e.message}")
        }

        // حذف مقادیر تکراری و مرتب‌سازی
        return fpsList.distinct().sortedDescending()
    }


    private fun sendSupportedFpsToMaster() {
        scope.launch {
            try {
                val message = "FPS_Supported:${getLocalIpAddress()}:$supportedFps"
                output?.println(message)
                Log.d(TAG, "Supported FPS sent to Master: $message")
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity,
                        "خطا در ارسال FPS به مستر",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface: NetworkInterface = interfaces.nextElement()
                val addresses: Enumeration<InetAddress> = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress: InetAddress = addresses.nextElement()
                    // چک کنید که آدرس IPv4 باشد و loopback نباشد
                    if (!inetAddress.isLoopbackAddress && inetAddress.hostAddress.indexOf(':') == -1) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null // در صورتی که آدرس پیدا نشد
    }

    private fun synchronizeTimeWithMaster() {
        scope2.launch {
            try {
                repeat(10) {
                    val currentRequestId = requestId++
                    requestTimes[currentRequestId] = System.currentTimeMillis()
                    output?.println("TIME_REQUEST:$currentRequestId")
                    Log.d(TAG, "Sent TIME_REQUEST to Master with ID: $currentRequestId")
                    delay(500) // وقفه بین درخواست‌ها
                }

                delay(3000)

                val avgDelay = TimeSyncManager.getDelay()
                val avgOffset = TimeSyncManager.getOffset()

                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity,
                        "Synchronization Complete: Delay=$avgDelay, Offset=$avgOffset",
                        Toast.LENGTH_LONG
                    ).show()
                }

                Log.d(TAG, "Final Average Offset: $avgOffset ms, Final Average Delay: $avgDelay ms")

                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity,
                        "synchronizeTimeWithMaster : $validResponses",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity,
                        " خطا در همگام‌سازی زمان",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun processTimeResponse(response: String) {
        try {
            val timestamps = response.removePrefix("TIME_RESPONSE:").split(",")
            if (timestamps.size == 3) {
                val requestId = timestamps[0].toLong()
                val t2 = timestamps[1].toLongOrNull()
                val t3 = timestamps[2].toLongOrNull()
                val t4 = System.currentTimeMillis()
                val t1 =
                    requestTimes[requestId] ?: return // فرض بر اینکه t1 در لحظه تقریبی شروع است

                // شمارش تعداد پیام‌های دریافتی
                requestReciveTime[requestId] = (requestReciveTime[requestId] ?: 0) + 1

                if (t2 != null && t3 != null) {
                    val delay = (t4 - t1) - (t3 - t2)
                    val offset = ((t2 - t1) + (t3 - t4)) / 2

                    totalDelay += delay
                    totalOffset += offset
                    validResponses++

                    Log.d(TAG, "Offset: $offset ms, Delay: $delay ms")

                    if (validResponses > 4) {
                        val avgDelay = totalDelay / validResponses
                        val avgOffset = totalOffset / validResponses

                        TimeSyncManager.setDelay(avgDelay)
                        TimeSyncManager.setOffset(avgOffset)

                        runOnUiThread {
                            Toast.makeText(
                                this@SlaveStatusActivity,
                                "processTimeResponse : $validResponses",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        updateLocalTimePeriodically()
                        Log.d(TAG, "Average Offset: $avgOffset ms, Average Delay: $avgDelay ms")

                        /*                        // بازنشانی مقادیر
                                                totalDelay = 0L
                                                totalOffset = 0L
                                                validResponses = 0*/
                    }
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(
                    this@SlaveStatusActivity,
                    "خطا در پردازش TIME_RESPONSE: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            Log.e(TAG, "خطا در پردازش TIME_RESPONSE: ${e.message}")
        }
    }


    private fun updateLocalTime(delay: Long, offset: Long) {
        val currentTime = System.currentTimeMillis()
        val masterTime = currentTime + offset // اعمال  Offset

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
        updateJob = scope3.launch {
            while (isActive) { // اطمینان از اجرای امن در زمان لغو Job
                updateLocalTime(TimeSyncManager.getDelay(), TimeSyncManager.getOffset())
                delay(1000)
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        slaveSocket?.close()
    }


}