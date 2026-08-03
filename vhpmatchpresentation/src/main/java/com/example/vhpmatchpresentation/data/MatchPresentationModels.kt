package com.example.vhpmatchpresentation.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlayerStats(
    val attack: Int = 75,
    val block: Int = 70,
    val serve: Int = 80,
    val receive: Int = 68,
    val defense: Int = 72,
    val set: Int = 65
) : Parcelable

@Parcelize
data class PlayerPresentation(
    val id: String,
    val name: String,
    val displayName: String = name,
    val number: String = "",
    val role: String = "Outside Hitter",
    val photoUri: String? = null,
    val stats: PlayerStats = PlayerStats()
) : Parcelable

@Parcelize
data class TeamPresentation(
    val name: String = "TEAM",
    val logoUrl: String = "",
    val primaryColorHex: String = "#1E3A8A",
    val players: List<PlayerPresentation> = emptyList()
) : Parcelable

@Parcelize
data class MatchPresentationData(
    val teamA: TeamPresentation = TeamPresentation("TEAM A", "", "#0284C7"),
    val teamB: TeamPresentation = TeamPresentation("TEAM B", "", "#E11D48"),
    val scoreA: Int = 0,
    val scoreB: Int = 0,
    val setsA: Int = 0,
    val setsB: Int = 0,
    val servingTeam: String = ""
) : Parcelable
