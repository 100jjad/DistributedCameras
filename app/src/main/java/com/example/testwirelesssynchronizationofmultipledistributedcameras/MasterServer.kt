package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.CameraSettings
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class MasterServer(private val port: Int , context: Context) {

    private val slaveSockets = mutableListOf<Socket>()
    private val scope = CoroutineScope(Dispatchers.IO)

    private var onClientConnected: ((String) -> Unit)? = null
    private var onClientDisconnected: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "MasterServer"
    }

    // شروع سرور
    fun startServer() {
        scope.launch {
            try {
                val serverSocket = ServerSocket(port)
                Log.d(TAG, "MasterServer started on port $port")

                while (true) {
                    val clientSocket = serverSocket.accept()
                    if (clientSocket != null) {
                        slaveSockets.add(clientSocket)
                        Log.d(TAG, "New slave connected: ${clientSocket.inetAddress.hostAddress}")
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in MasterServer: ${e.message}", e)
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

                 /*   else {
                        break // پایان اتصال کلاینت
                    }*/
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with client: ${e.message}", e)

            } finally {
                val clientAddress = clientSocket.inetAddress.hostAddress
                slaveSockets.remove(clientSocket)
                if (!clientSocket.isClosed) {
                    Log.d(TAG, "Client socket is still active, maintaining connection.")
                } else {
                    slaveSockets.remove(clientSocket)
                    Log.d(TAG, "Client disconnected: $clientAddress")
                    onClientDisconnected?.invoke(clientAddress)
                }
            }
        }
    }

    private fun processClientMessage(message: String, clientSocket: Socket) {
        when (message) {
            "START_RECORDING" -> {
                Log.d("MasterServer", "Received START_RECORDING command")
                // مدیریت هماهنگ‌سازی و ارسال دستور شروع ضبط
            }
            "SYNC_TIME" -> {
                Log.d("MasterServer", "Received SYNC_TIME command")
                // هماهنگ‌سازی زمان بین دستگاه‌ها
            }
            "STOP_SERVER" -> {
                Log.d("MasterServer", "Received STOP_SERVER command")
                stopServer()
            }
            else -> {
                Log.d("MasterServer", "Unknown message: $message")
            }
        }
    }




/*    fun sendBroadcastMessage(settings: CameraSettings) {
        scope.launch {
            val settingsJson = Json.encodeToString(settings)
            slaveSockets.forEach { socket ->
                try {
                    val outputStream = ObjectOutputStream(socket.getOutputStream())
                    outputStream.writeObject(settingsJson)
                    outputStream.flush()
                    Log.d("MasterServer", "Settings broadcast to client: ${socket.inetAddress.hostAddress}")
                } catch (e: Exception) {
                    Log.e("MasterServer", "Error broadcasting settings to client: ${e.message}", e)
                }
            }
        }

    }*/

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
                    Log.e(TAG, "Error sending settings to ${socket.inetAddress.hostAddress}: ${e.message}", e)
                }
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



    // تنظیم Listener برای اتصال کلاینت
    fun setOnClientConnectedListener(listener: (String) -> Unit) {
        onClientConnected = listener
    }

    // تنظیم Listener برای قطع اتصال کلاینت
    fun setOnClientDisconnectedListener(listener: (String) -> Unit) {
        onClientDisconnected = listener
    }
}
