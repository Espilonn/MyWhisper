package dev.voxkey.bench

import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Mots-clés : une abréviation tapée, un texte complet écrit, présenté comme des modèles d'e-mail. */
class KeywordsScreen(private val act: Activity, private val ui: UiKit) {

    val view: ScrollView

    init {
        val titles = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text("Mots-clés", 26f, Ui.INK, bold = true))
            addView(ui.text("Une abréviation tapée, un texte complet écrit.", 14f, Ui.MUTED))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val add = ui.text("+", 24f, Ui.ON_ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Ui.ACCENT, 22)
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
            setOnClickListener { notReady() }
        }
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titles)
            addView(add)
        }

        val list = ui.card()
        list.addView(ui.sectionTitle(R.drawable.ic_nav_keywords, "Modèles"))
        SampleData.keywords.forEach { list.addFull(keywordRow(it), 12) }

        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(24))
            addView(header)
            addFull(ui.sampleTag(), 14)
            addFull(ui.text("Pas encore branché à la dictée.", 13f, Ui.MUTED), 8)
            addFull(list, 18)
        }
        view = ScrollView(act).apply { addView(content) }
    }

    private fun keywordRow(k: SampleData.Keyword) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ui.rounded(Ui.BG, 16)
        setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        addView(ui.text(k.trigger.first().uppercase(), 16f, Ui.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Ui.CARD_HI, 20)
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
        })
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), 0, ui.dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(ui.text("« ${k.trigger} »", 15f, Ui.INK, bold = true))
            addView(ui.text(k.replacement, 13f, Ui.MUTED).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, ui.dp(2), 0, 0)
            })
        })
        addView(ui.pill(k.tag, Ui.MUTED, Ui.LINE))
        setOnClickListener { notReady() }
    }

    private fun notReady() {
        Toast.makeText(act, "Mots-clés : pas encore disponibles", Toast.LENGTH_SHORT).show()
    }
}
