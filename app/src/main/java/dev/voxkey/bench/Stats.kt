package dev.voxkey.bench

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// Fonctions pures, sans Android : formatage et calculs des écrans. Testées dans StatsTest.

/** Nombre avec séparateur de milliers fine insécable : 1248 -> "1 248". */
fun formatCount(n: Int): String {
    val digits = Math.abs(n).toString().reversed().chunked(3).joinToString("\u202F").reversed()
    return if (n < 0) "-$digits" else digits
}

/** Variation en pourcentage arrondie ; null sans référence (pas de division par zéro). */
fun deltaPercent(current: Int, previous: Int): Int? {
    if (previous <= 0) return null
    return Math.round((current - previous) * 100.0 / previous).toInt()
}

/** Nombre de mots : les jetons sans lettre ni chiffre (ponctuation seule) ne comptent pas. */
fun countWords(text: String): Int =
    text.trim().split(Regex("\\s+")).count { token -> token.any { it.isLetterOrDigit() } }

// --- dates des écrans : jour local, libellé lisible, heure

/** Jour local d'un horodatage. */
fun dayOf(ts: Long): LocalDate =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

/** Clé de regroupement stable dans le temps : « 2026-11-15 ». */
fun dayKey(ts: Long): String = dayOf(ts).toString()

/**
 * « Aujourd'hui », « Hier », sinon « 9 novembre » (année ajoutée si ce n'est pas celle en cours).
 * Les deux mots relatifs viennent des ressources, seul endroit où un texte est traduit ; le nom
 * du mois suit la langue du téléphone via [locale].
 */
fun dayLabel(
    day: LocalDate,
    today: LocalDate = LocalDate.now(),
    todayLabel: String = "Aujourd'hui",
    yesterdayLabel: String = "Hier",
    locale: Locale = Locale.getDefault(),
): String {
    val month = day.month.getDisplayName(TextStyle.FULL, locale)
    return when {
        day == today -> todayLabel
        day == today.minusDays(1) -> yesterdayLabel
        day.year == today.year -> "${day.dayOfMonth} $month"
        else -> "${day.dayOfMonth} $month ${day.year}"
    }
}

/** Heure locale « 14:32 ». */
fun timeLabel(ts: Long): String {
    val t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime()
    return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
}
