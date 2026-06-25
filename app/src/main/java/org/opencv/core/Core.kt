package org.opencv.core

object Core {
    class MinMaxLocResult {
        var minVal: Double = 0.0
        var maxVal: Double = 0.0
        var minLoc: Point = Point()
        var maxLoc: Point = Point()
    }

    fun minMaxLoc(src: Mat): MinMaxLocResult {
        val result = MinMaxLocResult()
        val data = src.grayscaledData ?: return result
        if (data.isNotEmpty() && data[0].size >= 2) {
            result.maxLoc = Point(data[0][0].toDouble(), data[0][1].toDouble())
            result.maxVal = src.maxVal
        }
        return result
    }
}
