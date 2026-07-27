package com.example.overlayvideoapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.overlayvideoapp.databinding.ActivityMainBinding
import com.example.overlayvideoapp.viewmodel.CameraViewModel
import kotlinx.coroutines.launch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: CameraViewModel by viewModels()
    private lateinit var auth: FirebaseAuth
    private lateinit var projectionManager: MediaProjectionManager

    private val requestOverlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingOverlayService()
            } else {
                Toast.makeText(this, "Permesso overlay non concesso", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val recordBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Toast.makeText(this, "Registrazione Overlay avviata in background!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permesso di registrazione schermo negato.", Toast.LENGTH_SHORT).show()
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                recordBroadcastReceiver,
                IntentFilter("com.example.overlayvideoapp.REQUEST_RECORD"),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATED")
            registerReceiver(
                recordBroadcastReceiver,
                IntentFilter("com.example.overlayvideoapp.REQUEST_RECORD")
            )
        }

        binding.btnToggleOverlay.setOnClickListener {
            checkAndStartOverlay()
        }

        binding.btnOpenCamera.setOnClickListener {
            try {
                val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Impossibile aprire la fotocamera", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnChangeAccount.setOnClickListener {
            auth.signOut()
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener {
                signInWithGoogle()
            }
        }

        auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            viewModel.startListening(currentUser.uid, "", "index")
        } else {
            signInWithGoogle()
        }

        observeViewModel()
    }

    private fun checkAndStartOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            requestOverlayPermissionLauncher.launch(intent)
        } else {
            startFloatingOverlayService()
        }
    }

    private fun startFloatingOverlayService() {
        val uid = auth.currentUser?.uid ?: ""
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_UID, uid)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Overlay Attivato!", Toast.LENGTH_SHORT).show()
    }

    private fun signInWithGoogle() {
        val clientId = resources.getIdentifier("default_web_client_id", "string", packageName)
        val clientIdStr = if (clientId != 0) getString(clientId) else "dummy_client_id"
        
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
                    viewModel.startListening(it.uid, "", "index")
                }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { isConnected ->
                        if (isConnected) {
                            binding.connectionIndicator.setBackgroundResource(R.drawable.circle_green)
                        } else {
                            binding.connectionIndicator.setBackgroundResource(R.drawable.circle_red)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(recordBroadcastReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
