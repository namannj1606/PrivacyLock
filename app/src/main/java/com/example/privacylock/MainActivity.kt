package com.example.privacylock

import android.Manifest
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
    private lateinit var btnAccessibility: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            if (!cameraGranted) {
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
        btnAccessibility = findViewById(R.id.btnToggle)

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

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'PrivacyLock' and toggle it ON", Toast.LENGTH_LONG).show()
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

    private fun updateUi() {
        val adminActive = dpm.isAdminActive(adminComponent)
        if (adminActive) {
            tvStatus.text = "Status: Ready (Auto-activates with PW)"
            btnAdmin.text = "1. Device Admin: ACTIVE"
        } else {
            tvStatus.text = "Status: Device Admin Required"
            btnAdmin.text = "1. Grant Device Admin"
        }
        btnAccessibility.text = "2. Enable Auto-Trigger (Accessibility)"
    }
}
