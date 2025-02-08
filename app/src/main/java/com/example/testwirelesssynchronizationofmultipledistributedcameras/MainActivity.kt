package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Intent
import android.content.SharedPreferences
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.res.ResourcesCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val txvmaster: TextView = findViewById(R.id.tvmaster)
        val txvslave: TextView = findViewById(R.id.tvslave)
        val typeface = ResourcesCompat.getFont(this, R.font.opensansregular)
        txvmaster.typeface = typeface
        txvslave.typeface = typeface



        val imvmaster: ImageView = findViewById(R.id.ivmaster)
        val imvslave: ImageView = findViewById(R.id.ivslave)

        val floatInAnimation = AnimationUtils.loadAnimation(this, R.anim.float_in)


        imvmaster.startAnimation(floatInAnimation)
        imvslave.startAnimation(floatInAnimation)


        // دسترسی به دکمه‌ها
        val btnMaster: CardView = findViewById(R.id.btnMaster)
        val btnSlave: CardView = findViewById(R.id.btnSlave)

        // SharedPreferences برای ذخیره نقش
        val sharedPreferences: SharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)

        // کلیک دکمه مستر
        btnMaster.setOnClickListener {
            saveRole("master", sharedPreferences)
            navigateToStatusPage("master")
        }

        // کلیک دکمه اسلیو
        btnSlave.setOnClickListener {
            saveRole("slave", sharedPreferences)
            navigateToStatusPage("slave")
        }
    }

    private fun saveRole(role: String, sharedPreferences: SharedPreferences) {
        val editor = sharedPreferences.edit()
        editor.putString("user_role", role)
        editor.apply()
    }


    private fun navigateToStatusPage(role: String) {
        val intent = if (role == "master") {
            Intent(this, MasterActivity::class.java)
        } else {
            Intent(this, SlaveActivity::class.java)
        }
        startActivity(intent)
        finish()
    }


}