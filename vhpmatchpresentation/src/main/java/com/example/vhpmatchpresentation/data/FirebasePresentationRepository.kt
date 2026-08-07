package com.example.vhpmatchpresentation.data

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FirebasePresentationRepository(private val context: Context) {

    private val database = FirebaseDatabase.getInstance("https://volley-hub-c90ca-default-rtdb.europe-west1.firebasedatabase.app")
    private val photoManager = PhotoMatchingManager(context)

    private var userRootRef: com.google.firebase.database.DatabaseReference? = null

    private val _presentationData = MutableStateFlow(MatchPresentationData())
    val presentationData: StateFlow<MatchPresentationData> = _presentationData.asStateFlow()

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private var userListener: ValueEventListener? = null
    private var lastScoreA: Int = -1
    private var lastScoreB: Int = -1
    private var currentServingTeam: String = ""

    init {
        val connectedRef = database.getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                _connectionState.value = connected
                Log.i("FirebaseRepo", "Database connection status: $connected")
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseRepo", "Connection listener cancelled: ${error.message}")
            }
        })
    }

    fun refreshPhotos() {
        val current = _presentationData.value
        val updatedTeamA = current.teamA.copy(
            players = current.teamA.players.map { player ->
                val localPhoto = photoManager.getPhotoUriForPlayer(player.name)
                    ?: photoManager.getPhotoUriForPlayer(player.displayName)
                player.copy(photoUri = if (!localPhoto.isNullOrEmpty()) localPhoto else player.photoUri)
            }
        )
        val updatedTeamB = current.teamB.copy(
            players = current.teamB.players.map { player ->
                val localPhoto = photoManager.getPhotoUriForPlayer(player.name)
                    ?: photoManager.getPhotoUriForPlayer(player.displayName)
                player.copy(photoUri = if (!localPhoto.isNullOrEmpty()) localPhoto else player.photoUri)
            }
        )
        _presentationData.value = current.copy(teamA = updatedTeamA, teamB = updatedTeamB)
    }

    fun startObserving(uid: String, matchKey: String = "", source: String = "index") {
        if (uid.isEmpty()) return
        Log.i("FirebaseRepo", "startObserving for uid: $uid")

        userRootRef = database.getReference("users/$uid")
        userListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                try {
                    parseFullUserSnapshot(snapshot)
                } catch (e: Exception) {
                    Log.e("FirebaseRepo", "Error parsing full user snapshot", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseRepo", "User listener cancelled: ${error.message}")
            }
        }
        userRootRef?.addValueEventListener(userListener!!)
    }

    private fun parseFullUserSnapshot(userSnap: DataSnapshot) {
        var nameA = ""
        var nameB = ""
        var logoA = ""
        var logoB = ""
        var colorA = ""
        var colorB = ""

        var scoreA = 0
        var scoreB = 0
        var setsA = 0
        var setsB = 0
        var serving = ""

        val playersA = mutableListOf<PlayerPresentation>()
        val playersB = mutableListOf<PlayerPresentation>()
        var hasActiveFormation = false

        // 1. PRIMARY SOURCE FOR VPM AND VHU: settings node
        val settingsSnap = userSnap.child("settings")
        if (settingsSnap.exists()) {
            nameA = extractString(settingsSnap, "myTeam", "teamAName", "teamA", "myTeamName", "team_a_name", "nomeA", "squadraA")
            nameB = extractString(settingsSnap, "opponentTeam", "teamBName", "teamB", "opponentTeamName", "team_b_name", "nomeB", "squadraB")

            logoA = extractLogo(settingsSnap, "logoA", "logoTeamA", "myTeamLogo", "imageA")
            logoB = extractLogo(settingsSnap, "logoB", "logoTeamB", "opponentTeamLogo", "imageB")

            colorA = extractString(settingsSnap, "colorA", "primaryColorA", "teamAColor")
            colorB = extractString(settingsSnap, "colorB", "primaryColorB", "teamBColor")
        }

        // 2. SECONDARY SOURCE: matchData (liveMatchProgress_index / liveMatchProgress_local / formation / settings / rosters)
        val matchDataSnap = userSnap.child("matchData")
        if (matchDataSnap.exists()) {
            val liveSnap = matchDataSnap.child("liveMatchProgress_index")
                .takeIf { it.exists() }
                ?: matchDataSnap.child("liveMatchProgress_local")
                .takeIf { it.exists() }
                ?: matchDataSnap.child("liveMatchProgress")
                .takeIf { it.exists() }
                ?: matchDataSnap.child("liveMatch")

            if (liveSnap.exists()) {
                if (nameA.isEmpty()) nameA = extractString(liveSnap, "teamAName", "myTeam", "teamA", "team_a_name", "squadraA")
                if (nameB.isEmpty()) nameB = extractString(liveSnap, "teamBName", "opponentTeam", "teamB", "team_b_name", "squadraB")

                scoreA = liveSnap.child("scoreA").getValue(Int::class.java) ?: 0
                scoreB = liveSnap.child("scoreB").getValue(Int::class.java) ?: 0
                setsA = liveSnap.child("setsWonA").getValue(Int::class.java)
                    ?: liveSnap.child("setsA").getValue(Int::class.java) ?: 0
                setsB = liveSnap.child("setsWonB").getValue(Int::class.java)
                    ?: liveSnap.child("setsB").getValue(Int::class.java) ?: 0

                if (logoA.isEmpty()) logoA = extractLogo(liveSnap, "logoA", "teamALogo", "myTeamLogo")
                if (logoB.isEmpty()) logoB = extractLogo(liveSnap, "logoB", "teamBLogo", "opponentTeamLogo")

                if (colorA.isEmpty()) colorA = extractString(liveSnap, "teamAColor", "colorA")
                if (colorB.isEmpty()) colorB = extractString(liveSnap, "teamBColor", "colorB")

                val livePlayersA = parsePlayerListSnapshot(liveSnap.child("teamAPlayers"), "A")
                val livePlayersB = parsePlayerListSnapshot(liveSnap.child("teamBPlayers"), "B")
                if (livePlayersA.isNotEmpty()) playersA.addAll(livePlayersA)
                if (livePlayersB.isNotEmpty()) playersB.addAll(livePlayersB)
            }

            // Check matchData/formation (Web App Active Lineup Priority)
            val formationSnap = matchDataSnap.child("formation")
            if (formationSnap.exists()) {
                if (nameA.isEmpty()) nameA = extractString(formationSnap, "teamAName", "myTeam", "teamA", "benchA")
                if (nameB.isEmpty()) nameB = extractString(formationSnap, "teamBName", "opponentTeam", "teamB", "benchB")

                // index.html saves the current lineup as formation/tokens, not playersA/playersB.
                // Read those tokens first so the phone mirrors the exact formation shown on the web app.
                val formationTokens = formationSnap.child("tokens")
                val formPlayersA: List<PlayerPresentation>
                val formPlayersB: List<PlayerPresentation>
                if (formationTokens.exists()) {
                    hasActiveFormation = true
                    formPlayersA = parseFormationTokens(formationTokens, "A", userSnap)
                    formPlayersB = parseFormationTokens(formationTokens, "B", userSnap)
                } else {
                    formPlayersA = parsePlayerListSnapshot(formationSnap.child("playersA"), "A")
                        .ifEmpty { parsePlayerListSnapshot(formationSnap.child("lineupA"), "A") }
                        .ifEmpty { parsePlayerListSnapshot(formationSnap.child("teamAPlayers"), "A") }

                    formPlayersB = parsePlayerListSnapshot(formationSnap.child("playersB"), "B")
                        .ifEmpty { parsePlayerListSnapshot(formationSnap.child("lineupB"), "B") }
                        .ifEmpty { parsePlayerListSnapshot(formationSnap.child("teamBPlayers"), "B") }
                }

                if (hasActiveFormation || formPlayersA.isNotEmpty()) {
                    playersA.clear()
                    playersA.addAll(formPlayersA)
                }
                if (hasActiveFormation || formPlayersB.isNotEmpty()) {
                    playersB.clear()
                    playersB.addAll(formPlayersB)
                }
            }

            // Check matchData/rosters (Web App Roster Priority if formation empty)
            val rostersSnap = matchDataSnap.child("rosters")
            if (rostersSnap.exists() && !hasActiveFormation) {
                // Look for team matching nameA or nameB or first two keys
                for (teamChild in rostersSnap.children) {
                    val key = teamChild.key ?: continue
                    val tName = extractString(teamChild, "teamName", "name", "squadra")
                    if (key.equals(nameA, ignoreCase = true) || tName.equals(nameA, ignoreCase = true) || key.contains("A", ignoreCase = true)) {
                        val rosterPlayersA = parsePlayerListSnapshot(teamChild.child("players"), "A")
                            .ifEmpty { parsePlayerListSnapshot(teamChild, "A") }
                        if (rosterPlayersA.isNotEmpty() && playersA.isEmpty()) {
                            playersA.addAll(rosterPlayersA)
                        }
                    } else if (key.equals(nameB, ignoreCase = true) || tName.equals(nameB, ignoreCase = true) || key.contains("B", ignoreCase = true)) {
                        val rosterPlayersB = parsePlayerListSnapshot(teamChild.child("players"), "B")
                            .ifEmpty { parsePlayerListSnapshot(teamChild, "B") }
                        if (rosterPlayersB.isNotEmpty() && playersB.isEmpty()) {
                            playersB.addAll(rosterPlayersB)
                        }
                    }
                }

                if (playersA.isEmpty() || playersB.isEmpty()) {
                    val teamKeys = rostersSnap.children.mapNotNull { it.key }
                    if (teamKeys.isNotEmpty() && playersA.isEmpty()) {
                        playersA.addAll(parsePlayerListSnapshot(rostersSnap.child(teamKeys[0]).child("players"), "A").ifEmpty { parsePlayerListSnapshot(rostersSnap.child(teamKeys[0]), "A") })
                    }
                    if (teamKeys.size > 1 && playersB.isEmpty()) {
                        playersB.addAll(parsePlayerListSnapshot(rostersSnap.child(teamKeys[1]).child("players"), "B").ifEmpty { parsePlayerListSnapshot(rostersSnap.child(teamKeys[1]), "B") })
                    }
                }
            }
        }

        // 3. Serve logic (Volleyball side-out rules + Firebase override):
        // At 0 - 0: No ball (servingTeam = "").
        // First team to score point gets serve. Subsequent points follow winner of point.
        val rawServing = extractString(
            matchDataSnap.child("liveMatchProgress_index"),
            "servingTeam", "serving", "battuta", "serve", "currentServe", "servizio", "server", "battutaTeam"
        ).ifEmpty {
            extractString(
                matchDataSnap.child("formation"),
                "servingTeam", "serving", "battuta", "serve", "currentServe"
            )
        }

        val normalizedRawServing = when {
            rawServing.equals("A", ignoreCase = true) || rawServing.equals("teamA", ignoreCase = true) || (nameA.isNotEmpty() && rawServing.equals(nameA, ignoreCase = true)) -> "A"
            rawServing.equals("B", ignoreCase = true) || rawServing.equals("teamB", ignoreCase = true) || (nameB.isNotEmpty() && rawServing.equals(nameB, ignoreCase = true)) -> "B"
            else -> ""
        }

        if (scoreA == 0 && scoreB == 0) {
            currentServingTeam = ""
        } else if (normalizedRawServing.isNotEmpty()) {
            currentServingTeam = normalizedRawServing
        } else {
            if (lastScoreA >= 0 && lastScoreB >= 0) {
                if (scoreA > lastScoreA && scoreB == lastScoreB) {
                    currentServingTeam = "A"
                } else if (scoreB > lastScoreB && scoreA == lastScoreA) {
                    currentServingTeam = "B"
                }
            } else {
                if (scoreA > scoreB) currentServingTeam = "A"
                else if (scoreB > scoreA) currentServingTeam = "B"
            }
        }
        lastScoreA = scoreA
        lastScoreB = scoreB
        serving = currentServingTeam

        // Strict Default Fallbacks for Team Names
        if (nameA.isEmpty()) nameA = "VPM"
        if (nameB.isEmpty()) nameB = "VHU"
        if (colorA.isEmpty()) colorA = "#0284C7"
        if (colorB.isEmpty()) colorB = "#E11D48"

        Log.i("FirebaseRepo", "Parsed Match State: Team A='$nameA' (${playersA.size} players), Team B='$nameB' (${playersB.size} players), Score: $scoreA - $scoreB, Serve: '$serving'")

        val current = _presentationData.value
        _presentationData.value = current.copy(
            teamA = current.teamA.copy(
                name = nameA,
                logoUrl = logoA.ifEmpty { current.teamA.logoUrl },
                primaryColorHex = colorA,
                players = when {
                    hasActiveFormation -> playersA
                    playersA.isNotEmpty() -> playersA
                    else -> current.teamA.players
                }
            ),
            teamB = current.teamB.copy(
                name = nameB,
                logoUrl = logoB.ifEmpty { current.teamB.logoUrl },
                primaryColorHex = colorB,
                players = when {
                    hasActiveFormation -> playersB
                    playersB.isNotEmpty() -> playersB
                    else -> current.teamB.players
                }
            ),
            scoreA = scoreA,
            scoreB = scoreB,
            setsA = setsA,
            setsB = setsB,
            servingTeam = serving
        )
    }

    private fun extractString(snap: DataSnapshot, vararg keys: String): String {
        for (key in keys) {
            val valStr = snap.child(key).getValue(String::class.java)
            if (!valStr.isNullOrEmpty() && !valStr.equals("Team A", ignoreCase = true) && !valStr.equals("Team B", ignoreCase = true)) {
                return valStr
            }
        }
        return ""
    }

    private fun extractLogo(snap: DataSnapshot, vararg keys: String): String {
        for (key in keys) {
            val valStr = snap.child(key).getValue(String::class.java)
            if (!valStr.isNullOrEmpty()) return valStr
        }
        return ""
    }

    private fun parsePlayerListSnapshot(snap: DataSnapshot, teamTag: String): List<PlayerPresentation> {
        val list = mutableListOf<PlayerPresentation>()
        if (!snap.exists()) return list

        for (child in snap.children) {
            val key = child.key ?: continue
            if (key == "teamName" || key == "name" || key == "logo" || key == "color") continue

            val id = "${teamTag}_$key"

            val name = child.child("name").getValue(String::class.java)
                ?: child.child("nome").getValue(String::class.java)
                ?: child.child("displayName").getValue(String::class.java)
                ?: child.child("playerName").getValue(String::class.java)
                ?: "Player $key"

            val number = child.child("num").getValue(Any::class.java)?.toString()
                ?: child.child("number").getValue(Any::class.java)?.toString()
                ?: child.child("numero").getValue(Any::class.java)?.toString()
                ?: child.child("jerseyNumber").getValue(Any::class.java)?.toString()
                ?: key

            val role = extractPlayerRole(child)

            val fbPhotoUri = child.child("photoUrl").getValue(String::class.java)
                ?: child.child("photo").getValue(String::class.java)
                ?: child.child("foto").getValue(String::class.java)
                ?: child.child("image").getValue(String::class.java)
                ?: child.child("picture").getValue(String::class.java)
                ?: child.child("url").getValue(String::class.java)
                ?: child.child("uri").getValue(String::class.java)
                ?: child.child("src").getValue(String::class.java)
                ?: child.child("path").getValue(String::class.java)
                ?: ""

            val localPhotoUri = photoManager.getPhotoUriForPlayer(name)

            val finalPhotoUri = if (fbPhotoUri.isNotEmpty()) fbPhotoUri else (localPhotoUri ?: "")

            val statsSnap = child.child("stats").takeIf { it.exists() } ?: child.child("statistiche")
            val attack = parseStat(statsSnap, "spike", "attacco", 75)
            val block = parseStat(statsSnap, "block", "muro", 70)
            val serve = parseStat(statsSnap, "serve", "battuta", 80)
            val receive = parseStat(statsSnap, "receive", "ricezione", 68)
            val defense = parseStat(statsSnap, "defense", "difesa", 72)
            val setVal = parseStat(statsSnap, "set", "alzata", 65)

            list.add(
                PlayerPresentation(
                    id = id,
                    name = name,
                    displayName = name,
                    number = number,
                    role = role,
                    photoUri = finalPhotoUri,
                    stats = PlayerStats(
                        attack = attack,
                        block = block,
                        serve = serve,
                        receive = receive,
                        defense = defense,
                        set = setVal
                    )
                )
            )
        }
        return list
    }

    /**
     * The formation editor stores every player as a lightweight token:
     * { name, team: "team-a" | "team-b", role, gender, ... }.
     * Use the token name as the primary value; roster data only enriches number,
     * role, photo and statistics when it is available.
     */
    private fun parseFormationTokens(
        tokensSnap: DataSnapshot,
        teamTag: String,
        rosterSnap: DataSnapshot
    ): List<PlayerPresentation> {
        val players = mutableListOf<PlayerPresentation>()
        val expectedTeam = if (teamTag == "A") "team-a" else "team-b"

        for (token in tokensSnap.children) {
            val tokenTeam = token.child("team").getValue(String::class.java)?.trim()?.lowercase()
            if (tokenTeam != expectedTeam) continue

            val tokenName = token.child("name").getValue(String::class.java)?.trim().orEmpty()
            val rosterPlayer = findRosterPlayerByName(rosterSnap, tokenName)
            val fallbackId = token.key ?: (players.size + 1).toString()
            val id = "${teamTag}_$fallbackId"

            val name = tokenName.ifEmpty {
                rosterPlayer?.child("name")?.getValue(String::class.java)?.trim().orEmpty()
            }.ifEmpty { "Player $fallbackId" }
            val number = rosterPlayer?.child("num")?.getValue(Any::class.java)?.toString()
                ?: rosterPlayer?.child("number")?.getValue(Any::class.java)?.toString()
                ?: rosterPlayer?.child("numero")?.getValue(Any::class.java)?.toString()
                ?: (players.size + 1).toString()
            // The formation token stores only the tactical display role
            // (usually Universal/Libero). The roster is the authoritative role.
            val role = rosterPlayer?.let { extractPlayerRole(it) }
                ?: token.child("role").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                ?: "Universal"

            val firebasePhotoUri = rosterPlayer?.let { extractPhotoUri(it) }.orEmpty()
            val localPhotoUri = photoManager.getPhotoUriForPlayer(name)
            val statsSnap = rosterPlayer?.child("stats")?.takeIf { it.exists() }
                ?: rosterPlayer?.child("statistiche")

            players.add(
                PlayerPresentation(
                    id = id,
                    name = name,
                    displayName = name,
                    number = number,
                    role = role,
                    photoUri = firebasePhotoUri.ifEmpty { localPhotoUri.orEmpty() },
                    stats = PlayerStats(
                        attack = parseStat(statsSnap, "spike", "attacco", 75),
                        block = parseStat(statsSnap, "block", "muro", 70),
                        serve = parseStat(statsSnap, "serve", "battuta", 80),
                        receive = parseStat(statsSnap, "receive", "ricezione", 68),
                        defense = parseStat(statsSnap, "defense", "difesa", 72),
                        set = parseStat(statsSnap, "set", "alzata", 65)
                    )
                )
            )
        }
        return players
    }

    private fun findRosterPlayerByName(rosterSnap: DataSnapshot, name: String): DataSnapshot? {
        if (name.isBlank() || !rosterSnap.exists()) return null
        fun search(snap: DataSnapshot): DataSnapshot? {
            for (child in snap.children) {
                for (key in listOf("name", "displayName", "playerName", "nome")) {
                    val pName = child.child(key).getValue(String::class.java)?.trim()
                    if (pName != null && pName.equals(name, ignoreCase = true)) {
                        return child
                    }
                }
                val subResult = search(child)
                if (subResult != null) return subResult
            }
            return null
        }
        return search(rosterSnap)
    }

    private fun extractPlayerRole(playerSnap: DataSnapshot): String {
        return listOf("role", "ruolo", "position", "posizione", "playerRole", "roleName")
            .asSequence()
            .mapNotNull { key -> playerSnap.child(key).getValue(String::class.java)?.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: "Outside Hitter"
    }

    private fun extractPhotoUri(playerSnap: DataSnapshot): String {
        return playerSnap.child("photoUrl").getValue(String::class.java)
            ?: playerSnap.child("photo").getValue(String::class.java)
            ?: playerSnap.child("foto").getValue(String::class.java)
            ?: playerSnap.child("image").getValue(String::class.java)
            ?: playerSnap.child("picture").getValue(String::class.java)
            ?: playerSnap.child("url").getValue(String::class.java)
            ?: playerSnap.child("uri").getValue(String::class.java)
            ?: ""
    }

    private fun parseStat(snap: DataSnapshot?, key1: String, key2: String, defaultVal: Int): Int {
        if (snap == null || !snap.exists()) return defaultVal
        val rawNum = snap.child(key1).getValue(Double::class.java)
            ?: snap.child(key2).getValue(Double::class.java)
            ?: snap.child(key1).getValue(Int::class.java)?.toDouble()
            ?: snap.child(key2).getValue(Int::class.java)?.toDouble()
            ?: defaultVal.toDouble()

        return when {
            rawNum in 1.0..5.0 -> (rawNum * 20.0).toInt().coerceIn(0, 100)
            rawNum in 1.0..10.0 -> (rawNum * 10.0).toInt().coerceIn(0, 100)
            else -> rawNum.toInt().coerceIn(0, 100)
        }
    }

    fun stopObserving() {
        userListener?.let { userRootRef?.removeEventListener(it) }
    }
}
