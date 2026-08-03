package com.example.vhpmatchpresentation

import com.example.vhpmatchpresentation.data.PhotoMatchingManager
import org.junit.Assert.assertEquals
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
}
