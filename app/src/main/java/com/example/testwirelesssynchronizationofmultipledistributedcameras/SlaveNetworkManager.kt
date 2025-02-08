package com.example.testwirelesssynchronizationofmultipledistributedcameras

import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.CameraSettings
import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.TimeSyncManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Enumeration

object SlaveNetworkManager {
    // اتصالات مربوط به شبکه
    private var slaveSocket: Socket? = null
    private var output: PrintWriter? = null
    private var input: BufferedReader? = null
    private var masterIp: String? = null

    // متغیرهای مربوط به همگام‌سازی زمان
    private var totalDelay = 0L
    private var totalOffset = 0L
    private var validResponses = 0
    private var requestId = 0L
    private val requestTimes = mutableMapOf<Long, Long>()
    private val requestReciveTime = mutableMapOf<Long, Long>()

    // اسکوپ‌های Coroutine
    private val networkScope = CoroutineScope(Dispatchers.IO)
    private val timeSyncScope = CoroutineScope(Dispatchers.IO)

    // listener جهت اطلاع‌رسانی رویدادهای شبکه به UI
    var listener: SlaveNetworkListener? = null

    /**
     * شروع به گوش دادن برای دریافت IP مستر (با استفاده از Broadcast)
     */
    fun listenForMasterIp(port: Int) {
        networkScope.launch {
            try {
                val socket = DatagramSocket(port)
                socket.reuseAddress = true
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isActive) {
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    if (message.startsWith("MASTER_IP:")) {
                        masterIp = message.substringAfter("MASTER_IP:")
                        listener?.onMasterIpReceived(masterIp!!)
                        // پس از دریافت IP، به مستر متصل شوید (در اینجا از پورت 12345 استفاده شده است)
                        connectToMaster(masterIp!!, 12345)
                        break // پس از دریافت IP از حلقه خارج می‌شویم
                    }
                }
            } catch (e: Exception) {
                listener?.onError("خطا در دریافت IP مستر: ${e.message}")
            }
        }
    }

    /**
     * اتصال به مستر و شروع به دریافت پیام‌ها
     */
    fun connectToMaster(host: String, port: Int) {
        networkScope.launch {
            try {
                slaveSocket = Socket(host, port)
                output = PrintWriter(slaveSocket!!.getOutputStream(), true)
                input = BufferedReader(InputStreamReader(slaveSocket!!.getInputStream()))

                listener?.onConnectionStatusChanged("متصل")

                // شروع به خواندن پیام‌ها از مستر
                while (isActive) {
                    val message = input?.readLine() ?: break
                    handleMasterMessage(message)
                }
            } catch (e: Exception) {
                listener?.onError("خطا در اتصال به مستر: ${e.message}")
            }
        }
    }

    /**
     * ارسال پیام به مستر
     */
    fun sendMessage(message: String) {
        networkScope.launch {
            try {
                output?.println(message)
            } catch (e: Exception) {
                listener?.onError("خطا در ارسال پیام: ${e.message}")
            }
        }
    }

    /**
     * ارسال فهرست FPS های پشتیبانی شده به مستر
     */
    fun sendSupportedFps(localIp: String, supportedFps: List<Int>) {
        val message = "FPS_Supported:$localIp:$supportedFps"
        sendMessage(message)
    }

    /**
     * ارسال تایید دریافت تنظیمات به مستر
     */
    fun sendConfirmation() {
        sendMessage("CONFIRM_SETTINGS")
    }

    /**
     * شروع به همگام‌سازی زمان با مستر (ارسال چندین درخواست TIME_REQUEST)
     */
    fun synchronizeTime() {
        timeSyncScope.launch {
            try {
                repeat(10) {
                    val currentRequestId = requestId++
                    requestTimes[currentRequestId] = System.currentTimeMillis()
                    sendMessage("TIME_REQUEST:$currentRequestId")
                    delay(500) // وقفه بین درخواست‌ها
                }
                delay(3000)

                val avgDelay = if (validResponses > 0) totalDelay / validResponses else 0L
                val avgOffset = if (validResponses > 0) totalOffset / validResponses else 0L

                TimeSyncManager.setDelay(avgDelay)
                TimeSyncManager.setOffset(avgOffset)
                listener?.onTimeSyncUpdated(avgDelay, avgOffset)
            } catch (e: Exception) {
                listener?.onError("خطا در همگام‌سازی زمان: ${e.message}")
            }
        }
    }

    /**
     * پردازش پیام‌های دریافتی از مستر
     */
    private fun handleMasterMessage(message: String) {
        when {
            message.startsWith("MASTER_IP:") -> {
                masterIp = message.removePrefix("MASTER_IP:")
                listener?.onMasterIpReceived(masterIp!!)
            }
            message.startsWith("Camera_Setting:") -> {
                val jsonStr = message.removePrefix("Camera_Setting:")
                try {
                    val settings = Json.decodeFromString<CameraSettings>(jsonStr)
                    listener?.onCameraSettingsReceived(settings)
                } catch (e: Exception) {
                    listener?.onError("خطا در پردازش تنظیمات دوربین: ${e.message}")
                }
            }
            message.startsWith("TIME_RESPONSE:") -> {
                processTimeResponse(message)
            }
            message.startsWith("READY_FOR_RECORDING") -> {
                listener?.onReadyForRecording()
            }
            else -> {
                listener?.onError("پیام ناشناخته دریافت شد: $message")
            }
        }
    }

    /**
     * پردازش پاسخ‌های همگام‌سازی زمان
     */
    private fun processTimeResponse(response: String) {
        try {
            val timestamps = response.removePrefix("TIME_RESPONSE:").split(",")
            if (timestamps.size == 3) {
                val reqId = timestamps[0].toLong()
                val t2 = timestamps[1].toLongOrNull()
                val t3 = timestamps[2].toLongOrNull()
                val t4 = System.currentTimeMillis()
                val t1 = requestTimes[reqId] ?: return

                requestReciveTime[reqId] = (requestReciveTime[reqId] ?: 0) + 1

                if (t2 != null && t3 != null) {
                    val delay = (t4 - t1) - (t3 - t2)
                    val offset = ((t2 - t1) + (t3 - t4)) / 2
                    totalDelay += delay
                    totalOffset += offset
                    validResponses++

                    if (validResponses > 7) {
                        val avgDelay = totalDelay / validResponses
                        val avgOffset = totalOffset / validResponses
                        TimeSyncManager.setDelay(avgDelay)
                        TimeSyncManager.setOffset(avgOffset)
                        listener?.onTimeSyncUpdated(avgDelay, avgOffset)
                    }
                }
            }
        } catch (e: Exception) {
            listener?.onError("خطا در پردازش TIME_RESPONSE: ${e.message}")
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

    /**
     * بستن اتصال به مستر (برای آزادسازی منابع)
     */
    fun closeConnection() {
        try {
            slaveSocket?.close()
        } catch (e: Exception) {
            // در صورت بروز خطا می‌توان لاگ کرد
        }
    }
}
