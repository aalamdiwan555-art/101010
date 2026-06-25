package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

interface EngineStatusListener {
    fun onStatusUpdated(status: String, active: Boolean)
}

class RideDetails {
    var price: Int? = null
    var pickupDistance: Float? = null
    var dropDistance: Float? = null
}

class AutoAcceptEngineService : AccessibilityService() {

    private var isEngineRunning = false
    private val executorService = Executors.newSingleThreadExecutor()
    private var lastScreenshotTime = 0L
    private var templateMat: Mat? = null
    private var consecutiveMatchEventsCount = 0

    companion object {
        var instance: AutoAcceptEngineService? = null
            private set
        var statusListener: EngineStatusListener? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AutoAcceptEngine", "Accessibility Service Connected")
        
        // Initialize OpenCV
        if (OpenCVLoader.initDebug()) {
            Log.d("AutoAcceptEngine", "OpenCV successfully initialized debug mode")
        } else {
            Log.e("AutoAcceptEngine", "OpenCV initialization failed")
        }

        // Read active state from prefs
        val prefs = getSharedPreferences("DrClickerPrefs", Context.MODE_PRIVATE)
        isEngineRunning = prefs.getBoolean("engine_active", false)
        if (isEngineRunning) {
            loadTemplate()
        }
        statusListener?.onStatusUpdated(
            if (isEngineRunning) "Scanning" else "Engine Idled", 
            isEngineRunning
        )
    }

    fun isEngineRunning(): Boolean = isEngineRunning

    fun setEngineActive(active: Boolean) {
        isEngineRunning = active
        consecutiveMatchEventsCount = 0
        if (active) {
            loadTemplate()
        }
        statusListener?.onStatusUpdated(
            if (active) "Scanning" else "Engine Idled", 
            active
        )
    }

    fun loadTemplate() {
        executorService.submit {
            try {
                val file = File(filesDir, "template.png")
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val mat = Mat()
                        Utils.bitmapToMat(bitmap, mat)
                        
                        // Grayscale convert and cache this template:
                        val grayMat = Mat()
                        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGRA2GRAY)
                        
                        templateMat = grayMat
                        mat.release()
                        bitmap.recycle()
                        Log.d("AutoAcceptEngine", "Loaded template grayscaled mat successfully")
                    } else {
                        templateMat = null
                    }
                } else {
                    templateMat = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                templateMat = null
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isEngineRunning) return

        // Target Rapido Captain app
        val prefs = getSharedPreferences("DrClickerPrefs", Context.MODE_PRIVATE)
        val targetPkg = prefs.getString("target_package", "com.rapido.partner")?.trim() ?: "com.rapido.partner"
        val actualTarget = if (targetPkg.isBlank()) "com.rapido.partner" else targetPkg

