package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.ViewConfiguration
import android.view.WindowManager
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

    // MediaProjection variables
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionHandler: Handler? = null
    private var projectionThread: android.os.HandlerThread? = null

    private val CHANNEL_ID = "DrClickerChannel"
    private val NOTIFICATION_ID = 9999

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START_PROJECTION") {
            val resultCode = intent.getIntExtra("resultCode", -1)
            val projectionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("projectionIntent", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("projectionIntent")
            }
            if (resultCode != -1 && projectionIntent != null) {
                startProjection(resultCode, projectionIntent)
            }
        } else if (action == "STOP_PROJECTION") {
            stopProjection()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dr. Clicker Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Dr. Clicker Calibration Active")
            .setContentText("Real-time low-latency local mirroring session active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        projectionThread = android.os.HandlerThread("ProjectionThread").apply { start() }
        projectionHandler = Handler(projectionThread!!.looper)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        val dpi = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isEngineRunning) return@setOnImageAvailableListener
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastScreenshotTime > 400L) {
                    lastScreenshotTime = now

                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val rawBitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    rawBitmap.copyPixelsFromBuffer(buffer)

                    val softwareBitmap = if (rowPadding > 0) {
                        val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                        rawBitmap.recycle()
                        cropped
                    } else {
                        rawBitmap
                    }

                    executorService.submit {
                        try {
                            processSoftwareBitmapFrame(softwareBitmap)
                        } finally {
                            softwareBitmap.recycle()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image.close()
            }
        }, projectionHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DrClickerMirror",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            projectionHandler
        )

        isEngineRunning = true
        consecutiveMatchEventsCount = 0
        loadTemplate()
        statusListener?.onStatusUpdated("Scanning...", true)
    }

    private fun processSoftwareBitmapFrame(bitmap: Bitmap) {
        val currentTemplate = templateMat
        if (currentTemplate == null) {
            statusListener?.onStatusUpdated("No Template Loaded", true)
            return
        }

        try {
            val screenMat = Mat()
            Utils.bitmapToMat(bitmap, screenMat)

            val grayScreenMat = Mat()
            Imgproc.cvtColor(screenMat, grayScreenMat, Imgproc.COLOR_RGBA2GRAY)
            screenMat.release()

            val resultMat = Mat()
            Imgproc.matchTemplate(grayScreenMat, currentTemplate, resultMat, Imgproc.TM_CCOEFF_NORMED)
            grayScreenMat.release()

            val minMaxLocResult = Core.minMaxLoc(resultMat)
            resultMat.release()

            val correlationScore = minMaxLocResult.maxVal
            val matchLoc = minMaxLocResult.maxLoc

            Log.d("AutoAcceptEngine", "MediaProjection correlation score: $correlationScore")

            if (correlationScore >= 0.85) {
                consecutiveMatchEventsCount++
                if (consecutiveMatchEventsCount >= 3) {
                    Log.w("AutoAcceptEngine", "Consecutive match count limit hit. Issuing multi-touch pointer reset.")
                    triggerMultiTouchReset()
                    consecutiveMatchEventsCount = 0
                }

                Handler(Looper.getMainLooper()).post {
                    val details = extractWorkflowDetails()
                    val meetsCriteria = matchesFilters(details)

                    if (meetsCriteria) {
                        dispatchHumanizedClick(matchLoc, currentTemplate.cols(), currentTemplate.rows())
                    } else {
                        statusListener?.onStatusUpdated("Filtered out", true)
                    }
                }
            } else {
                consecutiveMatchEventsCount = 0
                statusListener?.onStatusUpdated("Scanning...", true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            statusListener?.onStatusUpdated("CV Processing Error", true)
        }
    }

    private fun dispatchHumanizedClick(matchLoc: Point, templateCols: Int, templateRows: Int) {
        val prefs = getSharedPreferences("DrClickerPrefs", Context.MODE_PRIVATE)
        val useHumanCoords = prefs.getBoolean("human_coordinates", true)
        val useHumanDelays = prefs.getBoolean("human_delays", true)

        val finalClickX: Float
        val finalClickY: Float

        if (useHumanCoords) {
            val safeWidthMin = (templateCols * 0.25).toInt()
            val safeWidthMax = (templateCols * 0.75).toInt()
            val safeHeightMin = (templateRows * 0.25).toInt()
            val safeHeightMax = (templateRows * 0.75).toInt()

            val randomX = matchLoc.x + if (safeWidthMax > safeWidthMin) Random.nextInt(safeWidthMin, safeWidthMax) else safeWidthMin
            val randomY = matchLoc.y + if (safeHeightMax > safeHeightMin) Random.nextInt(safeHeightMin, safeHeightMax) else safeHeightMin

            finalClickX = randomX.toFloat()
            finalClickY = randomY.toFloat()
        } else {
            finalClickX = (matchLoc.x + templateCols / 2f).toFloat()
            finalClickY = (matchLoc.y + templateRows / 2f).toFloat()
        }

        val reflexDelay = if (useHumanDelays) Random.nextLong(10L, 101L) else 0L
        statusListener?.onStatusUpdated("Accepting (Delay: ${reflexDelay}ms)", true)

        val clickRunnable = Runnable {
            val path = Path()
            path.moveTo(finalClickX, finalClickY)
            path.lineTo(finalClickX, finalClickY + 1f)

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
                    triggerMultiTouchReset()
                }
            }, null)
        }

        if (reflexDelay > 0) {
            Handler(Looper.getMainLooper()).postDelayed(clickRunnable, reflexDelay)
        } else {
            Handler(Looper.getMainLooper()).post(clickRunnable)
        }
    }

    private fun stopProjection() {
        isEngineRunning = false
        consecutiveMatchEventsCount = 0

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.stop()
            mediaProjection = null

            projectionThread?.quitSafely()
            projectionThread = null
            projectionHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        stopForeground(true)
        statusListener?.onStatusUpdated("Engine Idled", false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Accessibility event fallback is unused when MediaProjection is active
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
            val priceMatch = Regex("₹\\s*(\\d+)").find(text) ?: Regex("(\\d+)\\s*Rs").find(text)
            if (priceMatch != null) {
                details.price = priceMatch.groupValues[1].toIntOrNull()
            }

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
        stopProjection()
        instance = null
        statusListener = null
        templateMat?.release()
        executorService.shutdown()
    }
}
