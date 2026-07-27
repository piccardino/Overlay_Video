package com.example.overlayvideoapp

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import coil.load
import com.example.overlayvideoapp.data.FirebaseMatchRepository
import com.example.overlayvideoapp.data.MatchData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val repository = FirebaseMatchRepository()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var lastScoreA = -1
    private var lastScoreB = -1
    private var isRecording = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RECORDING_STATE) {
                val state = intent.getBooleanExtra(EXTRA_RECORDING_ACTIVE, false)
                updateRecordingUI(state)
            }
        }
    }

    companion object {
        const val ACTION_START = "ACTION_START_OVERLAY"
        const val ACTION_STOP = "ACTION_STOP_OVERLAY"
        const val ACTION_RECORDING_STATE = "com.example.overlayvideoapp.RECORDING_STATE"
        const val EXTRA_RECORDING_ACTIVE = "EXTRA_RECORDING_ACTIVE"
        const val EXTRA_UID = "EXTRA_UID"

        var isServiceRunning = false
        var isCurrentlyRecording = false
        var currentScoreboardView: View? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlayView()
        startForegroundNotification()
        observeFirebase()

        val filter = IntentFilter(ACTION_RECORDING_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATED")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uid = intent.getStringExtra(EXTRA_UID) ?: ""
                if (uid.isNotEmpty()) {
                    repository.startObserving(uid, "", "index")
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "OverlayServiceChannel",
                "Overlay Window Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, "OverlayServiceChannel")
            .setContentTitle("Overlay Video Attivo")
            .setContentText("L'overlay fluttuante è in esecuzione in background.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(2, notification)
    }

    private fun setupOverlayView() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.view_floating_overlay, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        windowManager.addView(overlayView, layoutParams)

        // Setup Drag & Touch
        val dragHandle = overlayView.findViewById<View>(R.id.drag_handle)
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayView, layoutParams)
                        return true
                    }
                }
                return false
            }
        })

        // Setup Overlay Control Buttons
        val btnRecord = overlayView.findViewById<Button>(R.id.btn_overlay_record)
        val btnClose = overlayView.findViewById<Button>(R.id.btn_overlay_close)
        currentScoreboardView = overlayView.findViewById(R.id.scoreboard_container)

        btnRecord.setOnClickListener {
            if (DirectOverlayRecorderService.isCurrentlyRecording) {
                // Stop direct recording service
                val stopIntent = Intent(this, DirectOverlayRecorderService::class.java).apply {
                    action = DirectOverlayRecorderService.ACTION_STOP
                }
                startService(stopIntent)
                updateRecordingUI(false)
                Toast.makeText(this, "Clip overlay salvata in Galleria!", Toast.LENGTH_SHORT).show()
            } else {
                // Start direct recording service
                val startIntent = Intent(this, DirectOverlayRecorderService::class.java).apply {
                    action = DirectOverlayRecorderService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
                updateRecordingUI(true)
                Toast.makeText(this, "Registrazione Overlay avviata!", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener {
            if (DirectOverlayRecorderService.isCurrentlyRecording) {
                val stopIntent = Intent(this, DirectOverlayRecorderService::class.java).apply {
                    action = DirectOverlayRecorderService.ACTION_STOP
                }
                startService(stopIntent)
            }
            stopSelf()
        }

        updateRecordingUI(isCurrentlyRecording)
    }

    private fun updateRecordingUI(recording: Boolean) {
        isRecording = recording
        isCurrentlyRecording = recording
        val btnRecord = overlayView.findViewById<Button>(R.id.btn_overlay_record)
        if (recording) {
            btnRecord.text = "STOP CLIP"
            btnRecord.setBackgroundColor(android.graphics.Color.parseColor("#CC0000"))
        } else {
            btnRecord.text = "REC CLIP"
            btnRecord.setBackgroundColor(android.graphics.Color.parseColor("#D9534F"))
        }
    }

    private fun observeFirebase() {
        serviceScope.launch {
            repository.matchData.collect { data ->
                data?.let { updateUI(it) }
            }
        }

        serviceScope.launch {
            repository.connectionState.collect { isConnected ->
                val indicator = overlayView.findViewById<View>(R.id.connection_indicator)
                if (isConnected) {
                    indicator.setBackgroundResource(R.drawable.circle_green)
                } else {
                    indicator.setBackgroundResource(R.drawable.circle_red)
                }
            }
        }
    }

    private fun updateUI(it: MatchData) {
        val serveTeamA = overlayView.findViewById<TextView>(R.id.serve_team_a)
        val serveTeamB = overlayView.findViewById<TextView>(R.id.serve_team_b)

        if (lastScoreA != -1 && lastScoreB != -1) {
            if (it.score_team_a > lastScoreA) {
                serveTeamA.visibility = View.VISIBLE
                serveTeamB.visibility = View.INVISIBLE
            } else if (it.score_team_b > lastScoreB) {
                serveTeamA.visibility = View.INVISIBLE
                serveTeamB.visibility = View.VISIBLE
            }
        }
        lastScoreA = it.score_team_a
        lastScoreB = it.score_team_b

        overlayView.findViewById<TextView>(R.id.score_team_a).text = it.score_team_a.toString()
        overlayView.findViewById<TextView>(R.id.score_team_b).text = it.score_team_b.toString()
        overlayView.findViewById<TextView>(R.id.name_team_a).text = it.team_name_a
        overlayView.findViewById<TextView>(R.id.name_team_b).text = it.team_name_b
        overlayView.findViewById<TextView>(R.id.sets_team_a).text = it.sets_won_a.toString()
        overlayView.findViewById<TextView>(R.id.sets_team_b).text = it.sets_won_b.toString()

        loadLogo(overlayView.findViewById(R.id.logo_team_a), it.logo_url_a)
        loadLogo(overlayView.findViewById(R.id.logo_team_b), it.logo_url_b)
    }

    private fun loadLogo(imageView: ImageView, urlOrBase64: String) {
        if (urlOrBase64.isEmpty()) {
            imageView.setImageDrawable(null)
            return
        }
        if (urlOrBase64.startsWith("data:image")) {
            try {
                val base64Image = urlOrBase64.substringAfter(",")
                val decodedString = Base64.decode(base64Image, Base64.DEFAULT)
                val decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                imageView.setImageBitmap(decodedByte)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            imageView.load(urlOrBase64) { crossfade(true) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        try {
            unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        repository.stopObserving()
        if (::overlayView.isInitialized && ::windowManager.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
