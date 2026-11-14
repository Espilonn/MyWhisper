package dev.voxkey.bench

/**
 * DONNÉES D'EXEMPLE. Rien n'est enregistré : il n'existe encore ni stockage des dictées,
 * ni historique, ni dictionnaire, ni mots-clés. Chaque écran lit ce seul objet ; le remplacer
 * par les vraies sources ne demandera de toucher qu'ici.
 *
 * Calendrier fictif : mois de novembre 2026, aujourd'hui = vendredi 13.
 */
object SampleData {

    data class Dictation(val day: String, val time: String, val text: String)
    data class Keyword(val trigger: String, val replacement: String, val tag: String)
    data class DictEntry(val word: String, val count: Int)

    /** Mots dictés par jour, du 1er au 13 novembre (aujourd'hui en dernier). */
    val dailyWords = intArrayOf(74, 112, 96, 83, 121, 90, 68, 105, 97, 118, 86, 64, 134)
    val wordsThisMonth = dailyWords.sum()
    const val wordsLastMonth = 1105

    /** Repères de l'axe du mois : 1, 5, 9, 13. */
    val monthTicks: List<String> = List(dailyWords.size) { i -> if (i % 4 == 0) "${i + 1}" else "" }

    /** Initiales des 7 derniers jours : sam. 7 -> ven. 13. */
    val weekdayInitials = listOf("S", "D", "L", "M", "M", "J", "V")

    val dictations = listOf(
        Dictation("Aujourd'hui", "14:32", "Peux-tu m'envoyer le devis avant vendredi ?"),
        Dictation("Aujourd'hui", "11:08", "Réunion décalée à 15 h, je prévois une heure de marge."),
        Dictation("Aujourd'hui", "09:41", "Bonjour Marc, merci pour ta réponse rapide."),
        Dictation("Hier", "18:20", "Courses : lait, pain de mie, deux citrons, du café."),
        Dictation("Hier", "12:05", "Relance le client pour la facture d'octobre."),
        Dictation("Hier", "08:55", "Ajoute un rappel pour la déclaration de TVA."),
        Dictation("Lundi 9 novembre", "16:47", "Je reviens vers toi lundi prochain avec les chiffres."),
    )

    val keywords = listOf(
        Keyword("mail merci", "Merci beaucoup pour ton retour, je reviens vers toi rapidement.", "Formule"),
        Keyword("mail rdv", "Bonjour, serait-il possible de se voir la semaine prochaine ?", "Formule"),
        Keyword("cordialement", "Bien cordialement,", "Formule"),
        Keyword("signature", "Ton nom\nTon poste", "Signature"),
        Keyword("adresse", "12 rue de l'Exemple, 75000 Paris", "Adresse"),
    )

    /** Dictionnaire trié par nombre d'occurrences décroissant. */
    val dictionary = listOf(
        DictEntry("Marc", 11),
        DictEntry("Voxkey", 14),
        DictEntry("Whisper", 9),
        DictEntry("devis", 8),
        DictEntry("facture", 7),
        DictEntry("Kotlin", 6),
        DictEntry("TVA", 5),
        DictEntry("Gradle", 4),
        DictEntry("Lyon", 3),
        DictEntry("Figma", 2),
    )
}
