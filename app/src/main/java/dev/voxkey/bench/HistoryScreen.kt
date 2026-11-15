package dev.voxkey.bench

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout

/** Historique : les dictées enregistrées, groupées par jour, avec suppression. */
class HistoryScreen(private val act: Activity, private val ui: UiKit) {

    private val page = ui.page("Historique", "Tes dictées, de la plus récente à la plus ancienne.")
    private val body = page.body
    val view = page.root

    fun refresh() = render()

    private fun render() {
        body.removeAllViews()
        val all = Store.dictations()
        if (all.isEmpty()) {
            body.addView(
                ui.empty(
                    R.drawable.ic_nav_history,
                    "Aucune dictée",
                    "Chaque texte dicté est gardé ici. Ouvre un champ de texte, puis appuie sur la touche de dictée.",
                ),
                LinearLayout.LayoutParams(MATCH, 0, 1f),
            )
            return
        }

        body.addView(ui.meta("${formatCount(all.sumOf { it.words })} mots au total"))

        all.groupBy { dayKey(it.ts) }.forEach { (_, items) ->
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.heading(dayLabel(dayOf(items.first().ts))),
                    LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(ui.meta(if (items.size == 1) "1 dictée" else "${items.size} dictées"))
            }
            body.addFull(head, 24)
            val card = ui.card()
            items.forEachIndexed { i, d ->
                if (i > 0) card.addRule()
                card.addView(dictationRow(d))
            }
            body.addFull(card, 10)
        }
    }

    private fun dictationRow(d: Dictation) = ui.listRow(
        title = d.text,
        over = timeLabel(d.ts),
        badge = ui.pill(wordsLabel(d.words), Ui.MUTED, Ui.CARD_HI),
        glyphIcon = R.drawable.ic_delete,
        glyphDesc = "Supprimer cette dictée",
        onGlyph = { confirmDelete(d) },
    )

    /** La suppression est définitive : on demande confirmation avant d'effacer. */
    private fun confirmDelete(d: Dictation) {
        ui.confirm(
            title = "Supprimer cette dictée ?",
            message = "« ${shorten(d.text)} » sera effacée pour de bon.",
            confirmLabel = "Supprimer",
            destructive = true,
            onConfirm = {
                Store.deleteDictation(d.id)
                render()
            },
        )
    }

    private fun shorten(text: String) = if (text.length <= 60) text else text.take(57).trimEnd() + "…"
}