        val pkg = event.packageName?.toString() ?: ""
        if (pkg == actualTarget || pkg == "com.rapido.partner" || pkg == "com.rapido.rider") {
            val now = System.currentTimeMillis()
            // Throttle screenshot captures to prevent freezing
            if (now - lastScreenshotTime > 400L) {
                lastScreenshotTime = now
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    captureAndMatchScreenshot()
                } else {
                    // Fallback to text matching on older SDKs
                    performNodeFallbackMatch()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndMatchScreenshot() {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        executorService.submit {
                            processScreenshot(screenshotResult)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("AutoAcceptEngine", "takeScreenshot failed: $errorCode")
                        statusListener?.onStatusUpdated("Capture Error", true)
                        // Trigger safety reset on hardware capture failures
                        triggerMultiTouchReset()
                    }
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processScreenshot(screenshotResult: ScreenshotResult) {
        val currentTemplate = templateMat
        if (currentTemplate == null) {
            // No template loaded. Attempt node-based match
            Handler(Looper.getMainLooper()).post {
                val found = performNodeFallbackMatch()
                if (!found) {
                    statusListener?.onStatusUpdated("No Template Loaded", true)
                }
            }
            return
        }

        try {
            // BITMAP MEMORY FIX: Copy Hardware-backed bitmap to Software configuration
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                screenshotResult.hardwareBuffer,
                screenshotResult.colorSpace
            ) ?: return

            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            
            val screenMat = Mat()
            Utils.bitmapToMat(softwareBitmap, screenMat)
            
            hardwareBitmap.recycle()
            softwareBitmap.recycle()

            // Grayscale screenMat
            val grayScreenMat = Mat()
            Imgproc.cvtColor(screenMat, grayScreenMat, Imgproc.COLOR_RGBA2GRAY)
            screenMat.release()

            // Execute pattern template matching
            val resultMat = Mat()
            Imgproc.matchTemplate(grayScreenMat, currentTemplate, resultMat, Imgproc.TM_CCOEFF_NORMED)
            grayScreenMat.release()

            val minMaxLocResult = Core.minMaxLoc(resultMat)
            resultMat.release()

            val correlationScore = minMaxLocResult.maxVal
            val matchLoc = minMaxLocResult.maxLoc

            Log.d("AutoAcceptEngine", "Correlation Score: $correlationScore")

            if (correlationScore >= 0.85) {
                // Read & parse workflow details from UI node trees
                var meetsCriteria = true
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    val details = extractWorkflowDetails()
                    meetsCriteria = matchesFilters(details)
                    
                    if (meetsCriteria) {
                        // Increment match events
                        consecutiveMatchEventsCount++
                        if (consecutiveMatchEventsCount >= 3) {
                            Log.w("AutoAcceptEngine", "Idle match limit hit. Dispatching dual reset.")
                            triggerMultiTouchReset()
                            consecutiveMatchEventsCount = 0
                        }

                        // Read preferences for humanization
                        val prefs = getSharedPreferences("DrClickerPrefs", Context.MODE_PRIVATE)
                        val useHumanCoords = prefs.getBoolean("human_coordinates", true)
                        val useHumanDelays = prefs.getBoolean("human_delays", true)

                        val clickX: Float
                        val clickY: Float
                        if (useHumanCoords) {
                            val safeWidthMin = (currentTemplate.cols() * 0.25).toInt()
                            val safeWidthMax = (currentTemplate.cols() * 0.75).toInt()
                            val safeHeightMin = (currentTemplate.rows() * 0.25).toInt()
                            val safeHeightMax = (currentTemplate.rows() * 0.75).toInt()
                            clickX = (matchLoc.x + Random.nextInt(safeWidthMin, safeWidthMax)).toFloat()
                            clickY = (matchLoc.y + Random.nextInt(safeHeightMin, safeHeightMax)).toFloat()
                        } else {
                            clickX = (matchLoc.x + currentTemplate.cols() / 2f).toFloat()
                            clickY = (matchLoc.y + currentTemplate.rows() / 2f).toFloat()
                        }

                        val reflexDelay = if (useHumanDelays) Random.nextLong(10L, 100L) else 0L
                        statusListener?.onStatusUpdated("Accepting (Delay: ${reflexDelay}ms)", true)

                        if (reflexDelay > 0) {
                            handler.postDelayed({
                                performClickGesture(clickX, clickY)
                            }, reflexDelay)
                        } else {
                            performClickGesture(clickX, clickY)
                        }
                    } else {
                        statusListener?.onStatusUpdated("Filtered out", true)
                    }
                }
            } else {
                statusListener?.onStatusUpdated("Scanning...", true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            statusListener?.onStatusUpdated("CV Processing Error", true)
        }
    }

    private fun extractWorkflowDetails(): RideDetails {
        val details = RideDetails()
        val root = rootInActiveWindow ?: return details
        traverseNodes(root, details)
        return details
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?, details: RideDetails) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        if (text.isNotBlank()) {
            parseTextForRideDetails(text, details)
        }
        for (i in 0 until node.childCount) {
            traverseNodes(node.getChild(i), details)
        }
    }

    private fun parseTextForRideDetails(text: String, details: RideDetails) {
        try {
            // Detect Pricing (₹ or Rs)
            val priceMatch = Regex("₹\\s*(\\d+)").find(text) ?: Regex("(\\d+)\\s*Rs").find(text)
            if (priceMatch != null) {
                details.price = priceMatch.groupValues[1].toIntOrNull()
            }

            // Detect Distances
            val kmMatches = Regex("(\\d+(?:\\.\\d+)?)\\s*km", RegexOption.IGNORE_CASE).findAll(text)
            for (match in kmMatches) {
                val valFloat = match.groupValues[1].toFloatOrNull() ?: continue
                val lower = text.lowercase()
                if (lower.contains("pickup") || lower.contains("away")) {
                    details.pickupDistance = valFloat
                } else if (lower.contains("drop") || lower.contains("dest") || lower.contains("deliver")) {
                    details.dropDistance = valFloat
                } else {
                    if (details.pickupDistance == null) {
                        details.pickupDistance = valFloat
                    } else if (details.dropDistance == null) {
                        details.dropDistance = valFloat
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun matchesFilters(details: RideDetails): Boolean {
        val prefs = getSharedPreferences("DrClickerPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("accept_all_rides", false)) {
            return true
        }
        val minPriceStr = prefs.getString("min_price", "") ?: ""
        val maxPriceStr = prefs.getString("max_price", "") ?: ""
        val minPickupStr = prefs.getString("min_pickup", "") ?: ""
        val maxDropStr = prefs.getString("max_drop", "") ?: ""

        val minPrice = if (minPriceStr.isBlank()) 0 else minPriceStr.toIntOrNull() ?: 0
        val maxPrice = if (maxPriceStr.isBlank()) 99999 else maxPriceStr.toIntOrNull() ?: 99999
        val minPickup = if (minPickupStr.isBlank()) 0.0f else minPickupStr.toFloatOrNull() ?: 0.0f
        val maxDrop = if (maxDropStr.isBlank()) 999.0f else maxDropStr.toFloatOrNull() ?: 999.0f

        val ridePrice = details.price ?: 0
        val ridePickup = details.pickupDistance ?: 0.0f
        val rideDrop = details.dropDistance ?: 0.0f

        if (details.price != null && (ridePrice < minPrice || ridePrice > maxPrice)) {
            return false
        }
        if (details.pickupDistance != null && (minPickup > 0.0f && ridePickup > minPickup)) {
            return false
        }
        if (details.dropDistance != null && (rideDrop > maxDrop)) {
            return false
        }
        return true
    }

    private fun performNodeFallbackMatch(): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText("Accept")
        if (nodes != null && nodes.isNotEmpty()) {
            for (node in nodes) {
                if (node.isVisibleToUser) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    val width = rect.width()
                    val height = rect.height()
                    if (width > 0 && height > 0) {
                        // Read preferences for humanization
                        val prefs = getSharedPreferences("DrClickerPrefs", Context.MODE_PRIVATE)
                        val useHumanCoords = prefs.getBoolean("human_coordinates", true)
                        val useHumanDelays = prefs.getBoolean("human_delays", true)

                        val clickX: Float
                        val clickY: Float
                        if (useHumanCoords) {
                            val insetX = (width * 0.25).toInt()
                            val insetY = (height * 0.25).toInt()
                            val randomOffsetX = if (insetX > 0) Random.nextInt(-insetX, insetX) else 0
                            val randomOffsetY = if (insetY > 0) Random.nextInt(-insetY, insetY) else 0
                            clickX = rect.centerX().toFloat() + randomOffsetX
                            clickY = rect.centerY().toFloat() + randomOffsetY
                        } else {
                            clickX = rect.centerX().toFloat()
                            clickY = rect.centerY().toFloat()
                        }

                        val reflexDelay = if (useHumanDelays) Random.nextLong(10L, 100L) else 0L
                        statusListener?.onStatusUpdated("Accepting (Delay: ${reflexDelay}ms)", true)

                        if (reflexDelay > 0) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                performClickGesture(clickX, clickY)
                            }, reflexDelay)
                        } else {
                            performClickGesture(clickX, clickY)
                        }
                        return true
                    } else if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        statusListener?.onStatusUpdated("Accept Sent (Text Fallback)", true)
                        playDualBeep()
                        return true
                    }
                }
            }
        }
        return false
    }

    // CLICK FIX & PATH CONSTRUCTION
    private fun performClickGesture(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        // Add a microscopic 1-pixel line shift to formulate a valid vector
        path.lineTo(x, y + 1f)

        val duration = ViewConfiguration.getTapTimeout().toLong()
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(stroke)

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                super.onCompleted(gestureDescription)
                statusListener?.onStatusUpdated("Accept Click Sent!", true)
                playDualBeep()
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                super.onCancelled(gestureDescription)
                statusListener?.onStatusUpdated("Click Cancelled", true)
                // STUCK POINTER WORKAROUND: Trigger immediate reset on failure/cancel
                triggerMultiTouchReset()
            }
        }, null)
    }

    // STUCK POINTER FIX: Programmatic asynchronous multi-touch reset gesture
    private fun triggerMultiTouchReset() {
        Log.d("AutoAcceptEngine", "Executing Android 15 touch reset queue sequence...")
        val path1 = Path().apply {
            moveTo(150f, 150f)
            lineTo(150f, 151f)
        }
        val path2 = Path().apply {
            moveTo(350f, 350f)
            lineTo(350f, 351f)
        }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 40L)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0L, 40L)

        val gestureBuilder = GestureDescription.Builder().apply {
            addStroke(stroke1)
            addStroke(stroke2)
        }

        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun playDualBeep() {
        try {
            val toneG = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneG.startTone(ToneGenerator.TONE_CDMA_PIP, 80)
            Handler(Looper.getMainLooper()).postDelayed({
                toneG.startTone(ToneGenerator.TONE_CDMA_PIP, 80)
            }, 120)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {
        Log.w("AutoAcceptEngine", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        statusListener = null
        templateMat?.release()
        executorService.shutdown()
    }
}
