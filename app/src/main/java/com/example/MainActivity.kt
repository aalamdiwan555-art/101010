package com.example

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvEngineStatus: TextView
    private lateinit var switchEngine: SwitchCompat
    private lateinit var etMinPrice: TextInputEditText
    private lateinit var etMaxPrice: TextInputEditText
    private lateinit var etMinPickup: TextInputEditText
    private lateinit var etMaxDrop: TextInputEditText
    private lateinit var ivTemplate: ImageView
    private lateinit var tvImagePlaceholder: TextView
    private lateinit var btnUploadTemplate: Button

    // Permission Setup views
    private lateinit var ivStatusAccessibility: ImageView
    private lateinit var btnGrantAccessibility: Button
    private lateinit var ivStatusOverlay: ImageView
    private lateinit var btnGrantOverlay: Button

    // Humanized Clicking switches
    private lateinit var switchHumanCoordinates: SwitchCompat
    private lateinit var switchHumanDelays: SwitchCompat

    private lateinit var prefs: android.content.SharedPreferences

    private val switchListener: CompoundButton.OnCheckedChangeListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            val hasAccessibility = isAccessibilityServiceEnabled()
            val hasOverlay = Settings.canDrawOverlays(this)
            
            if (hasAccessibility && hasOverlay) {
                startEngine()
            } else {
                revertSwitch(false)
                Toast.makeText(this, "Please enable all required system permissions first.", Toast.LENGTH_LONG).show()
            }
        } else {
            stopEngine()
        }
    }

    private val pickTemplateLauncher: androidx.activity.result.ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            if (saveTemplateUri(uri)) {
                displayTemplateImage()
                // Instantly notify service if running
                AutoAcceptEngineService.instance?.loadTemplate()
                Toast.makeText(this, "Calibration template updated successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to parse selected image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.DR_CLICKER_STATE_CHANGED") {
                val active = intent.getBooleanExtra("active", false)
                switchEngine.setOnCheckedChangeListener(null)
                switchEngine.isChecked = active
                switchEngine.setOnCheckedChangeListener(switchListener)
                updateStatusText(active)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("DrClickerPrefs", Context.MODE_PRIVATE)

        // Bind Views
        tvEngineStatus = findViewById(R.id.tv_engine_status)
        switchEngine = findViewById(R.id.switch_engine)
        etMinPrice = findViewById(R.id.et_min_price)
        etMaxPrice = findViewById(R.id.et_max_price)
        etMinPickup = findViewById(R.id.et_min_pickup)
        etMaxDrop = findViewById(R.id.et_max_drop)
        ivTemplate = findViewById(R.id.iv_template)
        tvImagePlaceholder = findViewById(R.id.tv_image_placeholder)
        btnUploadTemplate = findViewById(R.id.btn_upload_template)

        // Bind permission Setup views
        ivStatusAccessibility = findViewById(R.id.iv_status_accessibility)
        btnGrantAccessibility = findViewById(R.id.btn_grant_accessibility)
        ivStatusOverlay = findViewById(R.id.iv_status_overlay)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)

        // Bind humanized click switches
        switchHumanCoordinates = findViewById(R.id.switch_human_coordinates)
        switchHumanDelays = findViewById(R.id.switch_human_delays)

        // Setup permission configure clicks
        btnGrantAccessibility.setOnClickListener {
            showAccessibilityGuideDialog()
        }

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // Setup humanized clicking toggles
        switchHumanCoordinates.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("human_coordinates", isChecked).apply()
        }
        switchHumanDelays.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("human_delays", isChecked).apply()
        }

        // Load saved parameter values
        loadSavedInputs()

        // Setup input watchers to capture user updates reactively
        setupInputWatchers()

        // Display existing template image if already chosen
        displayTemplateImage()

        // Bind unified activation switch
        switchEngine.setOnCheckedChangeListener(switchListener)

        btnUploadTemplate.setOnClickListener {
            pickTemplateLauncher.launch("image/*")
        }

        // Register State Broadcast Receiver
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter("com.example.DR_CLICKER_STATE_CHANGED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        // Refresh toggle state in case the engine was turned off externally
        val isActive = AutoAcceptEngineService.instance?.isEngineRunning() == true
        switchEngine.setOnCheckedChangeListener(null)
        switchEngine.isChecked = isActive
        switchEngine.setOnCheckedChangeListener(switchListener)
        updateStatusText(isActive)
    }

    private fun loadSavedInputs() {
        etMinPrice.setText(prefs.getString("min_price", ""))
        etMaxPrice.setText(prefs.getString("max_price", ""))
        etMinPickup.setText(prefs.getString("min_pickup", ""))
        etMaxDrop.setText(prefs.getString("max_drop", ""))

        switchHumanCoordinates.isChecked = prefs.getBoolean("human_coordinates", true)
        switchHumanDelays.isChecked = prefs.getBoolean("human_delays", true)
    }

    private fun setupInputWatchers() {
        etMinPrice.addTextChangedListener(SimpleTextWatcher { s ->
            prefs.edit().putString("min_price", s).apply()
        })
        etMaxPrice.addTextChangedListener(SimpleTextWatcher { s ->
            prefs.edit().putString("max_price", s).apply()
        })
        etMinPickup.addTextChangedListener(SimpleTextWatcher { s ->
            prefs.edit().putString("min_pickup", s).apply()
        })
        etMaxDrop.addTextChangedListener(SimpleTextWatcher { s ->
            prefs.edit().putString("max_drop", s).apply()
        })
    }

    private fun startEngine() {
        prefs.edit().putBoolean("engine_active", true).apply()
        
        // Start floating overlay
        startService(Intent(this, FloatingOverlayService::class.java))
        
        // Activate system engine context
        AutoAcceptEngineService.instance?.setEngineActive(true)
        updateStatusText(true)
    }

    private fun stopEngine() {
        prefs.edit().putBoolean("engine_active", false).apply()
        
        // Stop floating overlay
        stopService(Intent(this, FloatingOverlayService::class.java))
        
        // Deactivate system engine context
        AutoAcceptEngineService.instance?.setEngineActive(false)
        updateStatusText(false)
    }

    private fun revertSwitch(checked: Boolean) {
        switchEngine.setOnCheckedChangeListener(null)
        switchEngine.isChecked = checked
        switchEngine.setOnCheckedChangeListener(switchListener)
    }

    private fun showAccessibilityGuideDialog() {
        val dialogMessage = """
            👉 STEP 1: Find the Service (सर्विस ढूंढें)
            Settings page khulne par niche scroll karein aur "Downloaded Services" ya "Installed Apps" ya "More Services" par click karein.
            Wahan "Dr. Clicker Auto-Clicker Service" option ko ON (Enable) karein.
            
            ⚠️ STEP 2: Android 13/14/15 Restricted Setting (अगर आप्शन नहीं दब रहा)
            Sideloaded apps ke liye Android settings grayed-out (click nahi ho raha) kar deta hai. Isko theek karne ke liye:
            1. Home Screen par jayein aur "Dr. Clicker" app icon par long-press (दबाकर रखें) karein.
            2. "App Info" par click karein.
            3. Top Right corner me 3 dots (तीन बिंदु) par click karke "Allow restricted settings" select karein aur device screen lock confirm karein.
            4. Ab wapas is app me aakar configuration start karein!
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Accessibility Setup Guide")
            .setMessage(dialogMessage)
            .setCancelable(true)
            .setPositiveButton("Settings Kholen (Open Settings)") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        val serviceComponent = ComponentName(packageName, AutoAcceptEngineService::class.java.name).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            // Android standard extras
            putExtra("extra_fragment_arg_key", serviceComponent)
            val bundle = Bundle().apply {
                putString("extra_fragment_arg_key", serviceComponent)
            }
            putExtra("extra_show_fragment_arguments", bundle)

            // Deep-link extra with colon (works on Samsung, Xiaomi, Pixel etc.)
            putExtra(":settings:fragment_args_key", serviceComponent)
            putExtra(":settings:show_fragment_args", true)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(fallbackIntent)
        }
    }

    private fun updateStatusText(active: Boolean) {
        if (active) {
            tvEngineStatus.text = "Engine Status: ACTIVE"
            tvEngineStatus.setTextColor(Color.GREEN)
        } else {
            tvEngineStatus.text = "Engine Status: INACTIVE"
            tvEngineStatus.setTextColor(Color.parseColor("#FF4444"))
        }
    }

    private fun updatePermissionStatus() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)

        if (hasAccessibility) {
            ivStatusAccessibility.setImageResource(android.R.drawable.presence_online)
            ivStatusAccessibility.imageTintList = ColorStateList.valueOf(Color.GREEN)
            btnGrantAccessibility.text = "Enabled"
            btnGrantAccessibility.isEnabled = false
            btnGrantAccessibility.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#333333"))
            btnGrantAccessibility.setTextColor(Color.GRAY)
        } else {
            ivStatusAccessibility.setImageResource(android.R.drawable.presence_offline)
            ivStatusAccessibility.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF4444"))
            btnGrantAccessibility.text = "Configure"
            btnGrantAccessibility.isEnabled = true
            btnGrantAccessibility.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFD700"))
            btnGrantAccessibility.setTextColor(Color.parseColor("#121212"))
        }

        if (hasOverlay) {
            ivStatusOverlay.setImageResource(android.R.drawable.presence_online)
            ivStatusOverlay.imageTintList = ColorStateList.valueOf(Color.GREEN)
            btnGrantOverlay.text = "Enabled"
            btnGrantOverlay.isEnabled = false
            btnGrantOverlay.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#333333"))
            btnGrantOverlay.setTextColor(Color.GRAY)
        } else {
            ivStatusOverlay.setImageResource(android.R.drawable.presence_offline)
            ivStatusOverlay.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF4444"))
            btnGrantOverlay.text = "Configure"
            btnGrantOverlay.isEnabled = true
            btnGrantOverlay.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFD700"))
            btnGrantOverlay.setTextColor(Color.parseColor("#121212"))
        }

        // Only allow enabling the engine if both permissions are granted
        if (hasAccessibility && hasOverlay) {
            switchEngine.isEnabled = true
        } else {
            if (switchEngine.isChecked) {
                stopEngine()
                switchEngine.setOnCheckedChangeListener(null)
                switchEngine.isChecked = false
                switchEngine.setOnCheckedChangeListener(switchListener)
            }
            switchEngine.isEnabled = false
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, AutoAcceptEngineService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun saveTemplateUri(uri: Uri): Boolean {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return false
            val file = File(filesDir, "template.png")
            file.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun displayTemplateImage() {
        val file = File(filesDir, "template.png")
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                ivTemplate.setImageBitmap(bitmap)
                ivTemplate.imageTintList = null
                tvImagePlaceholder.visibility = View.GONE
            } else {
                ivTemplate.setImageResource(android.R.drawable.ic_menu_gallery)
                ivTemplate.imageTintList = ColorStateList.valueOf(Color.parseColor("#555555"))
                tvImagePlaceholder.visibility = View.VISIBLE
            }
        } else {
            ivTemplate.setImageResource(android.R.drawable.ic_menu_gallery)
            ivTemplate.imageTintList = ColorStateList.valueOf(Color.parseColor("#555555"))
            tvImagePlaceholder.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
    }

    // Small TextWatcher wrapper helper
    private class SimpleTextWatcher(private val onTextChanged: (String) -> Unit) : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            onTextChanged(s?.toString() ?: "")
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }
}
