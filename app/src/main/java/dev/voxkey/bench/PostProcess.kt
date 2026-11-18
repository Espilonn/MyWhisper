package dev.voxkey.bench

import android.util.Log

/** Artefacts connus de whisper sur du silence : à ne pas publier. */
private val ARTIFACTS = setOf(
    "Sous-titres par la communauté d'Amara.org",
    "Sous-titres réalisés par la communauté d'Amara.org",
    "Merci d'avoir regardé cette vidéo",
)

/** Retire les jetons de whisper entre crochets, les notes ♪ et les artefacts connus. */
internal fun cleanWhisper(text: String): String {
    if (ARTIFACTS.any { text.contains(it) }) return ""
    return text.replace(Regex("\\[[^\\]]*\\]|♪"), " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * Une abréviation dictée seule devient le texte complet enregistré pour ce mot-clé.
 * Correspondance sur la dictée entière uniquement : « signature » est aussi un mot courant,
 * l'étendre au milieu d'une phrase abîmerait le texte.
 */
internal fun expandKeywords(text: String): String {
    if (text.isBlank()) return text
    val bare = text.trim().trimEnd('.', '!', '?', ',', ';', ':', ' ').lowercase()
    val hit = runCatching {
        Store.keywords().firstOrNull { it.trigger.trim().lowercase() == bare }
    }.getOrNull() ?: return text
    Log.d("VXK", "keyword « ${hit.trigger} » → ${hit.replacement.length} chars")
    return hit.replacement
}
