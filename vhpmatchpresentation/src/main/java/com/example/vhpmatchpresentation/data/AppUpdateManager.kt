package com.example.vhpmatchpresentation.data

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.vhpmatchpresentation.BuildConfig
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val releaseNotes: String = "",
    val forceUpdate: Boolean = false,
    val source: String = "Firebase"
)

class AppUpdateManager(private val context: Context) {

    private val database = FirebaseDatabase.getInstance("https://volley-hub-c90ca-default-rtdb.europe-west1.firebasedatabase.app")

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val GITHUB_REPO = "piccardino/Overlay_Video"
        var pendingApkFile: File? = null

        fun isVersionNewer(remoteVer: String, currentVer: String): Boolean {
            val cleanRemote = remoteVer.trim().removePrefix("v").removePrefix("V")
            val cleanCurrent = currentVer.trim().removePrefix("v").removePrefix("V")
            if (cleanRemote.isBlank() || cleanCurrent.isBlank()) return false
            if (cleanRemote == cleanCurrent) return false

            val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }

    fun checkForUpdates(
        activity: AppCompatActivity,
        uid: String = "",
        showNoUpdateToast: Boolean = false
    ) {
        // 1. Try checking GitHub Releases first (fast, public, automated)
        CoroutineScope(Dispatchers.IO).launch {
            val gitHubUpdate = checkGitHubReleases()
            withContext(Dispatchers.Main) {
                if (gitHubUpdate != null) {
                    showUpdateDialog(activity, gitHubUpdate)
                } else {
                    // 2. Fall back to Firebase Realtime Database
                    checkFirebaseUpdates(activity, uid, showNoUpdateToast)
                }
            }
        }
    }

    private fun checkGitHubReleases(): AppUpdateInfo? {
        return try {
            val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "VHPMatchPresentation-App")
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(jsonStr)
                val tagName = jsonObj.optString("tag_name", "").trim()
                val body = jsonObj.optString("body", "Nuovo aggiornamento disponibile da GitHub Releases.")
                val assets = jsonObj.optJSONArray("assets")

                var apkDownloadUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                if (apkDownloadUrl.isNotBlank()) {
                    val isNewer = isVersionNewer(tagName, BuildConfig.VERSION_NAME)
                    if (isNewer) {
                        return AppUpdateInfo(
                            versionCode = 0,
                            versionName = tagName,
                            apkUrl = apkDownloadUrl,
                            releaseNotes = body,
                            forceUpdate = false,
                            source = "GitHub Releases"
                        )
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "GitHub release check skipped/failed: ${e.message}")
            null
        }
    }

