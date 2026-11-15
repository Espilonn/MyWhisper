package dev.voxkey.bench

import android.app.Activity
import android.widget.LinearLayout

/** Historique : les dictées enregistrées, groupées par jour, avec suppression. */
class HistoryScreen(private val act: Activity, private val ui: UiKit) {

    private val page = ui.page(
        title = "Historique",
        subtitle = "De la plus récente à la plus ancienne.",
    )
    private val body = page.body
    val view = page.root

    fun refresh() = render()

    private fun render() {
        body.removeAllViews()
        val all = Store.dictations()
        if (all.isEmpty()) {
            body.center(
                ui.empty(
                    R.drawable.ic_nav_history,
                    "Aucune dictée",
                    "Chaque texte dicté est gardé ici. Ouvre un champ de texte, puis appuie sur " +
                        "la touche de dictée.",
                ),
            )
            return
        }

        all.groupBy { dayKey(it.ts) }.forEach { (_, items) ->
            body.addFull(
                ui.section(
                    dayLabel(dayOf(items.first().ts)),
                    if (items.size == 1) "1 dictée" else "${items.size} dictées",
                ),
                if (body.childCount == 0) 0 else 24,
            )
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
        over = "${timeLabel(d.ts)} · ${wordsLabel(d.words)}",
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
