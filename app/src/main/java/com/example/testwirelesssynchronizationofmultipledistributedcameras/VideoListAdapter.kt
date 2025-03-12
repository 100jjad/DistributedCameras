package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.content.Context
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class VideoListAdapter(
    private val videoItems: List<VideoItem>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VideoViewHolder>() {

    private lateinit var context: Context // متغیر برای نگه‌داری context

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        context = parent.context // context رو اینجا مقداردهی می‌کنیم
        val view = LayoutInflater.from(context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = videoItems[position]

        // نمایش thumbnail ویدئو
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(item.videoPath)
            val bitmap = retriever.getFrameAtTime(0)
            holder.ivVideoThumbnail.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            retriever.release()
        }

        holder.tvVideoPath.text = item.videoPath
        holder.tvTimestampPath.text = item.timestampPath
        holder.ivUploadStatus.visibility = if (item.isUploaded) View.VISIBLE else View.GONE
        holder.pbUploadProgress.visibility = if (item.isUploading) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onItemClick(position) }
    }

    override fun getItemCount(): Int = videoItems.size

    fun uploadFile(position: Int) {
        val item = videoItems[position]
        if (item.isUploaded || item.isUploading) return

        item.isUploading = true
        notifyItemChanged(position)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()

                val videoFile = File(item.videoPath)
                val timestampFile = File(item.timestampPath)

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "video", videoFile.name,
                        okhttp3.RequestBody.create("video/mp4".toMediaType(), videoFile)
                    )
                    .addFormDataPart(
                        "timestamp", timestampFile.name,
                        okhttp3.RequestBody.create("text/plain".toMediaType(), timestampFile)
                    )
                    .build()

                val request = Request.Builder()
                    .url("YOUR_SERVER_URL_HERE") // آدرس سرور رو اینجا بذار
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    item.isUploading = false
                    item.isUploaded = true
                    notifyItemChangedOnUiThread(position, "فایل‌ها با موفقیت ارسال شدند")
                } else {
                    item.isUploading = false
                    notifyItemChangedOnUiThread(position, "خطا در ارسال: ${response.message}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                item.isUploading = false
                notifyItemChangedOnUiThread(position, "خطا: ${e.message}")
            }
        }
    }

    private fun notifyItemChangedOnUiThread(position: Int, message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            notifyItemChanged(position)
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivVideoThumbnail: ImageView = itemView.findViewById(R.id.iv_video_thumbnail)
        val tvVideoPath: TextView = itemView.findViewById(R.id.tv_video_path)
        val tvTimestampPath: TextView = itemView.findViewById(R.id.tv_timestamp_path)
        val ivUploadStatus: ImageView = itemView.findViewById(R.id.iv_upload_status)
        val pbUploadProgress: ProgressBar = itemView.findViewById(R.id.pb_upload_progress)
    }
}