    private fun checkFirebaseUpdates(
        activity: AppCompatActivity,
        uid: String,
        showNoUpdateToast: Boolean
    ) {
        val rootRef = database.reference
        val checkPath = if (uid.isNotBlank()) "users/$uid/app_update" else "app_update"

        rootRef.child(checkPath).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    handleFirebaseSnapshot(activity, snapshot, showNoUpdateToast)
                } else if (checkPath != "app_update") {
                    rootRef.child("app_update").addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(globalSnap: DataSnapshot) {
                            if (globalSnap.exists()) {
                                handleFirebaseSnapshot(activity, globalSnap, showNoUpdateToast)
                            } else if (showNoUpdateToast) {
                                Toast.makeText(activity, "Nessun aggiornamento disponibile (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {
                            if (showNoUpdateToast) Toast.makeText(activity, "Errore verifica: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    })
                } else if (showNoUpdateToast) {
                    Toast.makeText(activity, "Nessun aggiornamento disponibile (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error checking Firebase for updates: ${error.message}")
                if (showNoUpdateToast) {
                    Toast.makeText(activity, "Errore verifica aggiornamenti: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun handleFirebaseSnapshot(activity: AppCompatActivity, snapshot: DataSnapshot, showNoUpdateToast: Boolean) {
        val remoteVersionCode = snapshot.child("versionCode").getValue(Int::class.java)
            ?: snapshot.child("code").getValue(Int::class.java)
            ?: snapshot.child("version").getValue(Int::class.java)
            ?: 0

        val remoteVersionName = snapshot.child("versionName").getValue(String::class.java)
            ?: snapshot.child("name").getValue(String::class.java)
            ?: ""

        val apkUrl = snapshot.child("apkUrl").getValue(String::class.java)
            ?: snapshot.child("url").getValue(String::class.java)
            ?: snapshot.child("downloadUrl").getValue(String::class.java)
            ?: ""

        val releaseNotes = snapshot.child("releaseNotes").getValue(String::class.java)
            ?: snapshot.child("changelog").getValue(String::class.java)
            ?: snapshot.child("notes").getValue(String::class.java)
            ?: "Miglioramenti generali, fix indicatore battuta e foto squadra."

        val forceUpdate = snapshot.child("forceUpdate").getValue(Boolean::class.java) ?: false

        val currentCode = BuildConfig.VERSION_CODE
        val isNewerCode = remoteVersionCode > currentCode
        val isNewerName = isVersionNewer(remoteVersionName, BuildConfig.VERSION_NAME)

        if ((isNewerCode || isNewerName) && apkUrl.isNotBlank()) {
            val updateInfo = AppUpdateInfo(
                versionCode = remoteVersionCode,
                versionName = remoteVersionName.ifBlank { "v$remoteVersionCode" },
                apkUrl = apkUrl,
                releaseNotes = releaseNotes,
                forceUpdate = forceUpdate,
                source = "Firebase"
            )
            showUpdateDialog(activity, updateInfo)
        } else if (showNoUpdateToast) {
            Toast.makeText(activity, "L'app è già aggiornata all'ultima versione (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
        }
    }

    fun showUpdateDialog(activity: AppCompatActivity, updateInfo: AppUpdateInfo) {
        val builder = AlertDialog.Builder(activity)
            .setTitle("🚀 Aggiornamento Disponibile (${updateInfo.versionName})")
            .setMessage(
                "È disponibile una nuova versione dell'applicazione (${updateInfo.source})!\n" +
                "Versione attuale: ${BuildConfig.VERSION_NAME}\n\n" +
                "Novità:\n${updateInfo.releaseNotes}\n\n" +
                "Vuoi scaricare e installare l'aggiornamento adesso?"
            )
            .setPositiveButton("SCARICA E INSTALLA") { _, _ ->
                downloadAndInstallApk(activity, updateInfo.apkUrl, updateInfo.versionName)
            }

        if (!updateInfo.forceUpdate) {
            builder.setNegativeButton("DOPO", null)
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    @Suppress("DEPRECATION")
    fun downloadAndInstallApk(activity: Activity, apkUrl: String, versionName: String) {
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Scaricamento aggiornamento")
            setMessage("Download di $versionName in corso...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            isIndeterminate = false
            setCancelable(false)
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(apkUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "VHPMatchPresentation-App")
                connection.connect()

                // Follow redirects manually if needed (GitHub releases redirect to AWS S3)
                var redirectConn = connection
                var redirectCount = 0
                while ((redirectConn.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        redirectConn.responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        redirectConn.responseCode == 307 ||
                        redirectConn.responseCode == 308) && redirectCount < 5) {
                    val newUrl = redirectConn.getHeaderField("Location")
                    redirectConn.disconnect()
                    val nextUrl = URL(newUrl)
                    redirectConn = nextUrl.openConnection() as HttpURLConnection
                    redirectConn.connectTimeout = 15000
                    redirectConn.readTimeout = 30000
                    redirectConn.setRequestProperty("User-Agent", "VHPMatchPresentation-App")
                    redirectConn.connect()
                    redirectCount++
                }

                if (redirectConn.responseCode != HttpURLConnection.HTTP_OK) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(activity, "Errore download server: HTTP ${redirectConn.responseCode}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val fileLength = redirectConn.contentLength
                val downloadsDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
                val outputFile = File(downloadsDir, "update_${versionName.replace("[^a-zA-Z0-9_.]".toRegex(), "")}.apk")
                if (outputFile.exists()) outputFile.delete()

                val input: InputStream = redirectConn.inputStream
                val output = FileOutputStream(outputFile)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int

                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = ((total * 100) / fileLength).toInt()
                        withContext(Dispatchers.Main) {
                            progressDialog.progress = progress
                        }
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()
                redirectConn.disconnect()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    pendingApkFile = outputFile
                    promptInstallApk(activity, outputFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error downloading APK", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(activity, "Errore download APK: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun promptInstallApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(activity, "File APK non trovato o corrotto.", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(activity)
                    .setTitle("Autorizzazione Richiesta")
                    .setMessage("Per installare l'aggiornamento, autorizza l'installazione di app da questa origine nelle impostazioni di Android.")
                    .setPositiveButton("IMPOSTAZIONI") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package installer", e)
            Toast.makeText(activity, "Impossibile avviare l'installazione: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
