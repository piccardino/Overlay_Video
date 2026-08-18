package com.example.vhpmatchpresentation

import com.example.vhpmatchpresentation.data.PhotoMatchingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMatchingManagerTest {

    @Test
    fun testNameNormalizationWithAccentsAndPunctuation() {
        assertEquals("marcorossi", PhotoMatchingManager.normalizeName("Marco-Rossi.jpg"))
        assertEquals("damicoluca", PhotoMatchingManager.normalizeName("D'Amico_Luca.PNG"))
        assertEquals("nicolobarella", PhotoMatchingManager.normalizeName("Nicolò Barella"))
        assertEquals("paolomaldini", PhotoMatchingManager.normalizeName("  PAOLO   MALDINi.webp "))
    }

    @Test
    fun testEmptyAndBlankInputs() {
        assertEquals("", PhotoMatchingManager.normalizeName(""))
        assertEquals("", PhotoMatchingManager.normalizeName("   "))
    }

    @Test
    fun testExtractNameAliases() {
        val aliases1 = PhotoMatchingManager.extractNameAliases("Mario Rossi")
        assertTrue(aliases1.contains("mariorossi"))
        assertTrue(aliases1.contains("rossi"))
        assertTrue(aliases1.contains("mrossi"))
        assertTrue(aliases1.contains("mario"))

        val aliases2 = PhotoMatchingManager.extractNameAliases("M. Rossi")
        assertTrue(aliases2.contains("mrossi"))
        assertTrue(aliases2.contains("rossi"))

        val aliases3 = PhotoMatchingManager.extractNameAliases("Simone Giannelli")
        assertTrue(aliases3.contains("simonegiannelli"))
        assertTrue(aliases3.contains("giannelli"))
        assertTrue(aliases3.contains("sgiannelli"))
    }
}
