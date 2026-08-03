package com.example.vhpmatchpresentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import coil.load
import com.example.vhpmatchpresentation.data.FirebasePresentationRepository
import com.example.vhpmatchpresentation.data.MatchPresentationData
import com.example.vhpmatchpresentation.data.PlayerPresentation
import com.example.vhpmatchpresentation.data.TeamPresentation
import com.example.vhpmatchpresentation.databinding.ViewPresentationOverlayBinding
import com.example.vhpmatchpresentation.ui.components.RadarChartView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PresentationOverlayService : Service() {

    enum class PresentationState {
        FORMATION_A,
        PLAYERS_A,
        FORMATION_B,
        PLAYERS_B,
        LIVE_SCOREBOARD
    }

    companion object {
        const val ACTION_START = "com.example.vhpmatchpresentation.START_OVERLAY"
        const val ACTION_STOP = "com.example.vhpmatchpresentation.STOP_OVERLAY"
        const val EXTRA_UID = "EXTRA_UID"
        private const val CHANNEL_ID = "VHP_Presentation_Overlay_Channel"
        private const val NOTIF_ID = 2001
        private const val AUTOPLAY_DELAY_MS = 6000L

        var currentOverlayView: View? = null
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var binding: ViewPresentationOverlayBinding? = null

    private var repo: FirebasePresentationRepository? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var currentState = PresentationState.FORMATION_A
    private var currentPlayerIndex = 0
    private var isPlaying = false
    private var isMinimized = false
    private var currentData = MatchPresentationData()

    private val autoplayHandler = Handler(Looper.getMainLooper())
    private val autoplayRunnable = Runnable {
        if (isPlaying) {
            advanceSequence()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = FirebasePresentationRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAndSave()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIF_ID, notification)

        val uid = intent?.getStringExtra(EXTRA_UID) ?: ""
        if (uid.isNotEmpty()) {
            repo?.startObserving(uid)
        }

        if (overlayView == null) {
            setupOverlayWindow()
            observeData()
        } else {
            currentState = PresentationState.FORMATION_A
            currentPlayerIndex = 0
            isPlaying = false
            renderCurrentState()
        }

        return START_STICKY
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val contextThemeWrapper = ContextThemeWrapper(this, R.style.Theme_VHPMatchPresentation)
        val inflater = LayoutInflater.from(contextThemeWrapper)
        binding = ViewPresentationOverlayBinding.inflate(inflater)
        overlayView = binding?.root
        currentOverlayView = binding?.presentationContentContainer

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATED") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 50
        }

        setupDragTouchListener(binding?.dragHeader, params)
        setupDragTouchListener(binding?.minimizedBubble, params)

        binding?.btnPlayPause?.setImageResource(R.drawable.ic_play)

        binding?.btnPlayPause?.setOnClickListener {
            isPlaying = !isPlaying
            if (isPlaying) {
                if (!DirectOverlayRecorderService.isCurrentlyRecording) {
                    val recordIntent = Intent(this, DirectOverlayRecorderService::class.java).apply {
                        action = DirectOverlayRecorderService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(recordIntent)
                    } else {
                        startService(recordIntent)
                    }
                    Toast.makeText(this, "Recording & Presentation Started!", Toast.LENGTH_SHORT).show()
                }
                binding?.btnPlayPause?.setImageResource(R.drawable.ic_pause)
                scheduleAutoplay()
            } else {
                binding?.btnPlayPause?.setImageResource(R.drawable.ic_play)
                stopAutoplay()
            }
        }

        binding?.btnNextPlayer?.setOnClickListener { advanceSequence() }

        binding?.btnShowScoreboard?.setOnClickListener {
            currentState = PresentationState.LIVE_SCOREBOARD
            renderCurrentState()
        }

        binding?.btnMinimize?.setOnClickListener {
            isMinimized = true
            updateMinimizeState()
        }

        binding?.minimizedBubble?.setOnClickListener {
            isMinimized = false
            updateMinimizeState()
        }

        binding?.btnStopSave?.setOnClickListener {
            stopAndSave()
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e("PresentationOverlay", "Error adding view to WindowManager", e)
        }

        renderCurrentState()
    }

    private fun updateMinimizeState() {
        binding?.expandedPanel?.visibility = if (isMinimized) View.GONE else View.VISIBLE
        binding?.minimizedBubble?.visibility = if (isMinimized) View.VISIBLE else View.GONE
    }

    private fun stopAndSave() {
        val recordIntent = Intent(this, DirectOverlayRecorderService::class.java).apply {
            action = DirectOverlayRecorderService.ACTION_STOP
        }
        startService(recordIntent)

        Toast.makeText(this, "Presentation Stopped! Video saved to Gallery.", Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun setupDragTouchListener(view: View?, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATED")
        windowManager?.defaultDisplay?.getMetrics(metrics)

        view?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx > 8 || dy > 8) {
                        isClick = false
                        val newX = (initialX + (event.rawX - initialTouchX)).toInt()
                        val newY = (initialY + (event.rawY - initialTouchY)).toInt()
                        params.x = newX.coerceIn(0, Math.max(0, metrics.widthPixels - 100))
                        params.y = newY.coerceIn(0, Math.max(0, metrics.heightPixels - 100))
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderCurrentState()
    }

    private fun observeData() {
        scope.launch {
            repo?.presentationData?.collectLatest { data ->
                currentData = data
                renderCurrentState()
            }
        }
    }

    private fun renderCurrentState() {
        val container = binding?.presentationFrame ?: return
        container.removeAllViews()

        val isScoreboard = currentState == PresentationState.LIVE_SCOREBOARD
        binding?.liveScoreboardLayout?.visibility = if (isScoreboard) View.VISIBLE else View.GONE

        if (isScoreboard) {
            stopAutoplay()
            updateScoreboardView()
            return
        }

        when (currentState) {
            PresentationState.FORMATION_A -> {
                val view = createFormationView(currentData.teamA)
                container.addView(view)
                if (isPlaying) scheduleAutoplay()
            }
            PresentationState.PLAYERS_A -> {
                val players = currentData.teamA.players
                if (players.isNotEmpty()) {
                    if (currentPlayerIndex >= players.size) currentPlayerIndex = 0
                    val player = players[currentPlayerIndex]
                    val view = createPlayerCardView(player, currentData.teamA)
                    container.addView(view)
                    if (isPlaying) scheduleAutoplay()
                } else {
                    currentState = PresentationState.FORMATION_B
                    renderCurrentState()
                }
            }
            PresentationState.FORMATION_B -> {
                val view = createFormationView(currentData.teamB)
                container.addView(view)
                if (isPlaying) scheduleAutoplay()
            }
            PresentationState.PLAYERS_B -> {
                val players = currentData.teamB.players
                if (players.isNotEmpty()) {
                    if (currentPlayerIndex >= players.size) currentPlayerIndex = 0
                    val player = players[currentPlayerIndex]
                    val view = createPlayerCardView(player, currentData.teamB)
                    container.addView(view)
                    if (isPlaying) scheduleAutoplay()
                } else {
                    currentState = PresentationState.LIVE_SCOREBOARD
                    renderCurrentState()
                }
            }
            PresentationState.LIVE_SCOREBOARD -> {}
        }
    }

    private fun updateScoreboardView() {
        val b = binding ?: return
        b.nameTeamA.text = currentData.teamA.name.uppercase()
        b.nameTeamB.text = currentData.teamB.name.uppercase()
        b.scoreTeamA.text = currentData.scoreA.toString()
        b.scoreTeamB.text = currentData.scoreB.toString()
        b.setsTeamA.text = "S:${currentData.setsA}"
        b.setsTeamB.text = "S:${currentData.setsB}"

        b.serveTeamA.visibility = if (currentData.servingTeam.equals("A", ignoreCase = true)) View.VISIBLE else View.INVISIBLE
        b.serveTeamB.visibility = if (currentData.servingTeam.equals("B", ignoreCase = true)) View.VISIBLE else View.INVISIBLE

        loadLogo(b.logoTeamA, currentData.teamA.logoUrl)
        loadLogo(b.logoTeamB, currentData.teamB.logoUrl)
    }

    private fun loadLogo(imageView: ImageView, logoStr: String) {
        if (logoStr.isEmpty()) {
            imageView.setImageResource(R.drawable.logo2)
            return
        }

        if (logoStr.startsWith("data:image") || (!logoStr.startsWith("http") && logoStr.length > 100)) {
            try {
                val cleanBase64 = if (logoStr.contains(",")) logoStr.substringAfter(",") else logoStr
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    return
                }
            } catch (e: Exception) {
                Log.e("PresentationOverlay", "Error decoding base64 logo", e)
            }
        }

        imageView.load(logoStr) {
            placeholder(R.drawable.logo2)
            error(R.drawable.logo2)
        }
    }

    private fun loadPlayerPhoto(imageView: ImageView, photoUri: String?) {
        if (photoUri.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_player_silhouette)
            return
        }

        if (photoUri.startsWith("data:image") || (!photoUri.startsWith("http") && !photoUri.startsWith("content") && !photoUri.startsWith("file") && photoUri.length > 100)) {
            try {
                val cleanBase64 = if (photoUri.contains(",")) photoUri.substringAfter(",") else photoUri
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    return
                }
            } catch (e: Exception) {
                Log.e("PresentationOverlay", "Error decoding base64 player photo", e)
            }
        }

        imageView.load(photoUri) {
            placeholder(R.drawable.ic_player_silhouette)
            error(R.drawable.ic_player_silhouette)
        }
    }

    private fun createFormationView(team: TeamPresentation): View {
        val contextThemeWrapper = ContextThemeWrapper(this, R.style.Theme_VHPMatchPresentation)
        val inflater = LayoutInflater.from(contextThemeWrapper)
        val formationView = inflater.inflate(R.layout.vnl_team_formation, null)

        val txtTeamName = formationView.findViewById<TextView>(R.id.txtTeamName)
        val imgTeamLogo = formationView.findViewById<ImageView>(R.id.imgTeamLogo)
        val accentBar = formationView.findViewById<View>(R.id.teamColorAccentBar)
        val listContainer = formationView.findViewById<LinearLayout>(R.id.playersListContainer)

        txtTeamName.text = "${team.name.uppercase()} LINEUP"
        try {
            accentBar.setBackgroundColor(Color.parseColor(team.primaryColorHex))
        } catch (e: Exception) {
            accentBar.setBackgroundColor(Color.parseColor("#0284C7"))
        }

        loadLogo(imgTeamLogo, team.logoUrl)

        for (player in team.players.take(12)) {
            val tv = TextView(this).apply {
                text = "#${player.number}  ${player.name} (${player.role})"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 4, 0, 4)
            }
            listContainer.addView(tv)
        }

        return formationView
    }

    private fun createPlayerCardView(player: PlayerPresentation, team: TeamPresentation): View {
        val contextThemeWrapper = ContextThemeWrapper(this, R.style.Theme_VHPMatchPresentation)
        val inflater = LayoutInflater.from(contextThemeWrapper)
        val cardView = inflater.inflate(R.layout.vnl_player_card, null)

        val imgPhoto = cardView.findViewById<ImageView>(R.id.imgPlayerPhoto)
        val teamBar = cardView.findViewById<View>(R.id.playerTeamBar)
        val txtNumber = cardView.findViewById<TextView>(R.id.txtPlayerNumber)
        val txtTeam = cardView.findViewById<TextView>(R.id.txtTeamShortName)
        val txtName = cardView.findViewById<TextView>(R.id.txtPlayerName)
        val txtRole = cardView.findViewById<TextView>(R.id.txtPlayerRole)
        val radarView = cardView.findViewById<RadarChartView>(R.id.radarChartView)

        txtNumber.text = "#${player.number}"
        txtTeam.text = team.name.uppercase()
        txtName.text = player.name.uppercase()
        txtRole.text = player.role.uppercase()

        try {
            teamBar.setBackgroundColor(Color.parseColor(team.primaryColorHex))
        } catch (e: Exception) {
            teamBar.setBackgroundColor(Color.parseColor("#0284C7"))
        }

        loadPlayerPhoto(imgPhoto, player.photoUri)

        radarView.setStats(player.stats, team.primaryColorHex, animate = true)

        return cardView
    }

    private fun advanceSequence() {
        when (currentState) {
            PresentationState.FORMATION_A -> {
                currentState = PresentationState.PLAYERS_A
                currentPlayerIndex = 0
            }
            PresentationState.PLAYERS_A -> {
                if (currentPlayerIndex + 1 < currentData.teamA.players.size) {
                    currentPlayerIndex++
                } else {
                    currentState = PresentationState.FORMATION_B
                    currentPlayerIndex = 0
                }
            }
            PresentationState.FORMATION_B -> {
                currentState = PresentationState.PLAYERS_B
                currentPlayerIndex = 0
            }
            PresentationState.PLAYERS_B -> {
                if (currentPlayerIndex + 1 < currentData.teamB.players.size) {
                    currentPlayerIndex++
                } else {
                    currentState = PresentationState.LIVE_SCOREBOARD
                }
            }
            PresentationState.LIVE_SCOREBOARD -> {
                stopAutoplay()
            }
        }
        renderCurrentState()
    }

    private fun scheduleAutoplay() {
        stopAutoplay()
        if (isPlaying) {
            autoplayHandler.postDelayed(autoplayRunnable, AUTOPLAY_DELAY_MS)
        }
    }

    private fun stopAutoplay() {
        autoplayHandler.removeCallbacks(autoplayRunnable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VHP Presentation Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VHP Match Presentation Overlay")
            .setContentText("Match presentation & live score board active")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoplay()
        repo?.stopObserving()
        currentOverlayView = null
        if (overlayView != null) {
            try {
                windowManager?.removeViewImmediate(overlayView)
            } catch (e: Exception) {
                Log.e("PresentationOverlay", "Error removing overlay view", e)
            }
            overlayView = null
            binding = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
