package com.example.privacylock

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var btnToggle: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            if (cameraGranted) {
                toggleService()
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        tvStatus = findViewById(R.id.tvStatus)
        btnAdmin = findViewById(R.id.btnAdmin)
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

        btnToggle.setOnClickListener {
            if (!dpm.isAdminActive(adminComponent)) {
                Toast.makeText(this, "Grant Device Admin first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            checkPermissionsAndToggle()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun checkPermissionsAndToggle() {
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            toggleService()
        } else {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun toggleService() {
        val intent = Intent(this, BackgroundCameraService::class.java)
        if (BackgroundCameraService.isRunning) {
            stopService(intent)
            BackgroundCameraService.isRunning = false
            Toast.makeText(this, "Background Guard Stopped", Toast.LENGTH_SHORT).show()
        } else {
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Background Guard Active! You can now switch to PW.", Toast.LENGTH_LONG).show()
        }
        updateUi()
    }

    private fun updateUi() {
        if (BackgroundCameraService.isRunning) {
            tvStatus.text = "Status: ACTIVE (Running in Background)"
            btnToggle.text = "2. Stop Background Protection"
        } else {
            tvStatus.text = "Status: Idle"
            btnToggle.text = "2. Start Background Protection"
        }
    }
}
