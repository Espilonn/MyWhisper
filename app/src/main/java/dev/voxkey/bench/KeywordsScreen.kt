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
        title = "Mots-clés",
        subtitle = "Une abréviation tapée, un texte complet écrit.",
        trailing = ui.iconButton(R.drawable.ic_add, "Ajouter un mot-clé") { edit(null) }.also { addButton = it },
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
            body.addView(
                ui.empty(
                    R.drawable.ic_nav_keywords,
                    "Aucun mot-clé",
                    "Dis « mail merci » et MyWisper écrira ta formule de politesse en entier.",
                    action = "Créer un mot-clé",
                    onAction = { edit(null) },
                ),
                LinearLayout.LayoutParams(MATCH, 0, 1f),
            )
            return
        }
        body.addView(ui.meta(if (keywords.size == 1) "1 mot-clé" else "${keywords.size} mots-clés"))
        val card = ui.card()
        keywords.forEachIndexed { i, k ->
            if (i > 0) card.addRule()
            card.addView(keywordRow(k))
        }
        body.addFull(card, 10)
        body.addFull(
            ui.meta(
                "Appuie sur un mot-clé pour le modifier. Le déclencheur n'est remplacé que " +
                    "lorsque tu dictes ce mot seul.",
            ),
            12,
        )
    }

    private fun keywordRow(k: Keyword) = ui.listRow(
        title = k.trigger,
        subtitle = k.replacement,
        lead = ui.heading((k.trigger.firstOrNull()?.uppercase() ?: "?"), Ui.ACCENT).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Ui.CARD_HI, 99, Ui.LINE)
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
        },
        glyphIcon = R.drawable.ic_delete,
        glyphDesc = "Supprimer « ${k.trigger} »",
        onOpen = { edit(k) },
        onGlyph = { confirmDelete(k) },
    )

    private fun edit(existing: Keyword?) {
        ui.prompt(
            title = if (existing == null) "Nouveau mot-clé" else "Modifier le mot-clé",
            subtitle = "Tapé seul pendant la dictée, le déclencheur est remplacé par le texte.",
            fields = listOf(
                UiKit.Field("Déclencheur", hint = "Ex. : mail merci", initial = existing?.trigger.orEmpty()),
                UiKit.Field("Texte écrit à la place", hint = "Merci beaucoup pour ton retour…", initial = existing?.replacement.orEmpty(), lines = 3),
            ),
            confirm = if (existing == null) "Ajouter" else "Enregistrer",
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
            title = "Supprimer « ${k.trigger} » ?",
            message = "Le raccourci disparaît ; le texte ne sera plus écrit à ta place.",
            confirmLabel = "Supprimer",
            destructive = true,
            onConfirm = {
                Store.deleteKeyword(k.id)
                render()
            },
        )
    }
}
