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

    private var currentMatchData = MatchPresentationData()
    private var selectedPlayerForSinglePhoto: PlayerPresentation? = null
    private var activeMappingAdapter: PhotoMappingAdapter? = null

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

    private val pickMultiplePhotosLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val allPlayers = currentMatchData.teamA.players + currentMatchData.teamB.players
            val matches = photoManager.autoMatchPhotos(uris, allPlayers)
            Toast.makeText(this, "${matches.size} photos automatically matched!", Toast.LENGTH_LONG).show()
            repo.refreshPhotos()
        }
    }

    private val pickSinglePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val player = selectedPlayerForSinglePhoto
        if (uri != null && player != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            photoManager.savePhotoForPlayer(player.name, uri.toString())
            if (player.displayName.isNotBlank()) {
                photoManager.savePhotoForPlayer(player.displayName, uri.toString())
            }
            repo.refreshPhotos()
            Toast.makeText(this, "Photo updated for ${player.name}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchPreparationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        repo = FirebasePresentationRepository(applicationContext)
        photoManager = PhotoMatchingManager(applicationContext)

        setupListeners()

        val currentUser = auth.currentUser
        if (currentUser != null) {
            repo.startObserving(currentUser.uid)
            observeRepositoryData()
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

        binding.btnImportPhotos.setOnClickListener {
            showVerifyMappingsDialog()
        }

        binding.btnStartPresentationOverlay.setOnClickListener {
            toggleOverlayService()
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

        val allPlayers = data.teamA.players + data.teamB.players
        activeMappingAdapter?.updatePlayers(allPlayers)
    }

    private fun showVerifyMappingsDialog() {
        val allPlayers = currentMatchData.teamA.players + currentMatchData.teamB.players
        if (allPlayers.isEmpty()) {
            Toast.makeText(this, "No players available in roster.", Toast.LENGTH_SHORT).show()
            return
        }

        activeMappingAdapter = PhotoMappingAdapter(
            players = allPlayers,
            onSelectPhotoClicked = { player ->
                selectedPlayerForSinglePhoto = player
                pickSinglePhotoLauncher.launch(arrayOf("image/*"))
            },
            onRemovePhotoClicked = { player ->
                photoManager.removePhotoForPlayer(player.name)
                if (player.displayName.isNotBlank()) {
                    photoManager.removePhotoForPlayer(player.displayName)
                }
                repo.refreshPhotos()
                Toast.makeText(this@MatchPreparationActivity, "Photo removed for ${player.name}", Toast.LENGTH_SHORT).show()
            }
        )

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MatchPreparationActivity)
            adapter = activeMappingAdapter
        }

        AlertDialog.Builder(this)
            .setTitle("Verify Player Photos")
            .setView(recyclerView)
            .setPositiveButton("Close") { dialog, _ ->
                activeMappingAdapter = null
            }
            .setOnDismissListener {
                activeMappingAdapter = null
            }
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
                }
            } else {
                Log.e("MatchPrepAuth", "Firebase auth with Google failed", task.exception)
                Toast.makeText(this, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repo.stopObserving()
    }
}
