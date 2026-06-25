package org.opencv.core

import android.graphics.Bitmap

class Mat {
    var bitmap: Bitmap? = null
    var grayscaledData: Array<IntArray>? = null
    var maxVal: Double = 0.0

    fun cols(): Int = bitmap?.width ?: grayscaledData?.get(0)?.size ?: 0
    fun rows(): Int = bitmap?.height ?: grayscaledData?.size ?: 0

    fun release() {
        try {
            bitmap?.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        bitmap = null
        grayscaledData = null
    }
}
