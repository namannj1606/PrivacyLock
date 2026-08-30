package com.example.privacylock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

class AppTriggerAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            val isPW = packageName.contains("physicswallah", ignoreCase = true) ||
                       packageName.contains("penpencil", ignoreCase = true)

            val serviceIntent = Intent(this, BackgroundCameraService::class.java)

            if (isPW) {
                if (!BackgroundCameraService.isRunning) {
                    ContextCompat.startForegroundService(this, serviceIntent)
                }
            } else if (!packageName.contains("privacylock", ignoreCase = true)) {
                if (BackgroundCameraService.isRunning) {
                    stopService(serviceIntent)
                    BackgroundCameraService.isRunning = false
                }
            }
        }
    }

    override fun onInterrupt() {}
}
