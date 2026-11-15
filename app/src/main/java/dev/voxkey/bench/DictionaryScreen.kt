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

    private val search = ui.input(ui.t(R.string.dictionary_search))
    private val count = ui.meta("")
    private val section = android.widget.LinearLayout(act).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(ui.heading(ui.t(R.string.dictionary_section)), android.widget.LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(count)
    }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    /** Emplacement de l'état vide : occupe la hauteur libre, sans déplacer la recherche. */
    private val emptyBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val page = ui.page(
        title = ui.t(R.string.dictionary_title),
        subtitle = ui.t(R.string.dictionary_subtitle),
        trailing = ui.iconButton(R.drawable.ic_add, ui.t(R.string.dictionary_add)) { addWord() }.also { addButton = it },
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
            query.isBlank() -> ui.q(R.plurals.words, total)
            hits.isEmpty() -> ""
            else -> ui.t(R.string.dictionary_count, hits.size, total)
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
                    ui.t(R.string.dictionary_empty_title),
                    ui.t(R.string.dictionary_empty_body),
                    action = ui.t(R.string.dictionary_add),
                    onAction = { addWord() },
                )
            } else {
                ui.empty(
                    R.drawable.ic_nav_dictionary,
                    ui.t(R.string.dictionary_empty_search_title),
                    ui.t(R.string.dictionary_empty_search_body, query.trim()),
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
        glyphDesc = ui.t(R.string.dictionary_delete_desc, e.word),
        onGlyph = { confirmDelete(e) },
    )

    private fun confirmDelete(e: WordEntry) {
        ui.confirm(
            title = ui.t(R.string.dictionary_delete_title, e.word),
            message = ui.t(R.string.dictionary_delete_message),
            confirmLabel = ui.t(R.string.delete),
            destructive = true,
            onConfirm = {
                Store.deleteWord(e.word)
                render()
            },
        )
    }

    private fun addWord() {
        ui.prompt(
            title = ui.t(R.string.dictionary_add),
            subtitle = ui.t(R.string.dictionary_prompt_subtitle),
            fields = listOf(UiKit.Field(ui.t(R.string.dictionary_field_word), hint = ui.t(R.string.dictionary_field_hint))),
            confirm = ui.t(R.string.dictionary_add_confirm),
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
