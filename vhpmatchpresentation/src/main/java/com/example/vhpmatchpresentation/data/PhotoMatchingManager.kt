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
            val withoutExt = raw.substringBeforeLast(".")
            val normalized = Normalizer.normalize(withoutExt, Normalizer.Form.NFD)
            return normalized
                .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .lowercase()
                .replace("[^a-z0-9]".toRegex(), "")
        }
    }

    fun savePhotoMapping(key: String, photoUri: String) {
        if (key.isBlank() || photoUri.isBlank()) return
        prefs.edit().putString(key, photoUri).apply()
    }

    fun getPhotoUriForPlayer(playerId: String): String? {
        val direct = prefs.getString(playerId, null)
        if (!direct.isNullOrEmpty()) return direct
        return null
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
        val unusedUris = uris.toMutableList()

        // 1. Take persistable URI permissions on ALL selected photo URIs
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("PhotoMatching", "Could not take persistable permission for $uri", e)
            }
        }

        // 2. Pass 1: Match by exact or partial player name / display name / jersey number in file name
        for (player in players) {
            val normalizedPlayerName = normalizeName(player.name)
            val normalizedDisplayName = normalizeName(player.displayName)
            val playerNum = player.number.trim()

            val matchedUri = unusedUris.firstOrNull { uri ->
                val fileName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: ""
                val normalizedFile = normalizeName(fileName)

                if (normalizedFile.isEmpty()) return@firstOrNull false

                val isNameMatch = (normalizedPlayerName.length > 2 && (normalizedFile.contains(normalizedPlayerName) || normalizedPlayerName.contains(normalizedFile))) ||
                        (normalizedDisplayName.length > 2 && (normalizedFile.contains(normalizedDisplayName) || normalizedDisplayName.contains(normalizedFile)))

                val isNumMatch = playerNum.isNotEmpty() && (normalizedFile == playerNum || normalizedFile == "num$playerNum" || normalizedFile == "p$playerNum" || normalizedFile.contains("player$playerNum"))

                isNameMatch || isNumMatch
            }

            if (matchedUri != null) {
                matches[player.id] = matchedUri
                savePhotoMapping(player.id, matchedUri.toString())
                savePhotoMapping(player.name, matchedUri.toString())
                savePhotoMapping(player.number, matchedUri.toString())
                unusedUris.remove(matchedUri)
            }
        }

        // 3. Pass 2: Sequential Fallback for remaining unmatched players
        val unmatchedPlayers = players.filter { !matches.containsKey(it.id) }
        for (i in unmatchedPlayers.indices) {
            if (i < unusedUris.size) {
                val player = unmatchedPlayers[i]
                val uri = unusedUris[i]
                matches[player.id] = uri
                savePhotoMapping(player.id, uri.toString())
                savePhotoMapping(player.name, uri.toString())
                savePhotoMapping(player.number, uri.toString())
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
