package org.opencv.imgproc

import android.graphics.Color
import org.opencv.core.Mat

object Imgproc {
    const val COLOR_BGRA2GRAY = 1
    const val COLOR_RGBA2GRAY = 2
    const val TM_CCOEFF_NORMED = 3

    fun cvtColor(src: Mat, dst: Mat, code: Int) {
        val srcBitmap = src.bitmap ?: return
        val w = srcBitmap.width
        val h = srcBitmap.height

        val grayscaled = Array(h) { IntArray(w) }
        val pixels = IntArray(w * h)
        srcBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                val pixel = pixels[offset + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // Standard grayscale formula (luminance conversion)
                grayscaled[y][x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
        }
        dst.grayscaledData = grayscaled
    }

    fun matchTemplate(image: Mat, templ: Mat, result: Mat, method: Int) {
        val imgData = image.grayscaledData ?: return
        val tplData = templ.grayscaledData ?: return

        val imgH = imgData.size
        val imgW = imgData[0].size
        val tplH = tplData.size
        val tplW = tplData[0].size

        val resH = imgH - tplH + 1
        val resW = imgW - tplW + 1

        if (resH <= 0 || resW <= 0) return

        var maxScore = -1.0
        var bestX = 0
        var bestY = 0

        // Step 1: Coarse-grained pyramid grid search (stride = 8px)
        val coarseStep = 8
        for (y in 0 until resH step coarseStep) {
            for (x in 0 until resW step coarseStep) {
                val score = computeCorrelationAt(imgData, tplData, x, y, tplW, tplH)
                if (score > maxScore) {
                    maxScore = score
                    bestX = x
                    bestY = y
                }
            }
        }

        // Step 2: Fine-grained localized neighborhood search (radius = 8px)
        val startY = maxOf(0, bestY - coarseStep)
        val endY = minOf(resH - 1, bestY + coarseStep)
        val startX = maxOf(0, bestX - coarseStep)
        val endX = minOf(resW - 1, bestX + coarseStep)

        for (y in startY..endY) {
            for (x in startX..endX) {
                val score = computeCorrelationAt(imgData, tplData, x, y, tplW, tplH)
                if (score > maxScore) {
                    maxScore = score
                    bestX = x
                    bestY = y
                }
            }
        }

        // Output results mimicking the OpenCV core results matrix
        result.grayscaledData = Array(1) { IntArray(2) }
        result.grayscaledData?.set(0, intArrayOf(bestX, bestY))
        result.maxVal = maxScore
    }

    private fun computeCorrelationAt(
        img: Array<IntArray>,
        tpl: Array<IntArray>,
        startX: Int,
        startY: Int,
        w: Int,
        h: Int
    ): Double {
        var sumImg = 0.0
        var sumTpl = 0.0
        var sumSqImg = 0.0
        var sumSqTpl = 0.0
        var sumProd = 0.0

        // Subsample pixel comparisons inside comparison block (step=4) to dramatically boost CPU performance
        val step = 4
        var count = 0
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val iVal = img[startY + y][startX + x].toDouble()
                val tVal = tpl[y][x].toDouble()

                sumImg += iVal
                sumTpl += tVal
                sumSqImg += iVal * iVal
                sumSqTpl += tVal * tVal
                sumProd += iVal * tVal
                count++
            }
        }

        if (count == 0) return 0.0

        val meanImg = sumImg / count
        val meanTpl = sumTpl / count

        var num = 0.0
        var denImg = 0.0
        var denTpl = 0.0

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val iVal = img[startY + y][startX + x].toDouble()
                val tVal = tpl[y][x].toDouble()

                val dI = iVal - meanImg
                val dT = tVal - meanTpl

                num += dI * dT
                denImg += dI * dI
                denTpl += dT * dT
            }
        }

        val den = Math.sqrt(denImg * denTpl)
        if (den == 0.0) return 0.0

        return num / den
    }
}
