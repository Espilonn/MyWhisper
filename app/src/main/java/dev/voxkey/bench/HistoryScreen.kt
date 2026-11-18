package dev.voxkey.bench

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.LinearLayout
import android.widget.Toast
import java.time.LocalDate

/** Historique : les dictées enregistrées, groupées par jour, avec suppression. */
class HistoryScreen(private val act: Activity, private val ui: UiKit) {

    private val page = ui.page(
        title = ui.t(R.string.history_title),
        subtitle = ui.t(R.string.history_subtitle),
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
                    ui.t(R.string.history_empty_title),
                    ui.t(R.string.history_empty_body),
                ),
            )
            return
        }

        all.groupBy { dayKey(it.ts) }.forEach { (_, items) ->
            body.addFull(
                ui.section(
                    dayLabel(
                        dayOf(items.first().ts),
                        LocalDate.now(),
                        ui.t(R.string.today),
                        ui.t(R.string.yesterday),
                    ),
                    ui.q(R.plurals.history_dictations, items.size),
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
        over = ui.t(R.string.history_row_meta, timeLabel(d.ts), ui.q(R.plurals.words, d.words)),
        glyphIcon = R.drawable.ic_delete,
        glyphDesc = ui.t(R.string.history_delete_desc),
        onOpen = { copy(d.text) },
        onGlyph = { confirmDelete(d) },
    )

    /** Le texte entier part dans le presse-papiers ; Android 13+ affiche déjà sa propre confirmation. */
    private fun copy(text: String) {
        val cm = act.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("MyWhisper", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(act, ui.t(R.string.history_copied), Toast.LENGTH_SHORT).show()
        }
    }

    /** La suppression est définitive : on demande confirmation avant d'effacer. */
    private fun confirmDelete(d: Dictation) {
        ui.confirm(
            title = ui.t(R.string.history_delete_title),
            message = ui.t(R.string.history_delete_message, shorten(d.text)),
            confirmLabel = ui.t(R.string.delete),
            destructive = true,
            onConfirm = {
                Store.deleteDictation(d.id)
                render()
            },
        )
    }

    private fun shorten(text: String) = if (text.length <= 60) text else text.take(57).trimEnd() + "…"
}
