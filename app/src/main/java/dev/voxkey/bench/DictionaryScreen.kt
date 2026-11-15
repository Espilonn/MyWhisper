package dev.voxkey.bench

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout

/**
 * Dictionnaire : le vocabulaire saisi à la main — noms propres, marques, termes techniques.
 * Ajouter, chercher, supprimer. Rien n'est appris automatiquement et ces mots ne sont pas
 * encore transmis au moteur : l'écran ne promet donc aucune correction automatique.
 */
class DictionaryScreen(private val act: Activity, private val ui: UiKit) {

    private val search = ui.input("Rechercher dans mes mots")
    private val count = ui.meta("")
    private val section = android.widget.LinearLayout(act).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(ui.heading("Mes mots"), android.widget.LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(count)
    }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    /** Emplacement de l'état vide : occupe la hauteur libre, sans déplacer la recherche. */
    private val emptyBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val page = ui.page(
        title = "Dictionnaire",
        subtitle = "Tes noms propres et tes marques, gardés à part.",
        trailing = ui.iconButton(R.drawable.ic_add, "Ajouter un mot") { addWord() }.also { addButton = it },
    )
    private lateinit var addButton: View
    private val body = page.body
    val view = page.root

    init {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        body.addView(search)
        body.addView(section, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
        body.addView(list, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(6) })
        body.addView(emptyBox, LinearLayout.LayoutParams(MATCH, 0, 1f))
        refresh()
    }

    fun refresh() {
        ui.retintIconButton(addButton)
        render()
    }

    private fun render() {
        list.removeAllViews()
        emptyBox.removeAllViews()
        emptyBox.visibility = View.GONE
        val query = search.text.toString()
        val hits = Store.words(query)
        val total = Store.words().size
        count.text = when {
            total == 0 -> ""
            query.isBlank() -> if (total == 1) "1 mot" else "$total mots"
            hits.isEmpty() -> ""
            else -> "${hits.size} sur $total"
        }
        count.visibility = if (count.text.isEmpty()) View.GONE else View.VISIBLE
        // Sans aucun mot, la recherche et l'intitulé de section n'ont rien à interroger ;
        // sans résultat, l'état vide dit tout.
        search.visibility = if (total == 0) View.GONE else View.VISIBLE
        section.visibility = if (hits.isEmpty()) View.GONE else View.VISIBLE
        if (hits.isEmpty()) {
            val empty = if (total == 0) {
                ui.empty(
                    R.drawable.ic_nav_dictionary,
                    "Ta liste est vide",
                    "Garde ici les prénoms, les marques et les termes de métier que tu veux " +
                        "retrouver. Rien ne quitte ce téléphone.",
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
            emptyBox.center(empty)
            emptyBox.visibility = View.VISIBLE
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
            subtitle = "Un prénom, une marque, un terme que tu veux garder tel quel.",
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
