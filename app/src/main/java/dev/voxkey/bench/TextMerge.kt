package dev.voxkey.bench

import java.util.Locale

private val WORD = Regex("""[\p{L}\p{N}]+""")

private fun words(s: String): List<String> =
    WORD.findAll(s.lowercase(Locale.ROOT)).map { it.value }.toList()

/** Colle le texte d'une fenêtre glissante sur le texte déjà validé.
 *  words() = minuscules, sans ponctuation ; l'overlap est calculé mot à mot. */
fun mergeTail(committed: String, windowText: String): String {
    if (committed.isBlank()) return windowText
    if (windowText.isBlank()) return committed

    val a = words(committed)
    val b = words(windowText)
    var overlap = 0
    for (k in minOf(a.size, b.size) downTo 1) {
        if (a.subList(a.size - k, a.size) == b.subList(0, k)) {
            overlap = k
            break
        }
    }
    if (overlap == 0) return "$committed $windowText"

    // On coupe le texte brut de la fenêtre après le dernier mot recouvert,
    // pour garder sa casse et sa ponctuation.
    val cut = WORD.findAll(windowText).elementAt(overlap - 1).range.last + 1
    val rest = windowText.substring(cut).trim()
    if (rest.isEmpty()) return committed
    return committed + (if (rest.first().isLetterOrDigit()) " " else "") + rest
}
