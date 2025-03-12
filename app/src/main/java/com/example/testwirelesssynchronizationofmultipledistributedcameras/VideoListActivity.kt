package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class VideoListActivity : AppCompatActivity() {

    private lateinit var rvVideoList: RecyclerView
    private lateinit var adapter: VideoListAdapter
    private val videoItems = mutableListOf<VideoItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_list)

        rvVideoList = findViewById(R.id.rv_video_list)
        rvVideoList.layoutManager = LinearLayoutManager(this)

        loadVideoFiles()

        adapter = VideoListAdapter(videoItems) { position ->
            showUploadConfirmationDialog(position)
        }
        rvVideoList.adapter = adapter
    }

    private fun loadVideoFiles() {
        val videoDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "DistributedCameras")
        if (!videoDir.exists()) {
            Log.e("VideoList", "Video directory not found!")
            return
        }

        val timestampDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DistributedCameras")

        val videoFiles = videoDir.listFiles { _, name -> name.endsWith(".mp4") }
        videoFiles?.forEach { videoFile ->
            val videoName = videoFile.name
            val timestampName = videoName.replace(".mp4", "_timestamps.txt")
            val timestampFile = File(timestampDir, timestampName)

            if (timestampFile.exists()) {
                videoItems.add(VideoItem(videoFile.absolutePath, timestampFile.absolutePath))
            } else {
                Log.w("VideoList", "No matching timestamp file for $videoName")
            }
        }
    }

    private fun showUploadConfirmationDialog(position: Int) {
        AlertDialog.Builder(this)
            .setTitle("ارسال فایل‌ها")
            .setMessage("آیا می‌خواهید ویدئو و فایل timestamp را به سرور ارسال کنید؟")
            .setPositiveButton("بله") { _, _ ->
                adapter.uploadFile(position)
            }
            .setNegativeButton("خیر", null)
            .show()
    }
}

data class VideoItem(
    val videoPath: String,
    val timestampPath: String,
    var isUploaded: Boolean = false,
    var isUploading: Boolean = false
)