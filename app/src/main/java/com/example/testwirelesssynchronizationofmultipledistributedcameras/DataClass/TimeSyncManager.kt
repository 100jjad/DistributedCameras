package com.example.testwirelesssynchronizationofmultipledistributedcameras.DataClass

import android.util.Log

// TimeSyncManager.kt

/**
 * این کلاس برای ذخیره‌سازی و دسترسی به مقادیر Delay و Offset طراحی شده است.
 * مقادیر به صورت Singleton نگهداری می‌شوند و از هر جای برنامه قابل دسترسی هستند.
 */
object TimeSyncManager {
    private var delay: Long = 0L
    private var offset: Long = 0L

    /**
     * مقدار Delay را تنظیم می‌کند.
     * @param value مقدار Delay بر حسب میلی‌ثانیه
     */
    fun setDelay(value: Long) {
        delay = value
    }

    /**
     * مقدار Offset را تنظیم می‌کند.
     * @param value مقدار Offset بر حسب میلی‌ثانیه
     */
    fun setOffset(value: Long) {
        offset = value
    }

    /**
     * مقدار Delay را برمی‌گرداند.
     * @return مقدار Delay فعلی
     */
    fun getDelay(): Long {
        return delay
    }

    /**
     * مقدار Offset را برمی‌گرداند.
     * @return مقدار Offset فعلی
     */
    fun getOffset(): Long {
        return offset
    }

    /**
     * نمایش مقادیر Delay و Offset برای اهداف دیباگ
     */
    fun logTimeSyncValues() {
        Log.d("TimeSyncManager","TimeSyncManager -> Delay: $delay ms, Offset: $offset ms")
    }
}

// استفاده از کلاس در SlaveStatusActivity
// TimeSyncManager.setDelay(avgDelay)
// TimeSyncManager.setOffset(avgOffset)
// val delay = TimeSyncManager.getDelay()
// val offset = TimeSyncManager.getOffset()
