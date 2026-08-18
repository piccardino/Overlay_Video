package com.example.vhpmatchpresentation

import com.example.vhpmatchpresentation.data.PhotoMatchingManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ServeLogicTest {

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

    @Test
    fun testServeNormalizationVariants() {
        assertEquals("A", normalizeServingTeam("1", "VPM", "VHU"))
        assertEquals("B", normalizeServingTeam("2", "VPM", "VHU"))
        assertEquals("A", normalizeServingTeam("teamA", "VPM", "VHU"))
        assertEquals("B", normalizeServingTeam("teamB", "VPM", "VHU"))
        assertEquals("A", normalizeServingTeam("team-a", "VPM", "VHU"))
        assertEquals("B", normalizeServingTeam("team_b", "VPM", "VHU"))
        assertEquals("A", normalizeServingTeam("home", "VPM", "VHU"))
        assertEquals("B", normalizeServingTeam("away", "VPM", "VHU"))
        assertEquals("A", normalizeServingTeam("casa", "VPM", "VHU"))
        assertEquals("B", normalizeServingTeam("ospiti", "VPM", "VHU"))
        assertEquals("A", normalizeServingTeam("VPM", "VPM", "VHU"))
        assertEquals("B", normalizeServingTeam("VHU", "VPM", "VHU"))
    }
}
