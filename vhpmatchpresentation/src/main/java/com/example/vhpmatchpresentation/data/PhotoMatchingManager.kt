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

        fun extractNameAliases(rawName: String): List<String> {
            val trimmed = rawName.trim()
            if (trimmed.isBlank()) return emptyList()

            val aliases = mutableSetOf<String>()
            val primaryNorm = normalizeName(trimmed)
            if (primaryNorm.isNotEmpty()) {
                aliases.add(primaryNorm)
            }

            // Split by spaces, dots, dashes, underscores
            val parts = trimmed.split("[\\s._\\-]+".toRegex()).filter { it.isNotBlank() }
            if (parts.size >= 2) {
                // Surname (last part)
                val surname = parts.last()
                val normSurname = normalizeName(surname)
                if (normSurname.length >= 3) {
                    aliases.add(normSurname)
                }

                // First initial + surname (e.g. M. Rossi -> mrossi)
                val firstInitial = parts.first().take(1)
                val initialPlusSurname = normalizeName("$firstInitial$surname")
                if (initialPlusSurname.isNotEmpty()) {
                    aliases.add(initialPlusSurname)
                }

                // First name (first part)
                val firstName = parts.first()
                val normFirstName = normalizeName(firstName)
                if (normFirstName.length >= 3) {
                    aliases.add(normFirstName)
                }
            }

            return aliases.toList()
        }
    }

    init {
        cleanupLegacyKeys()
    }

    fun cleanupLegacyKeys() {
        try {
            val allKeys = prefs.all.keys
            val legacyKeys = allKeys.filter { key ->
                key.matches("^[abAB]_[0-9]+$".toRegex()) || (key.matches("^[0-9]+$".toRegex()) && !key.startsWith("num_"))
            }
            if (legacyKeys.isNotEmpty()) {
                val editor = prefs.edit()
                for (key in legacyKeys) {
                    editor.remove(key)
                }
                editor.apply()
                Log.i("PhotoMatching", "Cleaned up ${legacyKeys.size} legacy positional photo keys")
            }
        } catch (e: Exception) {
            Log.e("PhotoMatching", "Error cleaning up legacy keys", e)
        }
    }

    fun convertUriToBase64(uriString: String): String {
        if (uriString.isBlank()) return ""
        if (uriString.startsWith("data:image/") || uriString.startsWith("http://") || uriString.startsWith("https://")) {
            return uriString
        }
        return try {
            val uri = Uri.parse(uriString)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return uriString
            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBitmap == null) return uriString

            val maxDimension = 350
            val width = originalBitmap.width
            val height = originalBitmap.height
            val scale = Math.min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
            val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
            val scaledHeight = (height * scale).toInt().coerceAtLeast(1)

            val resizedBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
            val outputStream = java.io.ByteArrayOutputStream()
            resizedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64Str = android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64Str"
        } catch (e: Exception) {
            Log.e("PhotoMatchingManager", "Error converting URI to Base64", e)
            uriString
        }
    }

    fun savePhotoForPlayer(name: String, photoUri: String, variant: String = "") {
        if (name.isBlank() || photoUri.isBlank()) return
        val finalPhoto = convertUriToBase64(photoUri)
        val editor = prefs.edit()
        val varLower = variant.trim().lowercase()

        val aliases = extractNameAliases(name)
        for (alias in aliases) {
            if (varLower.isNotBlank()) {
                editor.putString("${alias}_$varLower", finalPhoto)
                if (varLower == "red") editor.putString("${alias}_rossa", finalPhoto)
                if (varLower == "blue") editor.putString("${alias}_azzurra", finalPhoto)
            }
            // Always set default fallback key as well
            editor.putString(alias, finalPhoto)
        }
        editor.apply()
    }

    fun savePhotoForNumber(number: String, photoUri: String, variant: String = "") {
        val numTrimmed = number.trim().replace("[^0-9]".toRegex(), "")
        if (numTrimmed.isBlank() || photoUri.isBlank()) return
        val finalPhoto = convertUriToBase64(photoUri)
        val editor = prefs.edit()
        val varLower = variant.trim().lowercase()

        val numKey = "num_$numTrimmed"
        if (varLower.isNotBlank()) {
            editor.putString("${numKey}_$varLower", finalPhoto)
            if (varLower == "red") editor.putString("${numKey}_rossa", finalPhoto)
            if (varLower == "blue") editor.putString("${numKey}_azzurra", finalPhoto)
        }
        editor.putString(numKey, finalPhoto)
        editor.apply()
    }

    fun getPhotoUriForPlayer(name: String, teamColorHex: String = "", number: String = ""): String? {
        val colorType = determineColorType(teamColorHex)
        val aliases = extractNameAliases(name)

        // 1. Check all name aliases with variant priority
        for (alias in aliases) {
            if (colorType == "red") {
                val redUri = prefs.getString("${alias}_red", null)
                    ?: prefs.getString("${alias}_rossa", null)
                if (!redUri.isNullOrEmpty()) return redUri
            } else if (colorType == "blue") {
                val blueUri = prefs.getString("${alias}_blue", null)
                    ?: prefs.getString("${alias}_azzurra", null)
                if (!blueUri.isNullOrEmpty()) return blueUri
            }

            val defaultUri = prefs.getString(alias, null)
            if (!defaultUri.isNullOrEmpty()) return defaultUri
        }

        // 2. Fuzzy substring check in SharedPreferences keys
        val primaryNorm = normalizeName(name)
        if (primaryNorm.length >= 4) {
            val allPrefs = prefs.all
            for ((key, value) in allPrefs) {
                if (value !is String || value.isBlank()) continue
                val cleanKey = key.removeSuffix("_red").removeSuffix("_blue").removeSuffix("_rossa").removeSuffix("_azzurra")
                if (cleanKey.length >= 4 && (primaryNorm.contains(cleanKey) || cleanKey.contains(primaryNorm))) {
                    if (colorType == "red" && (key.endsWith("_red") || key.endsWith("_rossa"))) return value
                    if (colorType == "blue" && (key.endsWith("_blue") || key.endsWith("_azzurra"))) return value
                    return value
                }
            }
        }

        // 3. Fallback to jersey number lookup
        val numTrimmed = number.trim().replace("[^0-9]".toRegex(), "")
        if (numTrimmed.isNotBlank()) {
            val numKey = "num_$numTrimmed"
            if (colorType == "red") {
                val redNumUri = prefs.getString("${numKey}_red", null)
                    ?: prefs.getString("${numKey}_rossa", null)
                if (!redNumUri.isNullOrEmpty()) return redNumUri
            } else if (colorType == "blue") {
                val blueNumUri = prefs.getString("${numKey}_blue", null)
                    ?: prefs.getString("${numKey}_azzurra", null)
                if (!blueNumUri.isNullOrEmpty()) return blueNumUri
            }

            val defaultNumUri = prefs.getString(numKey, null)
            if (!defaultNumUri.isNullOrEmpty()) return defaultNumUri
        }

        return null
    }

    fun determineColorType(hexColor: String): String {
        if (hexColor.isBlank()) return "default"
        try {
            val cleaned = hexColor.trim().removePrefix("#")
            if (cleaned.length != 6) return "default"
            val r = cleaned.substring(0, 2).toInt(16)
            val g = cleaned.substring(2, 4).toInt(16)
            val b = cleaned.substring(4, 6).toInt(16)

            if (r > 120 && r > g * 1.2 && r > b * 1.2) return "red"
            if (b > 120 && b > r * 1.1) return "blue"
            if (g > 120 && b > 120 && (g + b) > r * 1.4) return "blue"
        } catch (e: Exception) {
            // Ignore color parse errors
        }
        return "default"
    }

    fun removePhotoForPlayer(name: String, number: String = "") {
        val aliases = extractNameAliases(name)
        val editor = prefs.edit()
        for (alias in aliases) {
            editor.remove(alias)
                .remove("${alias}_red")
                .remove("${alias}_blue")
                .remove("${alias}_rossa")
                .remove("${alias}_azzurra")
        }
        val numTrimmed = number.trim().replace("[^0-9]".toRegex(), "")
        if (numTrimmed.isNotBlank()) {
            val numKey = "num_$numTrimmed"
            editor.remove(numKey)
                .remove("${numKey}_red")
                .remove("${numKey}_blue")
                .remove("${numKey}_rossa")
                .remove("${numKey}_azzurra")
        }
        editor.apply()
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

        for (player in players) {
            val playerAliases = extractNameAliases(player.name) + extractNameAliases(player.displayName)
            val playerNum = player.number.trim().replace("[^0-9]".toRegex(), "")

            val matchedUris = unusedUris.filter { uri ->
                val fileName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: ""
                val normalizedFile = normalizeName(fileName)
                if (normalizedFile.isEmpty()) return@filter false

                val isNameMatch = playerAliases.any { alias ->
                    alias.length >= 3 && (normalizedFile.contains(alias) || alias.contains(normalizedFile))
                }

                val isNumMatch = playerNum.isNotEmpty() && (
                    normalizedFile == playerNum ||
                    normalizedFile == "num$playerNum" ||
                    normalizedFile == "p$playerNum" ||
                    normalizedFile.contains("player$playerNum") ||
                    normalizedFile.contains("num$playerNum")
                )

                isNameMatch || isNumMatch
            }

            for (matchedUri in matchedUris) {
                val fileName = (getFileNameFromUri(matchedUri) ?: matchedUri.lastPathSegment ?: "").lowercase()
                val isRedVariant = fileName.contains("red") || fileName.contains("rossa") || fileName.contains("home") || fileName.contains("amaranto")
                val isBlueVariant = fileName.contains("blue") || fileName.contains("azzurra") || fileName.contains("blu") || fileName.contains("away")

                val variant = when {
                    isRedVariant -> "red"
                    isBlueVariant -> "blue"
                    else -> ""
                }

                matches[player.id] = matchedUri
                savePhotoForPlayer(player.name, matchedUri.toString(), variant)
                if (player.displayName.isNotBlank()) {
                    savePhotoForPlayer(player.displayName, matchedUri.toString(), variant)
                }
                if (playerNum.isNotBlank()) {
                    savePhotoForNumber(playerNum, matchedUri.toString(), variant)
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
