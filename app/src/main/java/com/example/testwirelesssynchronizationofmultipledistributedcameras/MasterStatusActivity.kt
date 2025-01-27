package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.CameraSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Enumeration
import java.util.LinkedList
import java.util.Locale
import java.util.Queue

class MasterStatusActivity : AppCompatActivity() {

    private val connectedSlaves = mutableListOf<String>() // لیست اسلیوهای متصل
    private val slaveSockets = mutableListOf<Socket>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val messageQueue: Queue<Pair<String, Socket>> = LinkedList() // صف پیام‌ها
    private lateinit var tvLocalTime: TextView
    var t2: Long = 0

    // لیست FPS های دریافتی
    private val commonFpsList = mutableListOf<Int>() // لیست FPS
    private lateinit var slaveListAdapter: ArrayAdapter<String>
    private lateinit var spinnerAdapter: ArrayAdapter<String>


    private lateinit var switchFlash: Switch
    private lateinit var spinnerFrameRate: Spinner
    private lateinit var etDuration: EditText


    companion object {
        private const val TAG = "MasterServer"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_master_status)

        broadcastMasterIp(7463)


        val lvSlaves: ListView = findViewById(R.id.lvSlaves)
        val btnSendSettings: Button = findViewById(R.id.btnSendSettings)
        val btnVideoRecord: Button = findViewById(R.id.btnVideoRecord)
        switchFlash = findViewById(R.id.switchFlash)
        spinnerFrameRate = findViewById(R.id.spinnerFrameRate)
        etDuration = findViewById(R.id.etDuration)
        tvLocalTime = findViewById(R.id.tvlocaltime)


        // مقداردهی آداپتر اسپینر
        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFrameRate.adapter = spinnerAdapter


        // مقداردهی آداپتر لیست اسلیوها
        slaveListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, connectedSlaves)
        lvSlaves.adapter = slaveListAdapter


        // راه‌اندازی سرور
