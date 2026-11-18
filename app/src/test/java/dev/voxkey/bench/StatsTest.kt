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
}

/** Libellés de date des écrans (Stats.kt) : ajouté en fin de fichier. */
class DayLabelTest {

    private val today = java.time.LocalDate.of(2026, 11, 15)

    @Test
    fun dayLabelIsRelativeThenDated() {
        assertEquals("Aujourd'hui", dayLabel(today, today))
        assertEquals("Hier", dayLabel(today.minusDays(1), today))
        assertEquals(
            "9 novembre",
            dayLabel(java.time.LocalDate.of(2026, 11, 9), today, locale = java.util.Locale.FRENCH),
        )
        assertEquals(
            "31 décembre 2025",
            dayLabel(java.time.LocalDate.of(2025, 12, 31), today, locale = java.util.Locale.FRENCH),
        )
    }

    @Test
    fun dayLabelFollowsThePhoneLanguage() {
        assertEquals(
            "9 November",
            dayLabel(java.time.LocalDate.of(2026, 11, 9), today, locale = java.util.Locale.ENGLISH),
        )
        assertEquals(
            "Today",
            dayLabel(today, today, todayLabel = "Today", yesterdayLabel = "Yesterday"),
        )
    }
}
