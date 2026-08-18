package com.example.vhpmatchpresentation.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.graphics.Typeface
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.vhpmatchpresentation.DirectOverlayRecorderService
import com.example.vhpmatchpresentation.PresentationOverlayService
import com.example.vhpmatchpresentation.R
import com.example.vhpmatchpresentation.data.AppUpdateManager
import com.example.vhpmatchpresentation.data.FirebasePresentationRepository
import com.example.vhpmatchpresentation.data.MatchPresentationData
import com.example.vhpmatchpresentation.data.PhotoMatchingManager
import com.example.vhpmatchpresentation.data.PlayerPresentation
import com.example.vhpmatchpresentation.databinding.ActivityMatchPreparationBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MatchPreparationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMatchPreparationBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var repo: FirebasePresentationRepository
    private lateinit var photoManager: PhotoMatchingManager
    private lateinit var updateManager: AppUpdateManager

    private var currentMatchData = MatchPresentationData()
    private var selectedPlayerForSinglePhoto: PlayerPresentation? = null
    private var selectedPhotoVariant: String = ""

    private val requestOverlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startPresentationOverlay()
        } else {
            Toast.makeText(this, "Overlay permission not granted", Toast.LENGTH_SHORT).show()
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null && !account.idToken.isNullOrEmpty()) {
                firebaseAuthWithGoogle(account.idToken!!)
            } else {
                Toast.makeText(this, "Google Sign-In failed: Null ID Token", Toast.LENGTH_LONG).show()
            }
        } catch (e: ApiException) {
            Log.e("MatchPrepAuth", "Google Sign-In ApiException status code: ${e.statusCode}", e)
            Toast.makeText(this, "Google Sign-In error (${e.statusCode}): ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val pickSinglePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val player = selectedPlayerForSinglePhoto
        val variant = selectedPhotoVariant
        if (uri != null && player != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val base64Photo = photoManager.convertUriToBase64(uri.toString())
            photoManager.savePhotoForPlayer(player.name, base64Photo, variant)
            if (player.displayName.isNotBlank()) {
                photoManager.savePhotoForPlayer(player.displayName, base64Photo, variant)
            }
            if (player.number.isNotBlank()) {
                photoManager.savePhotoForNumber(player.number, base64Photo, variant)
            }
            val uid = auth.currentUser?.uid
            if (!uid.isNullOrEmpty()) {
                repo.savePhotoVariantToFirebase(uid, player.name, base64Photo, variant)
            }
            repo.refreshPhotos()
            val variantLabel = if (variant == "red") " (Red Kit)" else if (variant == "blue") " (Blue Kit)" else ""
            Toast.makeText(this, "Photo updated for ${player.name}$variantLabel", Toast.LENGTH_SHORT).show()
        }
        selectedPhotoVariant = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchPreparationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        repo = FirebasePresentationRepository(applicationContext)
        photoManager = PhotoMatchingManager(applicationContext)
        updateManager = AppUpdateManager(applicationContext)

        setupListeners()

        val currentUser = auth.currentUser
        if (currentUser != null) {
            repo.startObserving(currentUser.uid)
            observeRepositoryData()
            updateManager.checkForUpdates(this, currentUser.uid)
            updateManager.startRealtimeListener(this, currentUser.uid)
        } else {
            signInWithGoogle()
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButtonState()
    }

    private fun setupListeners() {
        binding.btnChangeAccount.setOnClickListener {
            auth.signOut()
            val webClientId = "614392919865-ofota1pn71noh36cu18t24o97okbqf9p.apps.googleusercontent.com"
            val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
            val clientIdStr = if (resId != 0) getString(resId) else webClientId

            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(clientIdStr)
                .requestEmail()
                .build()

            GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener {
                signInWithGoogle()
            }
        }

        binding.btnManualPhotoWizard.setOnClickListener {
            showActiveRosterPhotosDialog()
        }

        binding.btnStartPresentationOverlay.setOnClickListener {
            toggleOverlayService()
        }

        binding.btnCheckUpdates.setOnClickListener {
            updateManager.checkForUpdates(this, auth.currentUser?.uid ?: "", showNoUpdateToast = true)
        }
    }

    private fun observeRepositoryData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repo.connectionState.collectLatest { connected ->
                        binding.connectionIndicator.setBackgroundResource(
                            if (connected) R.drawable.circle_green else R.drawable.circle_red
                        )
                    }
                }
                launch {
                    repo.presentationData.collectLatest { data ->
                        currentMatchData = data
                        updateUI(data)
                    }
                }
            }
        }
    }

    private fun updateUI(data: MatchPresentationData) {
        binding.txtPrepTeamA.text = data.teamA.name
        binding.txtPrepTeamB.text = data.teamB.name

        try {
            binding.colorBarA.setBackgroundColor(Color.parseColor(data.teamA.primaryColorHex))
            binding.colorBarB.setBackgroundColor(Color.parseColor(data.teamB.primaryColorHex))
        } catch (e: Exception) {
            binding.colorBarA.setBackgroundColor(Color.parseColor("#0284C7"))
            binding.colorBarB.setBackgroundColor(Color.parseColor("#E11D48"))
        }

        if (data.teamA.logoUrl.isNotEmpty()) {
            binding.imgPrepLogoA.load(data.teamA.logoUrl) {
                placeholder(R.drawable.ic_player_silhouette)
                error(R.drawable.ic_player_silhouette)
            }
        }
        if (data.teamB.logoUrl.isNotEmpty()) {
            binding.imgPrepLogoB.load(data.teamB.logoUrl) {
                placeholder(R.drawable.ic_player_silhouette)
                error(R.drawable.ic_player_silhouette)
            }
        }

        val countA = data.teamA.players.size
        val photosA = data.teamA.players.count { !it.photoUri.isNullOrEmpty() }
        binding.txtPrepStatusA.text = "Players: $countA | Photos mapped: $photosA/$countA"

        val countB = data.teamB.players.size
        val photosB = data.teamB.players.count { !it.photoUri.isNullOrEmpty() }
        binding.txtPrepStatusB.text = "Players: $countB | Photos mapped: $photosB/$countB"
    }

    private fun showActiveRosterPhotosDialog() {
        val teamA = currentMatchData.teamA
        val teamB = currentMatchData.teamB

        if (teamA.players.isEmpty() && teamB.players.isEmpty()) {
            Toast.makeText(this, "No active players in current match roster.", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val inflater = LayoutInflater.from(this)

        // Section Team A
        val colorTypeA = photoManager.determineColorType(teamA.primaryColorHex)
        val isRedA = colorTypeA == "red"
        val headerA = TextView(this).apply {
            text = if (isRedA) "🔴 ${teamA.name.uppercase()} (${teamA.players.size} GIOCATORI - ROSSA)"
            else "🔵 ${teamA.name.uppercase()} (${teamA.players.size} GIOCATORI - BLU)"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isRedA) "#EF4444" else "#38BDF8"))
            setPadding(0, 12, 0, 12)
        }
        container.addView(headerA)

        for (player in teamA.players) {
            val itemView = inflater.inflate(R.layout.item_active_roster_player_photo, container, false)
            val imgPhoto = itemView.findViewById<ImageView>(R.id.imgItemPhoto)
            val txtName = itemView.findViewById<TextView>(R.id.txtItemPlayerName)
            val txtRole = itemView.findViewById<TextView>(R.id.txtItemPlayerRole)
            val btnRed = itemView.findViewById<Button>(R.id.btnItemRedPhoto)
            val btnBlue = itemView.findViewById<Button>(R.id.btnItemBluePhoto)

            txtName.text = "#${player.number} ${player.name}"
            val statsSummary = "ATK:${player.stats.attack} BLK:${player.stats.block} SRV:${player.stats.serve}"
            txtRole.text = "${player.role}  |  $statsSummary"

            if (!player.photoUri.isNullOrEmpty()) {
                imgPhoto.load(player.photoUri) {
                    placeholder(R.drawable.ic_player_silhouette)
                    error(R.drawable.ic_player_silhouette)
                }
            } else {
                imgPhoto.setImageResource(R.drawable.ic_player_silhouette)
            }

            btnRed.setOnClickListener {
                selectedPlayerForSinglePhoto = player
                selectedPhotoVariant = "red"
                pickSinglePhotoLauncher.launch(arrayOf("image/*"))
            }
            btnBlue.setOnClickListener {
                selectedPlayerForSinglePhoto = player
                selectedPhotoVariant = "blue"
                pickSinglePhotoLauncher.launch(arrayOf("image/*"))
            }
            container.addView(itemView)
        }

        // Section Team B
        val colorTypeB = photoManager.determineColorType(teamB.primaryColorHex)
        val isRedB = colorTypeB == "red"
        val headerB = TextView(this).apply {
            text = if (isRedB) "🔴 ${teamB.name.uppercase()} (${teamB.players.size} GIOCATORI - ROSSA)"
            else "🔵 ${teamB.name.uppercase()} (${teamB.players.size} GIOCATORI - BLU)"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isRedB) "#EF4444" else "#38BDF8"))
            setPadding(0, 24, 0, 12)
        }
        container.addView(headerB)

        for (player in teamB.players) {
            val itemView = inflater.inflate(R.layout.item_active_roster_player_photo, container, false)
            val imgPhoto = itemView.findViewById<ImageView>(R.id.imgItemPhoto)
            val txtName = itemView.findViewById<TextView>(R.id.txtItemPlayerName)
            val txtRole = itemView.findViewById<TextView>(R.id.txtItemPlayerRole)
            val btnRed = itemView.findViewById<Button>(R.id.btnItemRedPhoto)
            val btnBlue = itemView.findViewById<Button>(R.id.btnItemBluePhoto)

            txtName.text = "#${player.number} ${player.name}"
            val statsSummary = "ATK:${player.stats.attack} BLK:${player.stats.block} SRV:${player.stats.serve}"
            txtRole.text = "${player.role}  |  $statsSummary"

            if (!player.photoUri.isNullOrEmpty()) {
                imgPhoto.load(player.photoUri) {
                    placeholder(R.drawable.ic_player_silhouette)
                    error(R.drawable.ic_player_silhouette)
                }
            } else {
                imgPhoto.setImageResource(R.drawable.ic_player_silhouette)
            }

            btnRed.setOnClickListener {
                selectedPlayerForSinglePhoto = player
                selectedPhotoVariant = "red"
                pickSinglePhotoLauncher.launch(arrayOf("image/*"))
            }
            btnBlue.setOnClickListener {
                selectedPlayerForSinglePhoto = player
                selectedPhotoVariant = "blue"
                pickSinglePhotoLauncher.launch(arrayOf("image/*"))
            }
            container.addView(itemView)
        }

        val scrollView = ScrollView(this).apply {
            addView(container)
        }

        AlertDialog.Builder(this)
            .setTitle("Active Match Roster (${teamA.name} vs ${teamB.name})")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun toggleOverlayService() {
        if (isOverlayServiceRunning()) {
            stopPresentationOverlay()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                requestOverlayPermissionLauncher.launch(intent)
            } else {
                startPresentationOverlay()
            }
        }
    }

    private fun startPresentationOverlay() {
        val uid = auth.currentUser?.uid ?: ""

        val serviceIntent = Intent(this, PresentationOverlayService::class.java).apply {
            action = PresentationOverlayService.ACTION_START
            putExtra(PresentationOverlayService.EXTRA_UID, uid)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "Overlay Started! Press Play (▶) on Overlay to Start Presentation & Recording", Toast.LENGTH_LONG).show()
        updateOverlayButtonState(isRunning = true)
    }

    private fun stopPresentationOverlay() {
        val directRecIntent = Intent(this, DirectOverlayRecorderService::class.java).apply {
            action = DirectOverlayRecorderService.ACTION_STOP
        }
        startService(directRecIntent)

        val serviceIntent = Intent(this, PresentationOverlayService::class.java).apply {
            action = PresentationOverlayService.ACTION_STOP
        }
        startService(serviceIntent)

        Toast.makeText(this, "Presentation Overlay Stopped!", Toast.LENGTH_SHORT).show()
        updateOverlayButtonState(isRunning = false)
    }

    @Suppress("DEPRECATED")
    private fun isOverlayServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (PresentationOverlayService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun updateOverlayButtonState(isRunning: Boolean = isOverlayServiceRunning()) {
        if (isRunning) {
            binding.btnStartPresentationOverlay.text = "STOP OVERLAY PRESENTATION"
            binding.btnStartPresentationOverlay.setBackgroundColor(Color.parseColor("#EF4444"))
        } else {
            binding.btnStartPresentationOverlay.text = "START OVERLAY PRESENTATION"
            binding.btnStartPresentationOverlay.setBackgroundColor(Color.parseColor("#0284C7"))
        }
    }

    private fun signInWithGoogle() {
        val webClientId = "614392919865-ofota1pn71noh36cu18t24o97okbqf9p.apps.googleusercontent.com"
        val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
        val clientIdStr = if (resId != 0) getString(resId) else webClientId

        Log.i("MatchPrepAuth", "Signing in with Web Client ID: $clientIdStr")

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(clientIdStr)
            .requestEmail()
            .build()

        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                val user = auth.currentUser
                user?.let {
                    Log.i("MatchPrepAuth", "Firebase auth successful for uid: ${it.uid}")
                    repo.startObserving(it.uid)
                    observeRepositoryData()
                    updateManager.checkForUpdates(this, it.uid)
                }
            } else {
                Log.e("MatchPrepAuth", "Firebase auth with Google failed", task.exception)
                Toast.makeText(this, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateManager.stopRealtimeListener()
        repo.stopObserving()
    }
}