//        setupMasterServer()
        initializeMasterFps()
        startServer(12345)


        // ارسال تنظیمات
        btnSendSettings.setOnClickListener {
            val flashEnabled = switchFlash.isChecked
            val frameRate = spinnerFrameRate.selectedItem.toString().toIntOrNull() ?: 30
            val duration = etDuration.text.toString().toIntOrNull() ?: 60

            sendSettingsToSlaves(CameraSettings(flashEnabled, frameRate, duration))

        }

        btnVideoRecord.setOnClickListener {
            startVideoRecording()
        }

        updateLocalTimePeriodically()
    }

    private fun initializeMasterFps() {
        val masterFpsList = getMasterSupportedFps()
        commonFpsList.addAll(masterFpsList)
        spinnerAdapter.addAll(masterFpsList.map { it.toString() })
        spinnerAdapter.notifyDataSetChanged()
    }


    // شروع سرور
    private fun startServer(port: Int) {
        scope.launch {
            try {
                val serverSocket = ServerSocket(port)
                Log.d(TAG, "Server started on port $port")

                while (true) {
                    val clientSocket = serverSocket.accept()
                    if (clientSocket != null) {
                        val clientAddress = clientSocket.inetAddress.hostAddress
                        connectedSlaves.add(clientAddress)
                        slaveSockets.add(clientSocket)
                        Log.d(TAG, "New slave connected: ${clientSocket.inetAddress.hostAddress}")
                        runOnUiThread { slaveListAdapter.notifyDataSetChanged() }
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in server: ${e.message}", e)
            }
        }
    }

    // اضافه کردن تابع ارسال IP به صورت Broadcast
    private fun broadcastMasterIp(port: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                socket.reuseAddress = true
                val ipAddress = getLocalIpAddress()
                if (ipAddress != null) {
                    // جدا کردن سه بخش اول آدرس IP و اضافه کردن بخش چهارم 255
                    val ipParts = ipAddress.split(".")
                    if (ipParts.size == 4) {
                        val broadcastIp = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}.255"

                        // ایجاد پیام و بسته Broadcast
                        val message = "MASTER_IP:$ipAddress"
                        val buffer = message.toByteArray()
                        val packet = DatagramPacket(
                            buffer,
                            buffer.size,
                            InetAddress.getByName(broadcastIp),
                            port
                        )
                        while (true) {
                            socket.send(packet)
                            delay(1000) // ارسال هر 1 ثانیه یک بار
                        }
                    }
                } else {
                    Log.e(TAG, "Unable to find local IP address")
                }// تابعی که IP دستگاه را دریافت می‌کند
            } catch (e: Exception) {
                Log.e(TAG, "Error in broadcastMasterIp : ${e.message}")
                e.printStackTrace()
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


    private fun handleClient(clientSocket: Socket) {
        scope.launch {
            try {
                val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val output = PrintWriter(clientSocket.getOutputStream(), true)

                // دریافت پیام از کلاینت
                while (true) {
                    val message = input.readLine()
                    if (message != null) {
                        if (message.startsWith("TIME_REQUEST")) {
                            t2 = System.currentTimeMillis() // زمان دریافت درخواست
                        }
                        Log.d(TAG, "Message from client: $message")

                        synchronized(messageQueue) {
                            messageQueue.add(Pair(message, clientSocket))
                        }

                        processMessageQueue()

                        // پیام دریافتی رو پردازش می‌کنیم
                        //processClientMessage(message, clientSocket)

                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with client: ${e.message}", e)
            } finally {
                val clientAddress = clientSocket.inetAddress.hostAddress
                if (!clientSocket.isClosed) {
                    Log.d(TAG, "Client socket is still active, maintaining connection.")
                } else {
                    slaveSockets.remove(clientSocket)
                    connectedSlaves.remove(clientAddress)
                    runOnUiThread { slaveListAdapter.notifyDataSetChanged() }
                    Log.d(TAG, "Client disconnected: $clientAddress")
                }
            }
        }
    }

    private fun processMessageQueue() {
        scope.launch {
            while (true) {
                val messagePair: Pair<String, Socket>?
                synchronized(messageQueue) {
                    messagePair = messageQueue.poll()
                }
                if (messagePair != null) {
                    val (message, clientSocket) = messagePair
                    processClientMessage(message, clientSocket)
                } else {
                    delay(100) // جلوگیری از اشغال CPU
                }
            }
        }
    }


    private fun processClientMessage(message: String, clientSocket: Socket) {
        when {
            message.startsWith("FPS_Supported:") -> {
                val parts = message.removePrefix("FPS_Supported:").split(":")
                if (parts.size == 2) {
                    val fpsList = parts[1].removeSurrounding("[", "]").split(", ")
                        .mapNotNull { it.toIntOrNull() }
                    updateCommonFps(fpsList)
                }
            }

            message.startsWith("START_RECORDING:") -> {
                // در اینجا می‌توانید دستورات مربوط به شروع ضبط را پیاده‌سازی کنید
                Log.d(TAG, "Received START_RECORDING command")
            }

            message.startsWith("TIME_REQUEST") -> {
                scope.launch {
                    try {
                        val id = message.substringAfter("TIME_REQUEST:").trim()
                        val output = PrintWriter(clientSocket.getOutputStream(), true)
                        val t3 = System.currentTimeMillis() // زمان ارسال پاسخ
                        output?.println("TIME_RESPONSE:$id,$t2,$t3")
                        output?.flush()
                        Log.d(
                            TAG,
                            "Sent TIME_RESPONSE: id=$id t2=$t2, t3=$t3 to ${clientSocket.inetAddress.hostAddress}"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending TIME_RESPONSE: ${e.message}", e)
                    }
                }
            }

            message.startsWith("STOP_SERVER:") -> {
                Log.d(TAG, "Received STOP_SERVER command")
                stopServer()
            }

            else -> {
                Log.d(TAG, "Unknown message: $message")
            }
        }
    }

    // ارسال تنظیمات به همه اسلیوها
    fun sendSettingsToSlaves(settings: CameraSettings) {
        val jsonSettings = Json.encodeToString(settings)
        scope.launch {
            for (socket in slaveSockets) {
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println("Camera_Setting:$jsonSettings")
                    Log.d(TAG, "Settings sent to ${socket.inetAddress.hostAddress}")
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Error sending settings to ${socket.inetAddress.hostAddress}: ${e.message}",
                        e
                    )
                }
            }
        }
    }


    private fun updateCommonFps(fpsList: List<Int>) {
        commonFpsList.retainAll(fpsList)
        runOnUiThread {
            spinnerAdapter.clear()
            spinnerAdapter.addAll(commonFpsList.map { it.toString() })
            spinnerAdapter.notifyDataSetChanged()
        }
        if (commonFpsList.isEmpty()) {
            spinnerAdapter.clear()
            spinnerAdapter.add("No Common FPS")
            spinnerAdapter.notifyDataSetChanged()
        }
    }

    // استخراج FPS قابل پشتیبانی
    private fun getMasterSupportedFps(): List<Int> {
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


    private fun startVideoRecording() {
        scope.launch {
            try {
                for (socket in slaveSockets) {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println("READY_FOR_RECORDING")
                    Log.d(
                        TAG,
                        "Sent READY_FOR_RECORDING command to ${socket.inetAddress.hostAddress}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending START_RECORDING command: ${e.message}", e)
            }
        }
        val flashEnabled = switchFlash.isChecked
        val frameRate = spinnerFrameRate.selectedItem.toString().toIntOrNull() ?: 30
        val duration = etDuration.text.toString().toIntOrNull() ?: 60
        val flashStatus = if (flashEnabled) "روشن" else "خاموش"

        // انتقال به صفحه بعدی با intent
        val intent = Intent(this, CustomCameraUI::class.java)

        // اضافه کردن مقادیر به Intent
        intent.putExtra("flash_status", flashStatus)
        intent.putExtra("frame_rate", frameRate.toString())
        intent.putExtra("duration", duration.toString())

        // شروع اکتیویتی بعدی
        startActivity(intent)
    }

    // بستن سرور
    fun stopServer() {
        scope.launch {
            try {
                slaveSockets.forEach { it.close() }
                slaveSockets.clear()
                Log.d(TAG, "MasterServer stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MasterServer: ${e.message}", e)
            }
        }
    }

}