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

        // 1. Check liveMatchProgress nodes
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
                nameA = extractString(liveSnap, "teamAName", "myTeam", "teamA", "team_a_name", "squadraA")
                nameB = extractString(liveSnap, "teamBName", "opponentTeam", "teamB", "team_b_name", "squadraB")

                scoreA = liveSnap.child("scoreA").getValue(Int::class.java) ?: 0
                scoreB = liveSnap.child("scoreB").getValue(Int::class.java) ?: 0
                setsA = liveSnap.child("setsWonA").getValue(Int::class.java)
                    ?: liveSnap.child("setsA").getValue(Int::class.java) ?: 0
                setsB = liveSnap.child("setsWonB").getValue(Int::class.java)
                    ?: liveSnap.child("setsB").getValue(Int::class.java) ?: 0
                serving = liveSnap.child("servingTeam").getValue(String::class.java) ?: ""

                logoA = extractLogo(liveSnap, "logoA", "teamALogo", "myTeamLogo", "logo")
                logoB = extractLogo(liveSnap, "logoB", "teamBLogo", "opponentTeamLogo", "logo")

                colorA = extractString(liveSnap, "teamAColor", "colorA")
                colorB = extractString(liveSnap, "teamBColor", "colorB")

                playersA.addAll(parsePlayerListSnapshot(liveSnap.child("teamAPlayers"), "A"))
                playersB.addAll(parsePlayerListSnapshot(liveSnap.child("teamBPlayers"), "B"))
            }

            // Check matchData/formation
            val formationSnap = matchDataSnap.child("formation")
            if (formationSnap.exists()) {
                val fNameA = extractString(formationSnap, "teamAName", "myTeam", "teamA")
                val fNameB = extractString(formationSnap, "teamBName", "opponentTeam", "teamB")
                if (fNameA.isNotEmpty()) nameA = fNameA
                if (fNameB.isNotEmpty()) nameB = fNameB

                if (playersA.isEmpty()) playersA.addAll(parsePlayerListSnapshot(formationSnap.child("playersA"), "A"))
                if (playersB.isEmpty()) playersB.addAll(parsePlayerListSnapshot(formationSnap.child("playersB"), "B"))
            }

            // Check matchData/rosters
            val rostersSnap = matchDataSnap.child("rosters")
            if (rostersSnap.exists()) {
                val teamKeys = rostersSnap.children.mapNotNull { it.key }
                if (teamKeys.isNotEmpty()) {
                    val rosterA = rostersSnap.child(teamKeys[0])
                    val rNameA = extractString(rosterA, "teamName", "name", "squadra")
                    if (rNameA.isNotEmpty()) nameA = rNameA
                    if (playersA.isEmpty()) playersA.addAll(parsePlayerListSnapshot(rosterA, "A"))
                }
                if (teamKeys.size > 1) {
                    val rosterB = rostersSnap.child(teamKeys[1])
                    val rNameB = extractString(rosterB, "teamName", "name", "squadra")
                    if (rNameB.isNotEmpty()) nameB = rNameB
                    if (playersB.isEmpty()) playersB.addAll(parsePlayerListSnapshot(rosterB, "B"))
                }
            }

            // Check matchData/settings
            val matchSettingsSnap = matchDataSnap.child("settings")
            if (matchSettingsSnap.exists()) {
                val msNameA = extractString(matchSettingsSnap, "myTeam", "teamAName", "teamA", "nomeA", "squadraA")
                val msNameB = extractString(matchSettingsSnap, "opponentTeam", "teamBName", "teamB", "nomeB", "squadraB")
                if (msNameA.isNotEmpty()) nameA = msNameA
                if (msNameB.isNotEmpty()) nameB = msNameB
                if (logoA.isEmpty()) logoA = extractLogo(matchSettingsSnap, "logoA", "logoTeamA", "myTeamLogo")
                if (logoB.isEmpty()) logoB = extractLogo(matchSettingsSnap, "logoB", "logoTeamB", "opponentTeamLogo")
            }
        }

        // 2. Check Tournament Data (if current match is in a tournament round)
        val tourneySnap = userSnap.child("tournamentData").child("rounds")
        if (tourneySnap.exists()) {
            for (roundChild in tourneySnap.children) {
                for (matchChild in roundChild.children) {
                    val t1 = matchChild.child("team1")
                    val t2 = matchChild.child("team2")
                    val t1Name = extractString(t1, "name", "teamName")
                    val t2Name = extractString(t2, "name", "teamName")
                    if (t1Name.isNotEmpty() && t2Name.isNotEmpty()) {
                        nameA = t1Name
                        nameB = t2Name
                        val t1Color = extractString(t1, "color")
                        val t2Color = extractString(t2, "color")
                        if (t1Color.isNotEmpty()) colorA = t1Color
                        if (t2Color.isNotEmpty()) colorB = t2Color
                        break
                    }
                }
            }
        }

        // 3. Root Settings node
        val settingsSnap = userSnap.child("settings")
        if (settingsSnap.exists()) {
            val sNameA = extractString(settingsSnap, "myTeam", "teamAName", "teamA", "myTeamName", "team_a_name", "nomeA", "squadraA")
            val sNameB = extractString(settingsSnap, "opponentTeam", "teamBName", "teamB", "opponentTeamName", "team_b_name", "nomeB", "squadraB")
            if (nameA.isEmpty() && sNameA.isNotEmpty()) nameA = sNameA
            if (nameB.isEmpty() && sNameB.isNotEmpty()) nameB = sNameB

            if (logoA.isEmpty()) logoA = extractLogo(settingsSnap, "logoA", "logoTeamA", "myTeamLogo", "imageA")
            if (logoB.isEmpty()) logoB = extractLogo(settingsSnap, "logoB", "logoTeamB", "opponentTeamLogo", "imageB")

            if (colorA.isEmpty()) colorA = extractString(settingsSnap, "colorA", "primaryColorA", "teamAColor")
            if (colorB.isEmpty()) colorB = extractString(settingsSnap, "colorB", "primaryColorB", "teamBColor")
        }

        // Defaults if missing
        if (nameA.isEmpty()) nameA = "VPM"
        if (nameB.isEmpty()) nameB = "VHU"
        if (colorA.isEmpty()) colorA = "#0284C7"
        if (colorB.isEmpty()) colorB = "#E11D48"

        Log.i("FirebaseRepo", "Parsed Match State: Team A='$nameA' (${playersA.size} players), Team B='$nameB' (${playersB.size} players), Score: $scoreA - $scoreB")

        val current = _presentationData.value
        _presentationData.value = current.copy(
            teamA = current.teamA.copy(
                name = nameA,
                logoUrl = logoA.ifEmpty { current.teamA.logoUrl },
                primaryColorHex = colorA,
                players = if (playersA.isNotEmpty()) playersA else current.teamA.players
            ),
            teamB = current.teamB.copy(
                name = nameB,
                logoUrl = logoB.ifEmpty { current.teamB.logoUrl },
                primaryColorHex = colorB,
                players = if (playersB.isNotEmpty()) playersB else current.teamB.players
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
                ?: "Player $key"

            val number = child.child("num").getValue(Any::class.java)?.toString()
                ?: child.child("number").getValue(Any::class.java)?.toString()
                ?: child.child("numero").getValue(Any::class.java)?.toString()
                ?: key

            val role = child.child("role").getValue(String::class.java)
                ?: child.child("ruolo").getValue(String::class.java)
                ?: "Outside Hitter"

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

            val localPhotoUri = photoManager.getPhotoUriForPlayer(id)
                ?: photoManager.getPhotoUriForPlayer(name)
                ?: photoManager.getPhotoUriForPlayer(number)

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
