package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.os.Bundle
import android.util.Log
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
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MasterStatusActivity2 : AppCompatActivity() {

    private val connectedSlaves = mutableListOf<String>() // لیست اسلیوهای متصل
    private val slaveSockets = mutableListOf<Socket>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var tvLocalTime: TextView

    private lateinit var slaveListAdapter: ArrayAdapter<String>


    companion object {
        private const val TAG = "MasterServer"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_master_status)


        val lvSlaves: ListView = findViewById(R.id.lvSlaves)
        val switchFlash: Switch = findViewById(R.id.switchFlash)
        val spinnerFrameRate: Spinner = findViewById(R.id.spinnerFrameRate)
        val etDuration: EditText = findViewById(R.id.etDuration)
        val btnSendSettings: Button = findViewById(R.id.btnSendSettings)
        tvLocalTime = findViewById(R.id.tvlocaltime)



        // مقداردهی آداپتر لیست اسلیوها
        slaveListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, connectedSlaves)
        lvSlaves.adapter = slaveListAdapter


        startServer(12345)

        // ارسال تنظیمات
        btnSendSettings.setOnClickListener {
            val flashEnabled = switchFlash.isChecked
            val frameRate = spinnerFrameRate.selectedItem.toString().toIntOrNull() ?: 30
            val duration = etDuration.text.toString().toIntOrNull() ?: 60

            sendSettingsToSlaves(CameraSettings(flashEnabled, frameRate, duration))

        }
        //updateLocalTimePeriodically()
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




    private fun handleClient(clientSocket: Socket) {
        scope.launch {
            try {
                val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val output = PrintWriter(clientSocket.getOutputStream(), true)

                // دریافت پیام از کلاینت
                while (true) {
                    val message = input.readLine()
                    if (message != null) {
                        Log.d(TAG, "Message from client: $message")

                        // پیام دریافتی رو پردازش می‌کنیم
                        processClientMessage(message, clientSocket)
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

    private fun processClientMessage(message: String, clientSocket: Socket) {

        if (message.startsWith("FPS_Supported:")) {
            val parts = message.removePrefix("FPS_Supported:").split(":")
            if (parts.size == 2) {
                val fpsList =
                    parts[1].removeSurrounding("[", "]").split(", ").mapNotNull { it.toIntOrNull() }

            }
        } else if (message.startsWith("START_RECORDING:")) {
            Log.d(TAG, "Received START_RECORDING command")
            // مدیریت هماهنگ‌سازی و ارسال دستور شروع ضبط
        } else if (message.startsWith("TIME_REQUEST"))
        {
            runOnUiThread { Toast.makeText(this@MasterStatusActivity2 , "TIME_REQUEST Block is Run" , Toast.LENGTH_LONG).show() }
            val t2 = System.nanoTime()
            sendTimeToSlaves(t2 , clientSocket)
            Log.d(TAG, "Sent TIME_RESPONSE: $t2")
            // هماهنگ‌سازی زمان بین دستگاه‌ها
        } else if (message.startsWith("STOP_SERVER:")) {
            Log.d(TAG, "Received STOP_SERVER command")
            stopServer()
        } else {
            Log.d(TAG, "Unknown message: $message")
        }
    }


    // ارسال تنظیمات به همه اسلیوها
    fun sendSettingsToSlaves(settings: CameraSettings) {
        val jsonSettings = Json.encodeToString(settings)
        scope.launch {
            for (socket in slaveSockets) {
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(jsonSettings)
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

    // ارسال تنظیمات به همه اسلیوها
    fun sendTimeToSlaves(t: Long, clientSocket: Socket) {
        scope.launch {
            try {
                val writer = PrintWriter(clientSocket.getOutputStream(), true)
                writer.println("TIME_RESPONSE:$t")
                Log.d(TAG, "Time sent to ${clientSocket.inetAddress.hostAddress}")
            } catch (e: Exception) {

                runOnUiThread { Toast.makeText(this@MasterStatusActivity2 , "sendTimeToSlaves Function Failed" , Toast.LENGTH_LONG).show() }
                Log.e(
                    TAG,
                    "Error sending Time to ${clientSocket.inetAddress.hostAddress}: ${e.message}",
                    e
                )
            }
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