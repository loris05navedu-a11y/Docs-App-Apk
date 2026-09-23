package com.docssuite.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueChildNameTest {

    @Test
    fun `un nom absent du dossier est garde tel quel`() {
        assertEquals("photo.jpg", uniqueChildName(setOf("autre.txt"), "photo.jpg"))
    }

    @Test
    fun `un nom deja present est suffixe`() {
        assertEquals("photo (2).jpg", uniqueChildName(setOf("photo.jpg"), "photo.jpg"))
    }

    @Test
    fun `le premier suffixe libre est choisi`() {
        val existing = setOf("photo.jpg", "photo (2).jpg", "photo (3).jpg")
        assertEquals("photo (4).jpg", uniqueChildName(existing, "photo.jpg"))
    }

    @Test
    fun `un nom sans extension se suffixe quand meme`() {
        assertEquals("README (2)", uniqueChildName(setOf("README"), "README"))
    }
}
