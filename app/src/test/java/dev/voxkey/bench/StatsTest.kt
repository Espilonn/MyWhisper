package dev.voxkey.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {

    @Test
    fun formatCountGroupsThousandsWithNarrowNoBreakSpace() {
        assertEquals("42", formatCount(42))
        assertEquals("1\u202F248", formatCount(1248))
        assertEquals("1\u202F000\u202F000", formatCount(1_000_000))
        assertEquals("-1\u202F105", formatCount(-1105))
    }

    @Test
    fun deltaPercentRoundsAndRefusesZeroReference() {
        assertEquals(13, deltaPercent(1248, 1105))
        assertEquals(-50, deltaPercent(50, 100))
        assertEquals(0, deltaPercent(100, 100))
        assertNull(deltaPercent(5, 0))
    }

    @Test
    fun countWordsIgnoresPunctuationOnlyTokens() {
        assertEquals(6, countWords("Peux-tu m'envoyer le devis avant vendredi ?"))
        assertEquals(0, countWords("   "))
        assertEquals(4, countWords("Courses : lait, pain, café"))
    }

    @Test
    fun filterWordsIsCaseInsensitiveAndKeepsOrder() {
        val entries = listOf(
            SampleData.DictEntry("Voxkey", 14),
            SampleData.DictEntry("TVA", 5),
            SampleData.DictEntry("Gradle", 4),
        )
        assertEquals(entries, filterWords(entries, "  "))
        assertEquals(listOf(entries[0]), filterWords(entries, "VOX"))
        assertEquals(listOf(entries[2]), filterWords(entries, "dle"))
        assertEquals(emptyList<SampleData.DictEntry>(), filterWords(entries, "zzz"))
    }

    @Test
    fun sampleMonthMatchesDailySeries() {
        assertEquals(1248, SampleData.wordsThisMonth)
        assertEquals(SampleData.dailyWords.size, SampleData.monthTicks.size)
    }
}
