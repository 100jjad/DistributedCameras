package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import androidx.appcompat.app.AppCompatActivity


class CameraActivity : AppCompatActivity(){
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        val textureView: TextureView = findViewById(R.id.textureView)

        textureView.surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                // تنظیم دوربین
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }
}