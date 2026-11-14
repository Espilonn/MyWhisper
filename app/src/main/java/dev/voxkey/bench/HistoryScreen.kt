package dev.voxkey.bench

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/** Historique : les dictées, groupées par jour. */
class HistoryScreen(private val act: Activity, private val ui: UiKit) {

    val view: ScrollView

    init {
        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(24))
        }
        content.addView(ui.text("Historique", 26f, Ui.INK, bold = true))
        content.addFull(ui.sampleTag(), 12)

        val totalWords = SampleData.dictations.sumOf { countWords(it.text) }
        content.addFull(
            ui.text("${SampleData.dictations.size} dictées · $totalWords mots", 14f, Ui.MUTED), 10
        )

        SampleData.dictations.groupBy { it.day }.forEach { (day, items) ->
            val header = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(day, 14f, Ui.INK, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(ui.text("${items.size} dictées", 12f, Ui.MUTED))
            }
            content.addFull(header, 22)

            val card = ui.card()
            items.forEachIndexed { i, d ->
                if (i > 0) card.addRule(12)
                card.addFull(ui.dictationRow(d), if (i > 0) 12 else 0)
            }
            content.addFull(card, 8)
        }
        view = ScrollView(act).apply { addView(content) }
    }
}
