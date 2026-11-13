package dev.voxkey.bench

import org.junit.Assert.assertEquals
import org.junit.Test

class TextMergeTest {

    @Test
    fun sansOverlapConcatene() {
        assertEquals("bonjour le monde comment ça va", mergeTail("bonjour le monde", "comment ça va"))
    }

    @Test
    fun overlapDeuxMots() {
        assertEquals("il fait beau aujourd'hui", mergeTail("il fait beau", "fait beau aujourd'hui"))
    }

    @Test
    fun overlapAvecPonctuationGardeLeTexteRecent() {
        assertEquals(
            "nous allons au marché, puis au parc",
            mergeTail("nous allons au marché", "au marché, puis au parc")
        )
    }

    @Test
    fun fenetreVideRendCommitted() {
        assertEquals("déjà validé", mergeTail("déjà validé", ""))
        assertEquals("déjà validé", mergeTail("déjà validé", "   "))
    }

    @Test
    fun committedVideRendLaFenetre() {
        assertEquals("Bonjour.", mergeTail("", "Bonjour."))
    }

    @Test
    fun overlapTotalRendCommitted() {
        assertEquals("un deux trois quatre", mergeTail("un deux trois quatre", "trois quatre"))
        assertEquals("un deux", mergeTail("un deux", "UN DEUX "))
    }

    @Test
    fun overlapDeTroisMots() {
        assertEquals("a b c d e f", mergeTail("a b c d", "b c d e f"))
    }

    @Test
    fun casseEtAccentsNormalisesPourLaComparaison() {
        assertEquals("Le Chat dort", mergeTail("Le Chat", "chat dort"))
        assertEquals("le chat DORT", mergeTail("le chat", "CHAT DORT"))
    }
}
