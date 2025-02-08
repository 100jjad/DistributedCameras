package com.example.testwirelesssynchronizationofmultipledistributedcameras

import com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass.CameraSettings

interface SlaveNetworkListener {
    fun onMasterIpReceived(masterIp: String)
    fun onConnectionStatusChanged(status: String)
    fun onCameraSettingsReceived(settings: CameraSettings)
    fun onTimeSyncUpdated(delay: Long, offset: Long)
    fun onReadyForRecording()
    fun onError(errorMessage: String)
}