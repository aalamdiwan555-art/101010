package com.example

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.example.R
import androidx.cardview.widget.CardView

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()

        // Register low-latency engine callback
        AutoAcceptEngineService.statusListener = object : EngineStatusListener {
            override fun onStatusUpdated(status: String, active: Boolean) {
                Handler(Looper.getMainLooper()).post {
                    updateOverlayUI(status, active)
                }
            }
        }

        // Initialize with current engine state
        val isRunning = AutoAcceptEngineService.instance?.isEngineRunning() == true
        updateOverlayUI(if (isRunning) "Scanning" else "Engine Idled", isRunning)
    }

    private fun setupOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.floating_control_widget, null)

        // Window Layout parameters using TYPE_APPLICATION_OVERLAY
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        val overlayCard = overlayView?.findViewById<androidx.cardview.widget.CardView>(R.id.overlay_card)
        val dragHandle = overlayView?.findViewById<ImageView>(R.id.iv_drag_handle)
        val quickDisableBtn = overlayView?.findViewById<ImageButton>(R.id.btn_quick_disable)

        // Make the overlay draggable via touch listener
        dragHandle?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.let { p ->
                            p.x = initialX + (event.rawX - initialTouchX).toInt()
                            p.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(overlayView, p)
                        }
                        return true
                    }
                }
                return false
            }
        })

        quickDisableBtn?.setOnClickListener {
            // Directly turn off engine
            AutoAcceptEngineService.instance?.setEngineActive(false)
            // Save state to SharedPreferences
            val prefs = getSharedPreferences("DrClickerPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("engine_active", false).apply()
            
            // Broadcast update to MainActivity
            val intent = Intent("com.example.DR_CLICKER_STATE_CHANGED").apply {
                setPackage(packageName)
                putExtra("active", false)
            }
            sendBroadcast(intent)
            
            stopSelf()
        }

        windowManager.addView(overlayView, params)
    }

    private fun updateOverlayUI(status: String, active: Boolean) {
        val tvStatus = overlayView?.findViewById<TextView>(R.id.tv_overlay_status)
        val vStatusLight = overlayView?.findViewById<View>(R.id.v_status_light)

        tvStatus?.text = status
        vStatusLight?.backgroundTintList = ColorStateList.valueOf(
            if (active) Color.GREEN else Color.RED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        AutoAcceptEngineService.statusListener = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
