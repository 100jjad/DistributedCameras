package com.example.testwirelesssynchronizationofmultipledistributedcameras.Temp

import kotlinx.coroutines.*
import android.util.Log
import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.CameraSettings
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class SlaveClient(private val masterIp: String, private val port: Int) {

    private var socket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "SlaveClient"
    }

    // اتصال به مستر
    fun connectToMaster() {
        scope.launch {
            try {
                socket = Socket(masterIp, port)
                Log.d(TAG, "Connected to Master at $masterIp:$port")
                listenForSettings()
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to Master: ${e.message}", e)
            }
        }
    }

    // گوش دادن به پیام‌های مستر
    private fun listenForSettings() {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                while (true) {
                    val jsonMessage = reader.readLine()
                    if (jsonMessage != null) {
                        val settings = Json.decodeFromString<CameraSettings>(jsonMessage)
                        Log.d(TAG, "Received settings: $settings")
                        processMasterMessage(jsonMessage)
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading settings: ${e.message}", e)
            }
        }
    }

    // پردازش پیام دریافتی از مستر
    private fun processMasterMessage(jsonMessage: String) {
        try {
            when {
                jsonMessage.contains("flashEnabled") -> {
                    val settings = Json.decodeFromString<CameraSettings>(jsonMessage)
                    Log.d(TAG, "Received settings: $settings")
                    applySettings(settings)
                }
                jsonMessage == "START_RECORDING" -> {
                    Log.d(TAG, "Received START_RECORDING command")
                    // شروع ضبط ویدئو
                }
                jsonMessage == "SYNC_TIME" -> {
                    Log.d(TAG, "Received SYNC_TIME command")
                    // هماهنگی زمان
                }
                else -> {
                    Log.d(TAG, "Unknown message: $jsonMessage")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message: ${e.message}", e)
        }
    }

    // اعمال تنظیمات دریافت‌شده
    private fun applySettings(settings: CameraSettings) {
        Log.d(
            TAG,
            "Applying settings: Flash=${settings.flashEnabled}, FrameRate=${settings.frameRate}, VideoDuration=${settings.videoDuration}"
        )
        // اینجا می‌توانید تنظیمات را به صورت واقعی اعمال کنید.
    }

    // ارسال پیام به مستر (در صورت نیاز)
    fun sendMessageToMaster(message: String) {
        scope.launch {
            try {
                val writer = PrintWriter(socket?.getOutputStream(), true)
                writer.println(message)
                Log.d(TAG, "Message sent to Master: $message")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message to Master: ${e.message}", e)
            }
        }
    }

    // قطع اتصال
    fun disconnect() {
        scope.launch {
            try {
                socket?.close()
                Log.d(TAG, "Disconnected from Master")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting from Master: ${e.message}", e)
            }
        }
    }
}