package dev.voxkey.bench

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Dictionnaire : les mots que voxkey connaît, avec recherche instantanée. */
class DictionaryScreen(private val act: Activity, private val ui: UiKit) {

    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    val view: ScrollView

    init {
        val search = EditText(act).apply {
            hint = "Rechercher un mot"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.INK)
            textSize = 15f
            maxLines = 1
            background = ui.rounded(Ui.CARD, 16)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = renderList(s?.toString().orEmpty())
            })
        }

        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(24))
        }
        content.addView(ui.text("Dictionnaire", 26f, Ui.INK, bold = true))
        content.addFull(ui.sampleTag(), 12)
        content.addFull(search, 16)
        content.addFull(list, 14)
        content.addFull(ui.button("Ajouter un mot", primary = false) {
            Toast.makeText(act, "Dictionnaire : ajout pas encore disponible", Toast.LENGTH_SHORT).show()
        }, 18)
        view = ScrollView(act).apply { addView(content) }
        renderList("")
    }

    private fun renderList(query: String) {
        list.removeAllViews()
        val hits = filterWords(SampleData.dictionary, query)
        if (hits.isEmpty()) {
            list.addView(ui.text("Aucun mot ne correspond à « ${query.trim()} ».", 14f, Ui.MUTED))
            return
        }
        hits.forEach { list.addFull(wordRow(it), 8) }
    }

    private fun wordRow(e: SampleData.DictEntry) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ui.rounded(Ui.CARD, 16)
        setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        addView(ui.text(e.word, 16f, Ui.INK, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(ui.text("${e.count} fois", 13f, Ui.MUTED))
    }
}
