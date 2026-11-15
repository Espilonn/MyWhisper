package dev.voxkey.bench

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout

/**
 * Dictionnaire : le vocabulaire saisi à la main — noms propres, marques, termes techniques.
 * Ajouter, chercher, supprimer. Rien n'est appris automatiquement et ces mots ne sont pas
 * encore transmis au moteur : l'écran ne promet donc aucune correction automatique.
 */
class DictionaryScreen(private val act: Activity, private val ui: UiKit) {

    private val search = ui.input("Rechercher dans mes mots")
    private val count = ui.meta("")
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    /** État vide : posé à même la page pour qu'il occupe la hauteur libre. */
    private var filler: android.view.View? = null
    private val page = ui.page(
        title = "Dictionnaire",
        subtitle = "Les noms propres, marques et termes techniques que la dictée doit écrire juste.",
        trailing = ui.iconButton(R.drawable.ic_add, "Ajouter un mot") { addWord() }.also { addButton = it },
    )
    private lateinit var addButton: android.view.View
    private val body = page.body
    val view = page.root

    init {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        body.addView(search)
        body.addView(count, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(14) })
        body.addView(list, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(10) })
        refresh()
    }

    fun refresh() {
        ui.retintIconButton(addButton)
        render()
    }

    private fun render() {
        filler?.let { body.removeView(it) }
        filler = null
        list.removeAllViews()
        val query = search.text.toString()
        val hits = Store.words(query)
        val total = Store.words().size
        count.text = when {
            total == 0 -> ""
            query.isBlank() -> if (total == 1) "1 mot" else "$total mots"
            hits.isEmpty() -> ""
            else -> "${hits.size} sur $total"
        }
        if (hits.isEmpty()) {
            val view = if (total == 0) {
                ui.empty(
                    R.drawable.ic_nav_dictionary,
                    "Ta liste est vide",
                    "Ajoute les mots que la dictée écorche : un prénom, une marque, " +
                        "un terme de métier. Ils restent sur ce téléphone.",
                    action = "Ajouter un mot",
                    onAction = { addWord() },
                )
            } else {
                ui.empty(
                    R.drawable.ic_nav_dictionary,
                    "Aucun mot trouvé",
                    "Rien ne correspond à « ${query.trim()} ».",
                )
            }
            body.addView(view, LinearLayout.LayoutParams(MATCH, 0, 1f))
            filler = view
            return
        }
        val card = ui.card()
        hits.forEachIndexed { i, e ->
            if (i > 0) card.addRule()
            card.addView(wordRow(e))
        }
        list.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun wordRow(e: WordEntry) = ui.listRow(
        title = e.word,
        glyphIcon = R.drawable.ic_delete,
        glyphDesc = "Supprimer « ${e.word} »",
        onGlyph = { confirmDelete(e) },
    )

    private fun confirmDelete(e: WordEntry) {
        ui.confirm(
            title = "Supprimer « ${e.word} » ?",
            message = "Le mot sera retiré de ta liste.",
            confirmLabel = "Supprimer",
            destructive = true,
            onConfirm = {
                Store.deleteWord(e.word)
                render()
            },
        )
    }

    private fun addWord() {
        ui.prompt(
            title = "Ajouter un mot",
            subtitle = "Un prénom, une marque, un terme que la dictée doit écrire juste.",
            fields = listOf(UiKit.Field("Mot", hint = "Ex. : Voxkey")),
            confirm = "Ajouter",
        ) { values ->
            val word = values.firstOrNull().orEmpty()
            if (word.isEmpty()) {
                false
            } else {
                Store.addWord(word)
                search.setText("")
                render()
                true
            }
        }
    }
}
