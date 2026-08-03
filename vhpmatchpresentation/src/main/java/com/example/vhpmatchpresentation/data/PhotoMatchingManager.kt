package com.example.vhpmatchpresentation.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.text.Normalizer

class PhotoMatchingManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("vhp_player_photos", Context.MODE_PRIVATE)

    companion object {
        fun normalizeName(raw: String): String {
            if (raw.isBlank()) return ""
            // Strip file extension if present
            val withoutExt = raw.substringBeforeLast(".")
            val normalized = Normalizer.normalize(withoutExt, Normalizer.Form.NFD)
            // Remove diacritical marks & non-alphanumeric chars, to lower case
            return normalized
                .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .lowercase()
                .replace("[^a-z0-9]".toRegex(), "")
        }
    }

    fun savePhotoMapping(playerId: String, photoUri: String) {
        prefs.edit().putString(playerId, photoUri).apply()
    }

    fun getPhotoUriForPlayer(playerId: String): String? {
        return prefs.getString(playerId, null)
    }

    fun removePhotoMapping(playerId: String) {
        prefs.edit().remove(playerId).apply()
    }

    fun getAllMappings(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return prefs.all as Map<String, String>
    }

    fun autoMatchPhotos(
        uris: List<Uri>,
        players: List<PlayerPresentation>
    ): Map<String, Uri> {
        val matches = mutableMapOf<String, Uri>()
        
        // Take persistable URI permissions where possible
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("PhotoMatching", "Could not take persistable permission for $uri", e)
            }

            val fileName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: continue
            val normalizedFile = normalizeName(fileName)

            for (player in players) {
                val normalizedPlayerName = normalizeName(player.name)
                val normalizedDisplayName = normalizeName(player.displayName)

                if (normalizedFile.isNotEmpty() &&
                    (normalizedFile == normalizedPlayerName || normalizedFile == normalizedDisplayName ||
                     normalizedFile.contains(normalizedPlayerName) || normalizedPlayerName.contains(normalizedFile))
                ) {
                    matches[player.id] = uri
                    savePhotoMapping(player.id, uri.toString())
                    break
                }
            }
        }
        return matches
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.e("PhotoMatching", "Error reading display name from URI", e)
        }
        return name
    }
}
