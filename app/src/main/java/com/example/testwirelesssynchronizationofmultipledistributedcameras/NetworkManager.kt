package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.util.Log
import com.example.testwirelesssynchronizationofmultipledistributedcameras.MasterStatusActivity.Companion
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
import java.util.Enumeration
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap

object NetworkManager {


    private var fpsUpdateListener: FpsUpdateListener? = null

    private const val TAG = "NetworkManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private val slaveSockets = ConcurrentHashMap<String, Socket>() // اتصالات اسلیوها بر اساس آدرس IP
    private val messageQueue: Queue<Pair<String, Socket>> = LinkedList()
    var t2: Long = 0

    fun startServer(port: Int, onClientConnected: (String) -> Unit, onClientDisconnected: (String) -> Unit) {
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Server started on port $port")

                while (true) {
                    val clientSocket = serverSocket!!.accept()
                    val clientAddress = clientSocket.inetAddress.hostAddress

                    slaveSockets[clientAddress] = clientSocket
                    Log.d(TAG, "New client connected: $clientAddress")
                    onClientConnected(clientAddress)

                    handleClient(clientSocket, onClientDisconnected)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in server: ${e.message}", e)
            }
        }
    }

    private fun handleClient(clientSocket: Socket, onClientDisconnected: (String) -> Unit) {
        scope.launch {
            try {
                val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))

                while (true) {
                    val message = input.readLine()
                    if (message != null) {
                        if (message.startsWith("TIME_REQUEST")) {
                            t2 = System.currentTimeMillis() // زمان دریافت درخواست
                        }
                        Log.d(TAG, "Message received: $message")

                        synchronized(messageQueue) {
                            messageQueue.add(Pair(message, clientSocket))
                        }

                        processMessageQueue()
                    }
                }
            } catch (e: Exception) {
                val clientAddress = clientSocket.inetAddress.hostAddress
                Log.e(TAG, "Client disconnected: $clientAddress", e)
                slaveSockets.remove(clientAddress)
                onClientDisconnected(clientAddress)
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
                    delay(100)
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
                    // فراخوانی متد listener
                    fpsUpdateListener?.onFpsListUpdated(fpsList)
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
                        output.println("TIME_RESPONSE:$id,$t2,$t3")
                        output.flush()
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

            // پیام‌های دیگر را اینجا پردازش کنید
            else -> Log.d(TAG, "Unknown message: $message")
        }
    }


    fun getLocalIpAddress(): String {
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
        return "" // در صورتی که آدرس پیدا نشد
    }

    fun broadcastMasterIp(port: Int, ipAddress: String) {
        scope.launch {
            try {
                val socket = DatagramSocket()
                socket.reuseAddress = true

                val ipParts = ipAddress.split(".")
                if (ipParts.size == 4) {
                    val broadcastIp = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}.255"
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
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting IP: ${e.message}", e)
            }
        }
    }



    fun sendMessageToAllClients(message: String) {
        scope.launch {
            slaveSockets.values.forEach { socket ->
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(message)
                    Log.d(TAG, "Message sent to ${socket.inetAddress.hostAddress}: $message")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending message to ${socket.inetAddress.hostAddress}: ${e.message}", e)
                }
            }
        }
    }

    fun sendSettingsToSlaves(message: String) {
        scope.launch {
            slaveSockets.values.forEach { socket ->
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(message)
                    Log.d(TAG, "Message sent to ${socket.inetAddress.hostAddress}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending message: ${e.message}", e)
                }
            }
        }
    }

    fun stopServer() {
        scope.launch {
            try {
                serverSocket?.close()
                slaveSockets.values.forEach { it.close() }
                slaveSockets.clear()
                Log.d(TAG, "Server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping server: ${e.message}", e)
            }
        }
    }


    interface FpsUpdateListener {
        fun onFpsListUpdated(fpsList: List<Int>)
    }

    // متد برای تنظیم listener
    fun setFpsUpdateListener(listener: FpsUpdateListener) {
        fpsUpdateListener = listener
    }
}
