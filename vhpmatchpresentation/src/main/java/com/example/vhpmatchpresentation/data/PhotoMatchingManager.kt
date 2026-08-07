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

    init {
        cleanupLegacyKeys()
    }

    fun cleanupLegacyKeys() {
        try {
            val allKeys = prefs.all.keys
            val legacyKeys = allKeys.filter { key ->
                key.matches("^[abAB]_[0-9]+$".toRegex()) || key.matches("^[0-9]+$".toRegex()) || key.matches("^num[0-9]+$".toRegex())
            }
            if (legacyKeys.isNotEmpty()) {
                val editor = prefs.edit()
                for (key in legacyKeys) {
                    editor.remove(key)
                }
                editor.apply()
                Log.i("PhotoMatching", "Cleaned up ${legacyKeys.size} legacy positional/numeric photo keys")
            }
        } catch (e: Exception) {
            Log.e("PhotoMatching", "Error cleaning up legacy keys", e)
        }
    }

    fun savePhotoForPlayer(name: String, photoUri: String) {
        val normKey = normalizeName(name)
        if (normKey.isBlank() || photoUri.isBlank()) return
        prefs.edit().putString(normKey, photoUri).apply()
    }

    fun getPhotoUriForPlayer(name: String): String? {
        val normKey = normalizeName(name)
        if (normKey.isBlank()) return null
        val uriStr = prefs.getString(normKey, null)
        if (!uriStr.isNullOrEmpty()) return uriStr
        return null
    }

    fun removePhotoForPlayer(name: String) {
        val normKey = normalizeName(name)
        if (normKey.isBlank()) return
        prefs.edit().remove(normKey).apply()
    }

    fun savePhotoMapping(key: String, photoUri: String) {
        savePhotoForPlayer(key, photoUri)
    }

    fun removePhotoMapping(key: String) {
        removePhotoForPlayer(key)
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

        // 2. Match by exact or partial player name / display name in file name
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
                savePhotoForPlayer(player.name, matchedUri.toString())
                if (player.displayName.isNotBlank()) {
                    savePhotoForPlayer(player.displayName, matchedUri.toString())
                }
                unusedUris.remove(matchedUri)
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
