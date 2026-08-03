package com.example.vhpmatchpresentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RawCameraRecordService : Service() {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var isRecording = false

    companion object {
        const val TAG = "RawCameraRecord"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_CAMERA_FACING = "EXTRA_CAMERA_FACING"
        var rawPreviewSurface: Surface? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val facing = intent.getIntExtra(EXTRA_CAMERA_FACING, CameraCharacteristics.LENS_FACING_BACK)
                startRecording(facing)
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(facing: Int) {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "RawCameraChannel")
            .setContentTitle("VHP Camera Recording")
            .setContentText("Recording camera stream...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(2, notification)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            stopSelf()
            return
        }

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCameraId(cameraManager, facing) ?: run {
            Log.e(TAG, "No camera found for facing $facing")
            stopSelf()
            return
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val videoSize = findBest60fpsSize(characteristics)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentFile = File(cacheDir, "VHP_Camera_${videoSize.width}x${videoSize.height}_$timestamp.mp4")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(currentFile!!.absolutePath)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(50_000_000)
            setVideoFrameRate(60)
            setAudioSamplingRate(48000)
            setAudioEncodingBitRate(192000)
            prepare()
        }

        val recorderSurface = mediaRecorder!!.surface

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surfaces = mutableListOf(recorderSurface)
                    rawPreviewSurface?.let { surfaces.add(it) }

                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(recorderSurface)
                                rawPreviewSurface?.let { addTarget(it) }
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            }
                            session.setRepeatingRequest(captureRequest.build(), null, null)
                            mediaRecorder?.start()
                            isRecording = true
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Camera session configuration failed")
                            stopSelf()
                        }
                    }, null)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    stopSelf()
                }
            }, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception opening camera", e)
            stopSelf()
        }
    }

    private fun findCameraId(manager: CameraManager, facing: Int): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                return id
            }
        }
        return null
    }

    private fun findBest60fpsSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val videoSizes = map.getOutputSizes(MediaRecorder::class.java)

        val preferredSizes = listOf(
            Size(2560, 1440),
            Size(3840, 2160),
            Size(1920, 1080),
            Size(1280, 720)
        )

        for (preferred in preferredSizes) {
            if (videoSizes?.any { it.width == preferred.width && it.height == preferred.height } == true) {
                return preferred
            }
        }

        return videoSizes?.filter {
            val ratio = it.width.toFloat() / it.height.toFloat()
            ratio in 1.7f..1.8f
        }?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
    }

    private fun stopRecording() {
        try {
            if (isRecording) {
                captureSession?.stopRepeating()
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                isRecording = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        captureSession?.close()
        cameraDevice?.close()
        captureSession = null
        cameraDevice = null

        currentFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                saveVideoToGallery(file)
            }
        }
    }

    private fun saveVideoToGallery(videoFile: File) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VHPMatchPresentation")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    videoFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                videoFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving video", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "RawCameraChannel",
                "Raw Camera Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
    }
}
