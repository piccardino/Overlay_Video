package com.example.overlayvideoapp.data

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class MatchData(
    val score_team_a: Int = 0,
    val score_team_b: Int = 0,
    val team_name_a: String = "",
    val team_name_b: String = "",
    val logo_url_a: String = "",
    val logo_url_b: String = "",
    val flag_url_a: String = "",
    val flag_url_b: String = "",
    val sets_won_a: Int = 0,
    val sets_won_b: Int = 0,
    val current_set: Int = 1
)
