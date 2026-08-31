package com.example.privacylock

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private lateinit var tvStatus: TextView
    private lateinit var btnAdmin: Button
    private lateinit var btnUsage: Button
    private lateinit var btnToggle: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            if (!cameraGranted) {
                Toast.makeText(this, "Camera permission required.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        tvStatus = findViewById(R.id.tvStatus)
        btnAdmin = findViewById(R.id.btnAdmin)
        btnUsage = findViewById(R.id.btnUsage)
        btnToggle = findViewById(R.id.btnToggle)

        updateUi()

        btnAdmin.setOnClickListener {
            if (!dpm.isAdminActive(adminComponent)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required to lock device when 2 or more faces are detected."
                    )
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Device Admin already active!", Toast.LENGTH_SHORT).show()
            }
        }

        btnUsage.setOnClickListener {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'PrivacyLock' and enable Usage Access", Toast.LENGTH_LONG).show()
        }

        btnToggle.setOnClickListener {
            if (!dpm.isAdminActive(adminComponent)) {
                Toast.makeText(this, "Please grant Device Admin first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!hasUsageStatsPermission()) {
                Toast.makeText(this, "Please grant Usage Access first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            toggleService()
        }

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun toggleService() {
        val intent = Intent(this, BackgroundCameraService::class.java)
        if (BackgroundCameraService.isRunning) {
            stopService(intent)
            BackgroundCameraService.isRunning = false
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        updateUi()
    }

    private fun updateUi() {
        val adminActive = dpm.isAdminActive(adminComponent)
        val usageActive = hasUsageStatsPermission()

        btnAdmin.text = if (adminActive) "1. Device Admin: ACTIVE" else "1. Grant Device Admin"
        btnUsage.text = if (usageActive) "2. Usage Access: ACTIVE" else "2. Grant Usage Access"

        if (BackgroundCameraService.isRunning) {
            tvStatus.text = "Status: SENTINEL ARMED (Watching for PW)"
            btnToggle.text = "3. Stop Sentinel"
        } else {
            tvStatus.text = "Status: Idle / Standby"
            btnToggle.text = "3. Start Sentinel"
        }
    }
}
