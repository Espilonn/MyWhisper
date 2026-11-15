package dev.voxkey.bench

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Mots-clés : une abréviation tapée pendant la dictée, un texte complet écrit à la place.
 * Ajouter, modifier et supprimer passent par le Store.
 */
class KeywordsScreen(private val act: Activity, private val ui: UiKit) {

    private val page = ui.page(
        title = ui.t(R.string.keywords_title),
        subtitle = ui.t(R.string.keywords_subtitle),
        trailing = ui.iconButton(R.drawable.ic_add, ui.t(R.string.keywords_add)) { edit(null) }.also { addButton = it },
    )
    private lateinit var addButton: View
    private val body = page.body
    val view = page.root

    init {
        refresh()
    }

    fun refresh() {
        ui.retintIconButton(addButton)
        render()
    }

    private fun render() {
        body.removeAllViews()
        val keywords = Store.keywords()
        if (keywords.isEmpty()) {
            body.center(
                ui.empty(
                    R.drawable.ic_nav_keywords,
                    ui.t(R.string.keywords_empty_title),
                    ui.t(R.string.keywords_empty_body),
                    action = ui.t(R.string.keywords_empty_action),
                    onAction = { edit(null) },
                ),
            )
            return
        }
        body.addFull(
            ui.section(
                ui.t(R.string.keywords_section),
                ui.q(R.plurals.keywords_count, keywords.size),
            ),
        )
        val card = ui.card()
        keywords.forEachIndexed { i, k ->
            if (i > 0) card.addRule()
            card.addView(keywordRow(k))
        }
        body.addFull(card, 10)
        body.addFull(ui.meta(ui.t(R.string.keywords_tip)), 12)
    }

    private fun keywordRow(k: Keyword) = ui.listRow(
        title = k.trigger,
        subtitle = k.replacement,
        lead = ui.heading((k.trigger.firstOrNull()?.uppercase() ?: ui.t(R.string.keywords_initial)), Ui.ACCENT).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Ui.CARD_HI, 99, Ui.LINE)
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
        },
        glyphIcon = R.drawable.ic_delete,
        glyphDesc = ui.t(R.string.keywords_delete_desc, k.trigger),
        onOpen = { edit(k) },
        onGlyph = { confirmDelete(k) },
    )

    private fun edit(existing: Keyword?) {
        ui.prompt(
            title = ui.t(if (existing == null) R.string.keywords_new else R.string.keywords_edit),
            subtitle = ui.t(R.string.keywords_prompt_subtitle),
            fields = listOf(
                UiKit.Field(ui.t(R.string.keywords_trigger), hint = ui.t(R.string.keywords_trigger_hint), initial = existing?.trigger.orEmpty()),
                UiKit.Field(ui.t(R.string.keywords_text), hint = ui.t(R.string.keywords_text_hint), initial = existing?.replacement.orEmpty(), lines = 3),
            ),
            confirm = ui.t(if (existing == null) R.string.keywords_add_confirm else R.string.save),
        ) { values ->
            val trigger = values[0]
            val replacement = values.getOrElse(1) { "" }
            if (trigger.isEmpty()) {
                false
            } else {
                // Le déclencheur identifie le mot-clé : s'il change, l'ancien doit partir.
                if (existing != null && existing.trigger != trigger) Store.deleteKeyword(existing.id)
                Store.saveKeyword(trigger, replacement)
                render()
                true
            }
        }
    }

    private fun confirmDelete(k: Keyword) {
        ui.confirm(
            title = ui.t(R.string.keywords_delete_title, k.trigger),
            message = ui.t(R.string.keywords_delete_message),
            confirmLabel = ui.t(R.string.delete),
            destructive = true,
            onConfirm = {
                Store.deleteKeyword(k.id)
                render()
            },
        )
    }
}
