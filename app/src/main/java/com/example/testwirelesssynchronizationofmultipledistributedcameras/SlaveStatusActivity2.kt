package com.example.testwirelesssynchronizationofmultipledistributedcameras

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

class SlaveStatusActivity2 : AppCompatActivity() {

    private var slaveSocket: Socket? = null
    private var output: PrintWriter? = null
    private var input: BufferedReader? = null
    private var masterIp: String? = "192.168.1.133"
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var tvLocalTime: TextView

    companion object {
        private const val TAG = "SlaveDevice"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slave_status)


        val btnConfirmSettings: Button = findViewById(R.id.btnConfirmSettings)
        tvLocalTime = findViewById(R.id.tvlocaltime)

        // گوش دادن به Broadcast برای دریافت IP مستر
        connectToMaster("192.168.1.133" , 7463) // شماره پورتی که مستر Broadcast می‌کند


        // دکمه تایید دریافت تنظیمات
        btnConfirmSettings.setOnClickListener {
            // در اینجا می‌توانید کدی برای ارسال تایید به مستر اضافه کنید
            sendConfirmationToMaster()
        }


        updateLocalTimePeriodically()
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
                // دریافت پیام‌های سرور
                while (true) {
                    val message = input?.readLine() ?: break
                    handleMasterMessage(message)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity2,
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
        }
        else if(message.startsWith("TIME_RESPONSE:")){
            runOnUiThread{
                Toast.makeText(this@SlaveStatusActivity2 , "handleMasterMessage : $message" , Toast.LENGTH_LONG).show()
            }
        }
        else
        {
            // پیام‌های JSON تنظیمات را پردازش کنید
            val settings = parseCameraSettings(message)
        }
    }

    private fun parseCameraSettings(message: String): CameraSettings {
        // فرض بر این است که پیام از نوع JSON است
        val settings = Json.decodeFromString<CameraSettings>(message)
        return settings
    }


    private fun sendConfirmationToMaster() {
        scope.launch {
            try {
                synchronizeTimeWithMaster()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun synchronizeTimeWithMaster() {
        scope.launch {
            try {
                repeat(10) { // چند بار برای دقت بیشتر
                    val t1 = System.nanoTime()
                    output?.println("TIME_REQUEST")

                    val response = input?.readLine()
                    val t4 = System.nanoTime()

                    if (response != null && response.startsWith("TIME_RESPONSE:")) {
                        val t2 = response.removePrefix("TIME_RESPONSE:").toLong()


                        runOnUiThread{
                            Toast.makeText(this@SlaveStatusActivity2 , "synchronizeTimeWithMaster Function : $response" , Toast.LENGTH_LONG).show()
                        }


                        // محاسبه تأخیر و آفست
                        val delay = (t4 - t1) / 2
                        val offset = t2 - (t1 + delay)


                        Log.d(TAG, "Calculated Offset: $offset ns, Delay: $delay ns")
                        updateLocalTime(offset)
                    }

                    delay(500)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this@SlaveStatusActivity2,
                        "خطا در همگام‌سازی زمان",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    private fun updateLocalTime(offset: Long) {
        val currentTime = System.currentTimeMillis() + (offset / 1_000_000)
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val formattedTime = dateFormat.format(Date(currentTime))
        runOnUiThread {
            tvLocalTime.text = "Local Time: $formattedTime"
        }
    }

    private fun updateLocalTimePeriodically() {
        scope.launch {
            while (true) {
                updateLocalTime(0)
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