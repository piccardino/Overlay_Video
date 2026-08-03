package com.example.overlayvideoapp.data

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FirebaseMatchRepository {

    private val database = FirebaseDatabase.getInstance("https://volley-hub-c90ca-default-rtdb.europe-west1.firebasedatabase.app")
    private var liveMatchRef: com.google.firebase.database.DatabaseReference? = null
    private var formationRef: com.google.firebase.database.DatabaseReference? = null
    private var settingsRef: com.google.firebase.database.DatabaseReference? = null

    private val _matchData = MutableStateFlow<MatchData?>(null)
    val matchData: StateFlow<MatchData?> = _matchData.asStateFlow()

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private var liveMatchListener: ValueEventListener? = null
    private var formationListener: ValueEventListener? = null
    private var settingsListener: ValueEventListener? = null
    
    init {
        // Monitor connection state
        val connectedRef = database.getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                _connectionState.value = connected
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseRepo", "Listener was cancelled")
            }
        })
    }

    fun startObserving(uid: String, matchKey: String, source: String = "local") {
        if (liveMatchListener != null) return

        val liveMatchPath = if (matchKey.isNotEmpty()) {
            "users/$uid/matchData/liveMatchProgress_$source/$matchKey"
        } else {
            "users/$uid/matchData/liveMatchProgress_$source"
        }
        liveMatchRef = database.getReference(liveMatchPath)

        liveMatchListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val scoreA = snapshot.child("scoreA").getValue(Int::class.java) ?: 0
                    val scoreB = snapshot.child("scoreB").getValue(Int::class.java) ?: 0
                    val setsA = snapshot.child("setsWonA").getValue(Int::class.java) ?: 0
                    val setsB = snapshot.child("setsWonB").getValue(Int::class.java) ?: 0
                    
                    val teamNameA = snapshot.child("teamAName").getValue(String::class.java)
                        ?: snapshot.child("myTeam").getValue(String::class.java)
                        ?: snapshot.child("teamA").getValue(String::class.java)
                        ?: snapshot.child("benchA").getValue(String::class.java)

                    val teamNameB = snapshot.child("teamBName").getValue(String::class.java)
                        ?: snapshot.child("opponentTeam").getValue(String::class.java)
                        ?: snapshot.child("teamB").getValue(String::class.java)
                        ?: snapshot.child("benchB").getValue(String::class.java)

                    val current = _matchData.value ?: MatchData()
                    
                    var finalNameA = current.team_name_a
                    if (!teamNameA.isNullOrEmpty() && teamNameA != "TEAM A" && teamNameA != "Team A") {
                        finalNameA = teamNameA
                    } else if (finalNameA.isEmpty() || finalNameA == "TEAM A" || finalNameA == "Team A") {
                        if (!teamNameA.isNullOrEmpty()) finalNameA = teamNameA
                    }

                    var finalNameB = current.team_name_b
                    if (!teamNameB.isNullOrEmpty() && teamNameB != "TEAM B" && teamNameB != "Team B") {
                        finalNameB = teamNameB
                    } else if (finalNameB.isEmpty() || finalNameB == "TEAM B" || finalNameB == "Team B") {
                        if (!teamNameB.isNullOrEmpty()) finalNameB = teamNameB
                    }

                    _matchData.value = current.copy(
                        score_team_a = scoreA,
                        score_team_b = scoreB,
                        sets_won_a = setsA,
                        sets_won_b = setsB,
                        team_name_a = finalNameA,
                        team_name_b = finalNameB
                    )
                } catch (e: Exception) {
                    Log.e("FirebaseRepo", "Error parsing liveMatch", e)
                }
            }
            override fun onCancelled(error: DatabaseError) { }
        }
        liveMatchRef?.addValueEventListener(liveMatchListener!!)

        val formationPath = if (matchKey.isNotEmpty()) {
            "users/$uid/matchData/formation/$matchKey"
        } else {
            "users/$uid/matchData/formation"
        }
        formationRef = database.getReference(formationPath)

        formationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val formNameA = snapshot.child("teamAName").getValue(String::class.java)
                        ?: snapshot.child("myTeam").getValue(String::class.java)
                        ?: snapshot.child("benchA").getValue(String::class.java)

                    val formNameB = snapshot.child("teamBName").getValue(String::class.java)
                        ?: snapshot.child("opponentTeam").getValue(String::class.java)
                        ?: snapshot.child("benchB").getValue(String::class.java)
                    
                    val current = _matchData.value ?: MatchData()
                    var finalNameA = current.team_name_a
                    if (!formNameA.isNullOrEmpty() && formNameA != "TEAM A" && formNameA != "Team A") {
                        finalNameA = formNameA
                    } else if (finalNameA.isEmpty() || finalNameA == "TEAM A" || finalNameA == "Team A") {
                        if (!formNameA.isNullOrEmpty()) finalNameA = formNameA
                    }

                    var finalNameB = current.team_name_b
                    if (!formNameB.isNullOrEmpty() && formNameB != "TEAM B" && formNameB != "Team B") {
                        finalNameB = formNameB
                    } else if (finalNameB.isEmpty() || finalNameB == "TEAM B" || finalNameB == "Team B") {
                        if (!formNameB.isNullOrEmpty()) finalNameB = formNameB
                    }
                    
                    var scoreA = current.score_team_a
                    var scoreB = current.score_team_b
                    val formScoreA = snapshot.child("scoreA").getValue(Int::class.java)
                    val formScoreB = snapshot.child("scoreB").getValue(Int::class.java)
                    if (formScoreA != null && formScoreA > scoreA) scoreA = formScoreA
                    if (formScoreB != null && formScoreB > scoreB) scoreB = formScoreB

                    _matchData.value = current.copy(
                        team_name_a = finalNameA,
                        team_name_b = finalNameB,
                        score_team_a = scoreA,
                        score_team_b = scoreB
                    )
                } catch (e: Exception) {
                    Log.e("FirebaseRepo", "Error parsing formation", e)
                }
            }

            override fun onCancelled(error: DatabaseError) { }
        }
        formationRef?.addValueEventListener(formationListener!!)

        val settingsPath = "users/$uid/settings"
        settingsRef = database.getReference(settingsPath)
        
        settingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val logoA = snapshot.child("logoA").getValue(String::class.java) ?: ""
                    val logoB = snapshot.child("logoB").getValue(String::class.java) ?: ""
                    val setNumA = snapshot.child("myTeam").getValue(String::class.java)
                        ?: snapshot.child("teamAName").getValue(String::class.java)
                    val setNumB = snapshot.child("opponentTeam").getValue(String::class.java)
                        ?: snapshot.child("teamBName").getValue(String::class.java)

                    val current = _matchData.value ?: MatchData()
                    var finalNameA = current.team_name_a
                    if (!setNumA.isNullOrEmpty() && setNumA != "TEAM A" && setNumA != "Team A") {
                        finalNameA = setNumA
                    } else if (finalNameA.isEmpty() || finalNameA == "TEAM A" || finalNameA == "Team A") {
                        if (!setNumA.isNullOrEmpty()) finalNameA = setNumA
                    }

                    var finalNameB = current.team_name_b
                    if (!setNumB.isNullOrEmpty() && setNumB != "TEAM B" && setNumB != "Team B") {
                        finalNameB = setNumB
                    } else if (finalNameB.isEmpty() || finalNameB == "TEAM B" || finalNameB == "Team B") {
                        if (!setNumB.isNullOrEmpty()) finalNameB = setNumB
                    }

                    _matchData.value = current.copy(
                        logo_url_a = logoA,
                        logo_url_b = logoB,
                        team_name_a = finalNameA,
                        team_name_b = finalNameB
                    )
                } catch (e: Exception) {
                    Log.e("FirebaseRepo", "Error parsing settings", e)
                }
            }

            override fun onCancelled(error: DatabaseError) { }
        }
        settingsRef?.addValueEventListener(settingsListener!!)
    }

    fun stopObserving() {
        liveMatchListener?.let {
            liveMatchRef?.removeEventListener(it)
            liveMatchListener = null
        }
        formationListener?.let {
            formationRef?.removeEventListener(it)
            formationListener = null
        }
        settingsListener?.let {
            settingsRef?.removeEventListener(it)
            settingsListener = null
        }
    }
}
