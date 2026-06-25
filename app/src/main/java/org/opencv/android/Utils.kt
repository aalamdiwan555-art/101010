package org.opencv.android

import android.graphics.Bitmap
import org.opencv.core.Mat

object Utils {
    fun bitmapToMat(bitmap: Bitmap, mat: Mat) {
        // Explicitly copy buffer to ARGB_8888 software format to resolve Hardware bitmap restrictions
        mat.bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    }
}
