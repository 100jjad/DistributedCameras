package com.example.testwirelesssynchronizationofmultipledistributedcameras

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.TextureView

class AutoFitTextureView : TextureView {
    private var mRatioWidth = 0
    private var mRatioHeight = 0
    private val mMatrix = Matrix() // برای تنظیم موقعیت و اندازه تصویر

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attributeSet: AttributeSet?) : this(context, attributeSet, 0)

    constructor(context: Context, attributeSet: AttributeSet?, defStyle: Int) : super(context, attributeSet, defStyle)

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be negative.")
        }
        mRatioWidth = width
        mRatioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (0 == mRatioWidth || 0 == mRatioHeight) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * mRatioWidth / mRatioHeight) {
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth)
            } else {
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height)
            }
        }
    }
/*
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // وقتی اندازه ویو تغییر کرد، موقعیت تصویر را تنظیم می‌کنیم
        adjustAspectRatio(w, h)
    }

    private fun adjustAspectRatio(viewWidth: Int, viewHeight: Int) {
        if (mRatioWidth == 0 || mRatioHeight == 0) {
            return
        }

        val aspectRatio = mRatioWidth.toFloat() / mRatioHeight.toFloat()
        val viewAspectRatio = viewWidth.toFloat() / viewHeight.toFloat()

        val scale: Float
        val dx: Float
        val dy: Float

        if (viewAspectRatio > aspectRatio) {
            // تصویر در ارتفاع محدود می‌شود
            scale = viewHeight.toFloat() / mRatioHeight.toFloat()
            dx = (viewWidth - mRatioWidth * scale) / 2
            dy = 0f
        } else {
            // تصویر در عرض محدود می‌شود
            scale = viewWidth.toFloat() / mRatioWidth.toFloat()
            dx = 0f
            dy = (viewHeight - mRatioHeight * scale) / 2
        }

        // اعمال تغییرات روی Matrix
        mMatrix.setScale(scale, scale)
        mMatrix.postTranslate(dx, dy)
        setTransform(mMatrix) // اعمال Matrix روی TextureView
    }*/
}