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

    private val _allDatabasePlayers = MutableStateFlow<List<PlayerPresentation>>(emptyList())
    val allDatabasePlayers: StateFlow<List<PlayerPresentation>> = _allDatabasePlayers.asStateFlow()

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
                val localPhoto = photoManager.getPhotoUriForPlayer(player.name, current.teamA.primaryColorHex, player.number)
                    ?: photoManager.getPhotoUriForPlayer(player.displayName, current.teamA.primaryColorHex, player.number)
                player.copy(photoUri = if (!localPhoto.isNullOrEmpty()) localPhoto else player.photoUri)
            }
        )
        val updatedTeamB = current.teamB.copy(
            players = current.teamB.players.map { player ->
                val localPhoto = photoManager.getPhotoUriForPlayer(player.name, current.teamB.primaryColorHex, player.number)
                    ?: photoManager.getPhotoUriForPlayer(player.displayName, current.teamB.primaryColorHex, player.number)
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

    private var lastUserSnap: DataSnapshot? = null

    private fun parseFullUserSnapshot(userSnap: DataSnapshot) {
        lastUserSnap = userSnap
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
        val liveSnap = if (matchDataSnap.exists()) {
            matchDataSnap.child("liveMatchProgress_index").takeIf { it.exists() }
                ?: matchDataSnap.child("liveMatchProgress_local").takeIf { it.exists() }
                ?: matchDataSnap.child("liveMatchProgress").takeIf { it.exists() }
                ?: matchDataSnap.child("liveMatch").takeIf { it.exists() }
                ?: userSnap.child("liveMatchProgress_index").takeIf { it.exists() }
                ?: userSnap.child("liveMatchProgress").takeIf { it.exists() }
                ?: userSnap.child("liveMatch").takeIf { it.exists() }
        } else {
            userSnap.child("liveMatchProgress_index").takeIf { it.exists() }
                ?: userSnap.child("liveMatchProgress").takeIf { it.exists() }
                ?: userSnap.child("liveMatch").takeIf { it.exists() }
        }

        if (liveSnap != null && liveSnap.exists()) {
            if (nameA.isEmpty()) nameA = extractString(liveSnap, "teamAName", "myTeam", "teamA", "team_a_name", "squadraA")
            if (nameB.isEmpty()) nameB = extractString(liveSnap, "teamBName", "opponentTeam", "teamB", "team_b_name", "squadraB")

            scoreA = liveSnap.child("scoreA").getValue(Int::class.java)
                ?: liveSnap.child("puntiA").getValue(Int::class.java)
                ?: liveSnap.child("pointsA").getValue(Int::class.java) ?: 0
            scoreB = liveSnap.child("scoreB").getValue(Int::class.java)
                ?: liveSnap.child("puntiB").getValue(Int::class.java)
                ?: liveSnap.child("pointsB").getValue(Int::class.java) ?: 0

            setsA = liveSnap.child("setsWonA").getValue(Int::class.java)
                ?: liveSnap.child("setsA").getValue(Int::class.java)
                ?: liveSnap.child("setA").getValue(Int::class.java) ?: 0
            setsB = liveSnap.child("setsWonB").getValue(Int::class.java)
                ?: liveSnap.child("setsB").getValue(Int::class.java)
                ?: liveSnap.child("setB").getValue(Int::class.java) ?: 0

            if (logoA.isEmpty()) logoA = extractLogo(liveSnap, "logoA", "teamALogo", "myTeamLogo")
            if (logoB.isEmpty()) logoB = extractLogo(liveSnap, "logoB", "teamBLogo", "opponentTeamLogo")

            if (colorA.isEmpty()) colorA = extractString(liveSnap, "teamAColor", "colorA")
            if (colorB.isEmpty()) colorB = extractString(liveSnap, "teamBColor", "colorB")

            val livePlayersA = parsePlayerListSnapshot(liveSnap.child("teamAPlayers"), "A", colorA)
            val livePlayersB = parsePlayerListSnapshot(liveSnap.child("teamBPlayers"), "B", colorB)
            if (livePlayersA.isNotEmpty()) playersA.addAll(livePlayersA)
            if (livePlayersB.isNotEmpty()) playersB.addAll(livePlayersB)
        }

        // Check matchData/formation (Web App Active Lineup Priority)
        if (matchDataSnap.exists()) {
            val formationSnap = matchDataSnap.child("formation")
            if (formationSnap.exists()) {
                if (nameA.isEmpty()) nameA = extractString(formationSnap, "teamAName", "myTeam", "teamA", "benchA")
                if (nameB.isEmpty()) nameB = extractString(formationSnap, "teamBName", "opponentTeam", "teamB", "benchB")

                val formationTokens = formationSnap.child("tokens")
                val formPlayersA: List<PlayerPresentation>
                val formPlayersB: List<PlayerPresentation>
                if (formationTokens.exists()) {
                    hasActiveFormation = true
                    formPlayersA = parseFormationTokens(formationTokens, "A", userSnap, colorA)
                    formPlayersB = parseFormationTokens(formationTokens, "B", userSnap, colorB)
                } else {
                    formPlayersA = parsePlayerListSnapshot(formationSnap.child("playersA"), "A", colorA)
                        .ifEmpty { parsePlayerListSnapshot(formationSnap.child("lineupA"), "A", colorA) }
                        .ifEmpty { parsePlayerListSnapshot(formationSnap.child("teamAPlayers"), "A", colorA) }

                    formPlayersB = parsePlayerListSnapshot(formationSnap.child("playersB"), "B", colorB)
                        .ifEmpty { parsePlayerListSnapshot(formationSnap.child("lineupB"), "B", colorB) }
                        .ifEmpty { parsePlayerListSnapshot(formationSnap.child("teamBPlayers"), "B", colorB) }
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
                for (teamChild in rostersSnap.children) {
                    val key = teamChild.key ?: continue
                    val tName = extractString(teamChild, "teamName", "name", "squadra")
                    if (key.equals(nameA, ignoreCase = true) || tName.equals(nameA, ignoreCase = true) || key.contains("A", ignoreCase = true)) {
                        val rosterPlayersA = parsePlayerListSnapshot(teamChild.child("players"), "A", colorA)
                            .ifEmpty { parsePlayerListSnapshot(teamChild, "A", colorA) }
                        if (rosterPlayersA.isNotEmpty() && playersA.isEmpty()) {
                            playersA.addAll(rosterPlayersA)
                        }
                    } else if (key.equals(nameB, ignoreCase = true) || tName.equals(nameB, ignoreCase = true) || key.contains("B", ignoreCase = true)) {
                        val rosterPlayersB = parsePlayerListSnapshot(teamChild.child("players"), "B", colorB)
                            .ifEmpty { parsePlayerListSnapshot(teamChild, "B", colorB) }
                        if (rosterPlayersB.isNotEmpty() && playersB.isEmpty()) {
                            playersB.addAll(rosterPlayersB)
                        }
                    }
                }

                if (playersA.isEmpty() || playersB.isEmpty()) {
                    val teamKeys = rostersSnap.children.mapNotNull { it.key }
                    if (teamKeys.isNotEmpty() && playersA.isEmpty()) {
                        playersA.addAll(parsePlayerListSnapshot(rostersSnap.child(teamKeys[0]).child("players"), "A", colorA).ifEmpty { parsePlayerListSnapshot(rostersSnap.child(teamKeys[0]), "A", colorA) })
                    }
                    if (teamKeys.size > 1 && playersB.isEmpty()) {
                        playersB.addAll(parsePlayerListSnapshot(rostersSnap.child(teamKeys[1]).child("players"), "B", colorB).ifEmpty { parsePlayerListSnapshot(rostersSnap.child(teamKeys[1]), "B", colorB) })
                    }
                }
            }
        }

        // Strict Default Fallbacks for Team Names
        if (nameA.isEmpty()) nameA = "VPM"
        if (nameB.isEmpty()) nameB = "VHU"
        if (colorA.isEmpty()) colorA = "#0284C7"
        if (colorB.isEmpty()) colorB = "#E11D48"

        // 3. COMPREHENSIVE SERVE / BALL LOGIC (Volleyball Rally Point System):
        val scoreChanged = (lastScoreA >= 0 && lastScoreB >= 0) && (scoreA != lastScoreA || scoreB != lastScoreB)

        if (scoreChanged) {
            // When points are scored during the match, the team that won the rally gets the serve
            if (scoreA > lastScoreA && scoreB == lastScoreB) {
                // Team A scored the point -> Team A serves
                currentServingTeam = "A"
            } else if (scoreB > lastScoreB && scoreA == lastScoreA) {
                // Team B scored the point -> Team B serves
                currentServingTeam = "B"
            } else if (scoreA > lastScoreA && scoreB > lastScoreB) {
                val diffA = scoreA - lastScoreA
                val diffB = scoreB - lastScoreB
                if (diffA > diffB) currentServingTeam = "A"
                else if (diffB > diffA) currentServingTeam = "B"
            } else if (scoreA == 0 && scoreB == 0) {
                // Match or Set reset to 0-0
                val liveExplicit = liveSnap?.let { extractServingString(it, "servingTeam", "serving", "server", "battuta", "palla", "serve") }.orEmpty()
                currentServingTeam = normalizeServingTeam(liveExplicit, nameA, nameB)
            }
        } else {
            // Points did NOT change in this snapshot (e.g. initial app startup, timeout, manual serve toggle from watch/web)
            // Check if the live match node has an explicit live server
            val liveExplicit = liveSnap?.let {
                extractServingString(
                    it,
                    "servingTeam", "serving", "server", "currentServe", "currentServer",
                    "battuta", "battitore", "servizio", "palla", "ball", "turnToServe",
                    "serveTeam", "battutaTeam", "possession", "activeServe", "whoServes"
                )
            }.orEmpty()
            val normalizedLive = normalizeServingTeam(liveExplicit, nameA, nameB)

            if (normalizedLive.isNotEmpty()) {
                currentServingTeam = normalizedLive
            } else if (lastScoreA < 0 || lastScoreB < 0) {
                // Initial startup before any point change
                if (scoreA == 0 && scoreB == 0) {
                    currentServingTeam = ""
                } else if (scoreA > scoreB) {
                    currentServingTeam = "A"
                } else if (scoreB > scoreA) {
                    currentServingTeam = "B"
                }
            }
        }

        lastScoreA = scoreA
        lastScoreB = scoreB
        val serving = currentServingTeam

        Log.i("FirebaseRepo", "Parsed Match State: Team A='$nameA' (${playersA.size} players), Team B='$nameB' (${playersB.size} players), Score: $scoreA - $scoreB, Serve: '$serving'")

        // 4. MASTER LIST OF PRIMARY DATABASE PLAYERS (Excluding AST, BMW, AUD, DET tournament rosters)
        val excludedTeamKeys = setOf("AST", "BMW", "AUD", "DET", "BOS", "CLE", "TOR", "DAL", "CHI", "LAL", "GSW", "MIA", "NYK", "PHX", "BKN", "MIL", "DEN", "OKC", "ORL", "SAC", "IND", "NOP", "UTA", "MEM", "WAS", "POR", "CHO", "ATL", "SAS", "MIN", "HOU", "PHI", "LAC")

        val masterList = mutableListOf<PlayerPresentation>()
        val rootPlayersSnap = userSnap.child("players")
        if (rootPlayersSnap.exists()) {
            masterList.addAll(parsePlayerListSnapshot(rootPlayersSnap, "ALL"))
        }
        val rostersSnapNode = userSnap.child("matchData").child("rosters")
        if (rostersSnapNode.exists()) {
            for (teamChild in rostersSnapNode.children) {
                val teamKey = teamChild.key?.uppercase() ?: "TEAM"
                if (excludedTeamKeys.contains(teamKey)) continue
                val pList = teamChild.child("players").takeIf { it.exists() } ?: teamChild
                masterList.addAll(parsePlayerListSnapshot(pList, teamKey))
            }
        }
        masterList.addAll(playersA)
        masterList.addAll(playersB)

        val uniqueMasterList = masterList
            .filter { player ->
                val nameUpper = player.name.trim().uppercase()
                val idUpper = player.id.uppercase()
                nameUpper.isNotBlank() &&
                !nameUpper.equals("TEAMNAME", ignoreCase = true) &&
                !nameUpper.startsWith("AMICA ") &&
                !nameUpper.startsWith("AMICO ") &&
                excludedTeamKeys.none { key -> idUpper.startsWith("${key}_") || nameUpper.startsWith("$key ") }
            }
            .distinctBy { PhotoMatchingManager.normalizeName(it.name) }
            .sortedBy { it.name }

        _allDatabasePlayers.value = uniqueMasterList

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

    private fun extractServingString(snap: DataSnapshot, vararg keys: String): String {
        for (key in keys) {
            val child = snap.child(key)
            if (!child.exists()) continue
            val strVal = child.getValue(String::class.java)
            if (!strVal.isNullOrBlank()) return strVal.trim()
            val anyVal = child.getValue(Any::class.java)?.toString()?.trim()
            if (!anyVal.isNullOrBlank()) return anyVal
        }
        return ""
    }

    private fun normalizeServingTeam(raw: String, nameA: String, nameB: String): String {
        if (raw.isBlank()) return ""
        val cleaned = raw.trim().lowercase().replace("[_\\-\\s]".toRegex(), "")

        if (cleaned == "a" || cleaned == "teama" || cleaned == "1" || cleaned == "team1" ||
            cleaned == "home" || cleaned == "casa" || cleaned == "myteam" || cleaned == "squadraa") {
            return "A"
        }
        if (cleaned == "b" || cleaned == "teamb" || cleaned == "2" || cleaned == "team2" ||
            cleaned == "away" || cleaned == "ospiti" || cleaned == "guest" || cleaned == "opponentteam" || cleaned == "squadrab") {
            return "B"
        }

        val normNameA = PhotoMatchingManager.normalizeName(nameA)
        val normNameB = PhotoMatchingManager.normalizeName(nameB)
        val normRaw = PhotoMatchingManager.normalizeName(raw)

        if (normNameA.isNotEmpty() && (normRaw == normNameA || (normNameA.length >= 3 && normRaw.contains(normNameA)))) {
            return "A"
        }
        if (normNameB.isNotEmpty() && (normRaw == normNameB || (normNameB.length >= 3 && normRaw.contains(normNameB)))) {
            return "B"
        }

        return ""
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

    private fun parsePlayerListSnapshot(snap: DataSnapshot, teamTag: String, teamColorHex: String = ""): List<PlayerPresentation> {
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

            val role = normalizeRole(extractPlayerRole(child).ifEmpty { "Outside Hitter" })

            val fbPhotoUri = extractPhotoUri(child, teamColorHex, number)
            val localPhotoUri = photoManager.getPhotoUriForPlayer(name, teamColorHex, number)

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
        userSnap: DataSnapshot,
        teamColorHex: String = ""
    ): List<PlayerPresentation> {
        val players = mutableListOf<PlayerPresentation>()
        val expectedTeam = if (teamTag == "A") "team-a" else "team-b"

        val defaultRoles = listOf(
            "Setter",
            "Outside Hitter",
            "Middle Blocker",
            "Opposite",
            "Outside Hitter",
            "Middle Blocker"
        )

        var indexInTeam = 0
        for (token in tokensSnap.children) {
            val tokenTeam = token.child("team").getValue(String::class.java)?.trim()?.lowercase()
            if (tokenTeam != expectedTeam) continue

            val tokenName = token.child("name").getValue(String::class.java)?.trim().orEmpty()
            val tokenNumStr = token.child("num").getValue(Any::class.java)?.toString()
                ?: token.child("number").getValue(Any::class.java)?.toString()
                ?: token.child("numero").getValue(Any::class.java)?.toString()

            val rosterPlayer = findRosterPlayer(userSnap, tokenName, tokenNumStr, indexInTeam, teamTag)
            val fallbackId = token.key ?: (indexInTeam + 1).toString()
            val id = "${teamTag}_$fallbackId"

            val name = tokenName.ifEmpty {
                rosterPlayer?.child("name")?.getValue(String::class.java)?.trim().orEmpty()
            }.ifEmpty { "Player ${indexInTeam + 1}" }

            val number = rosterPlayer?.child("num")?.getValue(Any::class.java)?.toString()
                ?: rosterPlayer?.child("number")?.getValue(Any::class.java)?.toString()
                ?: rosterPlayer?.child("numero")?.getValue(Any::class.java)?.toString()
                ?: tokenNumStr
                ?: (indexInTeam + 1).toString()

            val rosterRole = rosterPlayer?.let { extractPlayerRole(it) }.orEmpty()
            val tokenRole = token.child("role").getValue(String::class.java)?.trim().orEmpty()

            val rawRole = when {
                rosterRole.isNotBlank() && rosterRole != "Universal" -> rosterRole
                tokenRole.isNotBlank() && tokenRole != "Universal" -> tokenRole
                rosterRole.isNotBlank() -> rosterRole
                tokenRole.isNotBlank() -> tokenRole
                else -> ""
            }

            val fallbackRole = if (tokenRole.equals("Libero", ignoreCase = true) || rosterRole.equals("Libero", ignoreCase = true)) {
                "Libero"
            } else {
                defaultRoles.getOrElse(indexInTeam % defaultRoles.size) { "Outside Hitter" }
            }

            val role = normalizeRole(rawRole.ifEmpty { fallbackRole })
            indexInTeam++

            val firebasePhotoUri = rosterPlayer?.let { extractPhotoUri(it, teamColorHex, number) }.orEmpty().ifEmpty { extractPhotoUri(token, teamColorHex, number) }
            val localPhotoUri = photoManager.getPhotoUriForPlayer(name, teamColorHex, number)
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

    private fun findRosterPlayer(
        userSnap: DataSnapshot,
        name: String,
        numberStr: String?,
        index: Int,
        teamTag: String
    ): DataSnapshot? {
        if (!userSnap.exists()) return null

        // 1. Search by name or alias first
        if (name.isNotBlank()) {
            val byName = searchRosterByName(userSnap, name)
            if (byName != null) return byName
        }

        // 2. Search by number
        if (!numberStr.isNullOrBlank()) {
            val byNum = searchRosterByNumber(userSnap, numberStr, teamTag)
            if (byNum != null) return byNum
        }

        // 3. Search by index in team roster
        return searchRosterByIndex(userSnap, index, teamTag)
    }

    private fun searchRosterByName(userSnap: DataSnapshot, name: String): DataSnapshot? {
        val playersSnap = userSnap.child("players")
        if (playersSnap.exists()) {
            val match = searchChildForPlayerName(playersSnap, name)
            if (match != null) return match
        }

        val rostersSnap = userSnap.child("matchData").child("rosters")
        if (rostersSnap.exists()) {
            val match = searchChildForPlayerName(rostersSnap, name)
            if (match != null) return match
        }

        val matchDataSnap = userSnap.child("matchData")
        for (liveKey in listOf("liveMatchProgress_index", "liveMatchProgress_local", "liveMatchProgress", "liveMatch")) {
            val liveSnap = matchDataSnap.child(liveKey)
            if (liveSnap.exists()) {
                val match = searchChildForPlayerName(liveSnap, name)
                if (match != null) return match
            }
        }

        return searchRecursiveExcludingTokens(userSnap, name)
    }

    private fun searchRosterByNumber(userSnap: DataSnapshot, targetNum: String, teamTag: String): DataSnapshot? {
        val targetDigits = targetNum.trim().replace("[^0-9]".toRegex(), "")
        if (targetDigits.isBlank()) return null
        val targetInt = targetDigits.toIntOrNull() ?: -1

        fun matchNum(child: DataSnapshot): Boolean {
            if (isTokenNode(child)) return false
            val rawNumVal = child.child("num").getValue(Any::class.java)?.toString()
                ?: child.child("number").getValue(Any::class.java)?.toString()
                ?: child.child("numero").getValue(Any::class.java)?.toString()
                ?: child.child("jerseyNumber").getValue(Any::class.java)?.toString()
            val childDigits = rawNumVal?.replace("[^0-9]".toRegex(), "")
            if (childDigits != null && (childDigits == targetDigits || (targetInt > 0 && childDigits.toIntOrNull() == targetInt))) {
                return true
            }
            return false
        }

        val playersSnap = userSnap.child("players")
        if (playersSnap.exists()) {
            for (child in playersSnap.children) {
                if (matchNum(child)) return child
            }
        }

        val rostersSnap = userSnap.child("matchData").child("rosters")
        if (rostersSnap.exists()) {
            for (teamChild in rostersSnap.children) {
                val playersList = teamChild.child("players").takeIf { it.exists() } ?: teamChild
                for (child in playersList.children) {
                    if (matchNum(child)) return child
                }
            }
        }
        return null
    }

    private fun searchRosterByIndex(userSnap: DataSnapshot, index: Int, teamTag: String): DataSnapshot? {
        val playersSnap = userSnap.child("players")
        if (playersSnap.exists() && index >= 0) {
            val childrenList = playersSnap.children.filter { !isTokenNode(it) }
            if (index < childrenList.size) {
                return childrenList[index]
            }
        }

        val rostersSnap = userSnap.child("matchData").child("rosters")
        if (rostersSnap.exists()) {
            val expectedTeam = if (teamTag == "A") "A" else "B"
            for (teamChild in rostersSnap.children) {
                val key = teamChild.key ?: ""
                val tName = extractString(teamChild, "teamName", "name", "squadra")
                if (key.contains(expectedTeam, ignoreCase = true) || tName.contains(expectedTeam, ignoreCase = true)) {
                    val playersList = teamChild.child("players").takeIf { it.exists() } ?: teamChild
                    val childrenList = playersList.children.filter { !isTokenNode(it) }
                    if (index >= 0 && index < childrenList.size) {
                        return childrenList[index]
                    }
                }
            }
        }
        return null
    }

    private fun searchChildForPlayerName(snap: DataSnapshot, targetName: String): DataSnapshot? {
        val targetAliases = PhotoMatchingManager.extractNameAliases(targetName)
        val targetNorm = PhotoMatchingManager.normalizeName(targetName)

        for (child in snap.children) {
            if (isTokenNode(child)) continue
            for (key in listOf("name", "displayName", "playerName", "nome")) {
                val pName = child.child(key).getValue(String::class.java)?.trim()
                if (pName != null) {
                    if (pName.equals(targetName, ignoreCase = true)) return child
                    val pNorm = PhotoMatchingManager.normalizeName(pName)
                    if (pNorm.isNotEmpty() && pNorm == targetNorm) return child
                    val childAliases = PhotoMatchingManager.extractNameAliases(pName)
                    if (childAliases.any { it.length >= 3 && targetAliases.contains(it) }) {
                        return child
                    }
                }
            }
            val subResult = searchChildForPlayerName(child, targetName)
            if (subResult != null) return subResult
        }
        return null
    }

    private fun searchRecursiveExcludingTokens(snap: DataSnapshot, targetName: String): DataSnapshot? {
        val targetAliases = PhotoMatchingManager.extractNameAliases(targetName)
        val targetNorm = PhotoMatchingManager.normalizeName(targetName)

        for (child in snap.children) {
            val keyName = child.key
            if (keyName == "formation" || keyName == "tokens" || isTokenNode(child)) continue
            for (key in listOf("name", "displayName", "playerName", "nome")) {
                val pName = child.child(key).getValue(String::class.java)?.trim()
                if (pName != null) {
                    if (pName.equals(targetName, ignoreCase = true)) return child
                    val pNorm = PhotoMatchingManager.normalizeName(pName)
                    if (pNorm.isNotEmpty() && pNorm == targetNorm) return child
                    val childAliases = PhotoMatchingManager.extractNameAliases(pName)
                    if (childAliases.any { it.length >= 3 && targetAliases.contains(it) }) {
                        return child
                    }
                }
            }
            val subResult = searchRecursiveExcludingTokens(child, targetName)
            if (subResult != null) return subResult
        }
        return null
    }

    private fun isTokenNode(snap: DataSnapshot): Boolean {
        return snap.hasChild("parent") || (snap.hasChild("top") && snap.hasChild("left"))
    }

    private fun normalizeRole(role: String): String {
        val trimmed = role.trim()
        if (trimmed.isEmpty()) return "Outside Hitter"
        return when {
            trimmed.equals("Palleggiatore", ignoreCase = true) || trimmed.equals("P", ignoreCase = true) || trimmed.equals("Setter", ignoreCase = true) -> "Setter"
            trimmed.equals("Centrale", ignoreCase = true) || trimmed.equals("C", ignoreCase = true) || trimmed.equals("Middle", ignoreCase = true) || trimmed.equals("Middle Blocker", ignoreCase = true) -> "Middle Blocker"
            trimmed.equals("Schiacciatore", ignoreCase = true) || trimmed.equals("S", ignoreCase = true) || trimmed.equals("Outside", ignoreCase = true) || trimmed.equals("Outside Hitter", ignoreCase = true) || trimmed.equals("Banda", ignoreCase = true) -> "Outside Hitter"
            trimmed.equals("Opposto", ignoreCase = true) || trimmed.equals("O", ignoreCase = true) || trimmed.equals("Opposite", ignoreCase = true) || trimmed.equals("Opposite Hitter", ignoreCase = true) -> "Opposite"
            trimmed.equals("Libero", ignoreCase = true) || trimmed.equals("L", ignoreCase = true) -> "Libero"
            trimmed.equals("Universal", ignoreCase = true) || trimmed.equals("Univ", ignoreCase = true) || trimmed.equals("U", ignoreCase = true) -> "Universal"
            else -> trimmed
        }
    }

    private fun extractPlayerRole(playerSnap: DataSnapshot): String {
        return listOf("role", "ruolo", "position", "posizione", "playerRole", "roleName")
            .asSequence()
            .mapNotNull { key -> playerSnap.child(key).getValue(String::class.java)?.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: ""
    }

    private fun extractPhotoUri(playerSnap: DataSnapshot, teamColorHex: String = "", numberStr: String = ""): String {
        val colorType = photoManager.determineColorType(teamColorHex)

        if (colorType == "red") {
            val redPhoto = listOf("photoRed", "photoRossa", "fotoRossa", "redPhoto", "imageRed")
                .asSequence()
                .mapNotNull { key -> playerSnap.child(key).getValue(String::class.java)?.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: playerSnap.child("photos").child("red").getValue(String::class.java)?.trim()
                ?: playerSnap.child("photos").child("rossa").getValue(String::class.java)?.trim()
            if (!redPhoto.isNullOrBlank()) return redPhoto
        } else if (colorType == "blue") {
            val bluePhoto = listOf("photoBlue", "photoAzzurra", "fotoAzzurra", "bluePhoto", "imageBlue")
                .asSequence()
                .mapNotNull { key -> playerSnap.child(key).getValue(String::class.java)?.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: playerSnap.child("photos").child("blue").getValue(String::class.java)?.trim()
                ?: playerSnap.child("photos").child("azzurra").getValue(String::class.java)?.trim()
            if (!bluePhoto.isNullOrBlank()) return bluePhoto
        }

        val directUri = playerSnap.child("photoUrl").getValue(String::class.java)
            ?: playerSnap.child("photo").getValue(String::class.java)
            ?: playerSnap.child("foto").getValue(String::class.java)
            ?: playerSnap.child("image").getValue(String::class.java)
            ?: playerSnap.child("picture").getValue(String::class.java)
            ?: playerSnap.child("url").getValue(String::class.java)
            ?: playerSnap.child("uri").getValue(String::class.java)
            ?: ""

        if (directUri.isNotBlank()) return directUri

        val pName = playerSnap.child("name").getValue(String::class.java)
            ?: playerSnap.child("nome").getValue(String::class.java)
            ?: playerSnap.child("displayName").getValue(String::class.java).orEmpty()

        val aliases = PhotoMatchingManager.extractNameAliases(pName)
        val photoMappingsSnap = lastUserSnap?.child("photoMappings")
        if (photoMappingsSnap != null && photoMappingsSnap.exists()) {
            for (alias in aliases) {
                if (colorType == "red") {
                    val redMapped = photoMappingsSnap.child("${alias}_red").getValue(String::class.java)
                        ?: photoMappingsSnap.child("${alias}_rossa").getValue(String::class.java)
                    if (!redMapped.isNullOrEmpty()) return redMapped
                } else if (colorType == "blue") {
                    val blueMapped = photoMappingsSnap.child("${alias}_blue").getValue(String::class.java)
                        ?: photoMappingsSnap.child("${alias}_azzurra").getValue(String::class.java)
                    if (!blueMapped.isNullOrEmpty()) return blueMapped
                }
                val defaultMapped = photoMappingsSnap.child(alias).getValue(String::class.java)
                if (!defaultMapped.isNullOrEmpty()) return defaultMapped
            }

            // Also check jersey number in Firebase photoMappings
            val numTrimmed = numberStr.trim().replace("[^0-9]".toRegex(), "")
            if (numTrimmed.isNotBlank()) {
                val numKey = "num_$numTrimmed"
                if (colorType == "red") {
                    val redNumMapped = photoMappingsSnap.child("${numKey}_red").getValue(String::class.java)
                        ?: photoMappingsSnap.child("${numKey}_rossa").getValue(String::class.java)
                    if (!redNumMapped.isNullOrEmpty()) return redNumMapped
                } else if (colorType == "blue") {
                    val blueNumMapped = photoMappingsSnap.child("${numKey}_blue").getValue(String::class.java)
                        ?: photoMappingsSnap.child("${numKey}_azzurra").getValue(String::class.java)
                    if (!blueNumMapped.isNullOrEmpty()) return blueNumMapped
                }
                val defaultNumMapped = photoMappingsSnap.child(numKey).getValue(String::class.java)
                if (!defaultNumMapped.isNullOrEmpty()) return defaultNumMapped
            }
        }

        return ""
    }

    fun savePhotoMappingToFirebase(uid: String, name: String, photoUri: String) {
        savePhotoVariantToFirebase(uid, name, photoUri, "")
    }

    fun savePhotoVariantToFirebase(uid: String, name: String, photoUri: String, variant: String = "") {
        val normKey = PhotoMatchingManager.normalizeName(name)
        if (uid.isNotBlank() && normKey.isNotBlank() && photoUri.isNotBlank()) {
            val key = if (variant.isNotBlank()) "photo${variant.lowercase().replaceFirstChar { it.uppercase() }}" else "photoUrl"
            if (variant.isNotBlank()) {
                database.getReference("users/$uid/photoMappings/${normKey}_${variant.lowercase()}").setValue(photoUri)
            }
            database.getReference("users/$uid/photoMappings/$normKey").setValue(photoUri)

            val playersRef = database.getReference("users/$uid/players")
            playersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        for (child in snapshot.children) {
                            val pName = child.child("name").getValue(String::class.java)
                                ?: child.child("nome").getValue(String::class.java) ?: ""
                            if (PhotoMatchingManager.normalizeName(pName) == normKey) {
                                child.ref.child(key).setValue(photoUri)
                                if (variant.isEmpty()) {
                                    child.ref.child("photoUrl").setValue(photoUri)
                                    child.ref.child("photo").setValue(photoUri)
                                }
                            }
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
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
