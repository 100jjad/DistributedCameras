//package com.example.testwirelesssynchronizationofmultipledistributedcameras
//
//import android.content.Intent
//import android.hardware.camera2.CameraCharacteristics
//import android.hardware.camera2.CameraManager
//import android.os.Bundle
//import android.util.Log
//import android.util.Range
//import android.widget.*
//import androidx.appcompat.app.AppCompatActivity
//import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.CameraSettings
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//import kotlinx.serialization.encodeToString
//import kotlinx.serialization.json.Json
//import java.io.BufferedReader
//import java.io.InputStreamReader
//import java.io.PrintWriter
//import java.net.DatagramPacket
//import java.net.DatagramSocket
//import java.net.InetAddress
//import java.net.NetworkInterface
//import java.net.ServerSocket
//import java.net.Socket
//import java.text.SimpleDateFormat
//import java.util.Date
//import java.util.Enumeration
//import java.util.LinkedList
//import java.util.Locale
//import java.util.Queue
//
//class MasterStatusActivity : AppCompatActivity() {
//
//    private val connectedSlaves = mutableListOf<String>() // لیست اسلیوهای متصل
//    private val slaveSockets = mutableListOf<Socket>()
//    private val scope = CoroutineScope(Dispatchers.IO)
//    private val messageQueue: Queue<Pair<String, Socket>> = LinkedList() // صف پیام‌ها
//    private lateinit var tvLocalTime: TextView
//    var t2: Long = 0
//
//    // لیست FPS های دریافتی
//    private val commonFpsList = mutableListOf<Int>() // لیست FPS
//    private lateinit var slaveListAdapter: ArrayAdapter<String>
//    private lateinit var spinnerAdapter: ArrayAdapter<String>
//
//
//    private lateinit var switchFlash: Switch
//    private lateinit var spinnerFrameRate: Spinner
//    private lateinit var etDuration: EditText
//
//
//    companion object {
//        private const val TAG = "MasterServer"
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_master_status)
//
//
//
//        val lvSlaves: ListView = findViewById(R.id.lvSlaves)
//        val btnSendSettings: Button = findViewById(R.id.btnSendSettings)
//        val btnVideoRecord: Button = findViewById(R.id.btnVideoRecord)
//        switchFlash = findViewById(R.id.switchFlash)
//        spinnerFrameRate = findViewById(R.id.spinnerFrameRate)
//        etDuration = findViewById(R.id.etDuration)
//        tvLocalTime = findViewById(R.id.tvlocaltime)
//
//
//        // مقداردهی آداپتر اسپینر
//        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
//        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
//        spinnerFrameRate.adapter = spinnerAdapter
//
//
//        // مقداردهی آداپتر لیست اسلیوها
//        slaveListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, connectedSlaves)
//        lvSlaves.adapter = slaveListAdapter
//
//
//        // راه‌اندازی سرور
////        setupMasterServer()
//
//
//        // ارسال تنظیمات
//        btnSendSettings.setOnClickListener {
//            val flashEnabled = switchFlash.isChecked
//            val frameRate = spinnerFrameRate.selectedItem.toString().toIntOrNull() ?: 30
//            val duration = etDuration.text.toString().toIntOrNull() ?: 60
//
//
//        }
//
//
//    }
//
//
//    private fun handleClient(clientSocket: Socket) {
//        scope.launch {
//            try {
//                val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
//                val output = PrintWriter(clientSocket.getOutputStream(), true)
//
//                // دریافت پیام از کلاینت
//                while (true) {
//                    val message = input.readLine()
//                    if (message != null) {
//                        if (message.startsWith("TIME_REQUEST")) {
//                            t2 = System.currentTimeMillis() // زمان دریافت درخواست
//                        }
//                        Log.d(TAG, "Message from client: $message")
//
//                        synchronized(messageQueue) {
//                            messageQueue.add(Pair(message, clientSocket))
//                        }
//
//                        processMessageQueue()
//
//                        // پیام دریافتی رو پردازش می‌کنیم
//                        //processClientMessage(message, clientSocket)
//
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error with client: ${e.message}", e)
//            } finally {
//                val clientAddress = clientSocket.inetAddress.hostAddress
//                if (!clientSocket.isClosed) {
//                    Log.d(TAG, "Client socket is still active, maintaining connection.")
//                } else {
//                    slaveSockets.remove(clientSocket)
//                    connectedSlaves.remove(clientAddress)
//                    runOnUiThread { slaveListAdapter.notifyDataSetChanged() }
//                    Log.d(TAG, "Client disconnected: $clientAddress")
//                }
//            }
//        }
//    }
//
//    private fun processMessageQueue() {
//        scope.launch {
//            while (true) {
//                val messagePair: Pair<String, Socket>?
//                synchronized(messageQueue) {
//                    messagePair = messageQueue.poll()
//                }
//                if (messagePair != null) {
//                    val (message, clientSocket) = messagePair
//                    processClientMessage(message, clientSocket)
//                } else {
//                    delay(100) // جلوگیری از اشغال CPU
//                }
//            }
//        }
//    }
//
//
//    private fun processClientMessage(message: String, clientSocket: Socket) {
//        when {
//            message.startsWith("FPS_Supported:") -> {
//                val parts = message.removePrefix("FPS_Supported:").split(":")
//                if (parts.size == 2) {
//                    val fpsList = parts[1].removeSurrounding("[", "]").split(", ")
//                        .mapNotNull { it.toIntOrNull() }
//
//                }
//            }
//
//            message.startsWith("START_RECORDING:") -> {
//                // در اینجا می‌توانید دستورات مربوط به شروع ضبط را پیاده‌سازی کنید
//                Log.d(TAG, "Received START_RECORDING command")
//            }
//
//            message.startsWith("TIME_REQUEST") -> {
//                scope.launch {
//                    try {
//                        val id = message.substringAfter("TIME_REQUEST:").trim()
//                        val output = PrintWriter(clientSocket.getOutputStream(), true)
//                        val t3 = System.currentTimeMillis() // زمان ارسال پاسخ
//                        output?.println("TIME_RESPONSE:$id,$t2,$t3")
//                        output?.flush()
//                        Log.d(
//                            TAG,
//                            "Sent TIME_RESPONSE: id=$id t2=$t2, t3=$t3 to ${clientSocket.inetAddress.hostAddress}"
//                        )
//                    } catch (e: Exception) {
//                        Log.e(TAG, "Error sending TIME_RESPONSE: ${e.message}", e)
//                    }
//                }
//            }
//
//            message.startsWith("STOP_SERVER:") -> {
//                Log.d(TAG, "Received STOP_SERVER command")
//
//            }
//
//            else -> {
//                Log.d(TAG, "Unknown message: $message")
//            }
//        }
//    }
//
//
//}