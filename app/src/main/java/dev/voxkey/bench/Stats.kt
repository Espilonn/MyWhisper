package dev.voxkey.bench

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

/** Filtre du dictionnaire : insensible à la casse, texte coupé aux espaces des bords. */
fun filterWords(entries: List<SampleData.DictEntry>, query: String): List<SampleData.DictEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return entries
    return entries.filter { it.word.lowercase().contains(q) }
}
