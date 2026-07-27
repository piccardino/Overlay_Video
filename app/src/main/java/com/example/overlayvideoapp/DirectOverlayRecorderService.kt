package com.example.overlayvideoapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.View
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DirectOverlayRecorderService : Service() {

    private var isRecording = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var surface: Surface? = null

    companion object {
        const val ACTION_START = "ACTION_START_DIRECT_REC"
        const val ACTION_STOP = "ACTION_STOP_DIRECT_REC"
        private const val TAG = "DirectOverlayRecorder"

        var isCurrentlyRecording = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDirectRecording()
            ACTION_STOP -> {
                stopDirectRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDirectRecording() {
        if (isRecording) return

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "DirectRecordChannel")
            .setContentTitle("Overlay Direct Recording")
            .setContentText("Registrazione clip overlay in corso...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(3, notification)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentFile = File(cacheDir, "OverlayClip_$timestamp.mp4")

        val width = 1920
        val height = 1080

        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(currentFile!!.absolutePath)
                setVideoSize(width, height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(8000000)
                setVideoFrameRate(30)
                prepare()
            }

            surface = mediaRecorder!!.surface
            mediaRecorder?.start()
            isRecording = true
            isCurrentlyRecording = true
            notifyState(true)

            handlerThread = HandlerThread("OverlayRenderThread").apply { start() }
            handler = Handler(handlerThread!!.looper)

            handler?.post(object : Runnable {
                override fun run() {
                    if (!isRecording || surface == null || !surface!!.isValid) return

                    val overlayView = OverlayService.currentScoreboardView
                    if (overlayView != null) {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                if (overlayView.width == 0 || overlayView.height == 0) {
                                    overlayView.measure(
                                        View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(75, View.MeasureSpec.EXACTLY)
                                    )
                                    overlayView.layout(0, 0, overlayView.measuredWidth, overlayView.measuredHeight)
                                }

                                val w = Math.max(1, overlayView.measuredWidth)
                                val h = Math.max(1, overlayView.measuredHeight)

                                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val viewCanvas = Canvas(bitmap)
                                overlayView.draw(viewCanvas)

                                // Draw Bitmap onto Surface
                                val localSurface = surface
                                if (localSurface != null && localSurface.isValid) {
                                    val canvas = localSurface.lockCanvas(null)
                                    canvas.drawColor(Color.BLACK)

                                    // Span 90% of the 16:9 video frame width
                                    val targetWidth = width.toFloat() * 0.90f
                                    val scale = targetWidth / w.toFloat()

                                    val cx = (width - (w * scale)) / 2f
                                    val cy = height - (h * scale) - 50f

                                    val saveCount = canvas.save()
                                    canvas.translate(cx, cy)
                                    canvas.scale(scale, scale)
                                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                                    canvas.restoreToCount(saveCount)

                                    localSurface.unlockCanvasAndPost(canvas)
                                }
                                bitmap.recycle()
                            } catch (e: Exception) {
                                Log.e(TAG, "Render bitmap error: ${e.message}")
                            }
                        }
                    }

                    if (isRecording) {
                        handler?.postDelayed(this, 33)
                    }
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder", e)
            isRecording = false
            isCurrentlyRecording = false
            notifyState(false)
        }
    }

    private fun stopDirectRecording() {
        if (!isRecording) return
        isRecording = false
        isCurrentlyRecording = false
        notifyState(false)

        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        }

        currentFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                saveVideoToGallery(file)
            }
        }
    }

    private fun notifyState(active: Boolean) {
        val intent = Intent(OverlayService.ACTION_RECORDING_STATE).apply {
            putExtra(OverlayService.EXTRA_RECORDING_ACTIVE, active)
        }
        sendBroadcast(intent)
    }

    private fun saveVideoToGallery(videoFile: File) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OverlayVideoApp")
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
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "DirectRecordChannel",
                "Direct Overlay Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
