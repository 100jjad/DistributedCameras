package com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass

import kotlinx.serialization.Serializable

@Serializable
data class CameraSettings(
    val flashEnabled: Boolean,
    val frameRate: Int,
    val videoDuration: Int
)
