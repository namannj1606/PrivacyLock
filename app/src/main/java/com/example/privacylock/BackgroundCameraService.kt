package com.example.privacylock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class BackgroundCameraService : LifecycleService() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var isAudioMonitoring = false
    private lateinit var audioExecutor: ExecutorService

    companion object {
        const val CHANNEL_ID = "privacy_guard_channel"
        const val NOTIF_ID = 1001
        var isRunning = false
        private const val SAMPLE_RATE = 16000

        // Dynamic Baseline Parameters
        private const val WARMUP_FRAMES = 30 // ~3 sec grace period to calibrate to PW volume
        private const val SPIKE_RATIO = 2.4 // Must be 2.4x louder than ongoing lecture audio
        private const val MIN_ABSOLUTE_RMS = 3000.0 // Minimum physical sound threshold
        private const val SMOOTHING_FACTOR = 0.08 // Alpha for Exponential Moving Average
    }

    override fun onCreate() {
        super.onCreate()
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        cameraExecutor = Executors.newSingleThreadExecutor()
        audioExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        startForegroundNotification()
        startBackgroundCamera()
        startBackgroundAudioMonitor()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Privacy Guard Active",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Privacy Guard Running")
            .setContentText("Adaptive visual and acoustic radar active...")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startBackgroundCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
            val detector = FaceDetection.getClient(options)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null && isRunning) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    detector.process(image)
                        .addOnSuccessListener { faces ->
                            if (faces.size >= 2 && isRunning) {
                                triggerLockdown()
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startBackgroundAudioMonitor() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize <= 0) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (AcousticEchoCanceler.isAvailable()) {
                audioRecord?.let {
                    aec = AcousticEchoCanceler.create(it.audioSessionId)
                    aec?.enabled = true
                }
            }

            audioRecord?.startRecording()
            isAudioMonitoring = true

            audioExecutor.execute {
                val buffer = ShortArray(bufferSize)
                var baselineRms = 1200.0
                var frameCount = 0
                var consecutiveSpikeCount = 0

                while (isRunning && isAudioMonitoring) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var sum = 0.0
                        for (i in 0 until readSize) {
                            sum += buffer[i] * buffer[i]
                        }
                        val currentRms = sqrt(sum / readSize)

                        // 1. Initial 3-second calibration phase
                        if (frameCount < WARMUP_FRAMES) {
                            baselineRms = (baselineRms + currentRms) / 2.0
                            frameCount++
                            continue
                        }

                        // 2. Anomaly Spike Detection
                        val dynamicThreshold = baselineRms * SPIKE_RATIO
                        if (currentRms > dynamicThreshold && currentRms > MIN_ABSOLUTE_RMS) {
                            consecutiveSpikeCount++
                            // Requires 2 consecutive buffer spikes (~200ms) to filter momentary digital clicks
                            if (consecutiveSpikeCount >= 2 && isRunning) {
                                triggerLockdown()
                            }
                        } else {
                            consecutiveSpikeCount = 0
                            // 3. Smooth continuous baseline adaptation (Tracks lecture volume changes)
                            baselineRms = (baselineRms * (1.0 - SMOOTHING_FACTOR)) + (currentRms * SMOOTHING_FACTOR)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun triggerLockdown() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.lockNow()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isAudioMonitoring = false

        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()

        try {
            aec?.release()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioExecutor.shutdown()
    }
}
