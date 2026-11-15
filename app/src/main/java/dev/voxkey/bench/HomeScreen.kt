package dev.voxkey.bench

import android.app.Activity
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.LocalDate
import kotlin.math.abs

/**
 * Accueil : les mots dictés sur la fenêtre choisie, le graphique réel, les dernières dictées.
 * L'écran ne défile pas : le journal du bas prend la hauteur qui reste, donc le bas de
 * l'écran n'est jamais vide, quelle que soit la quantité de dictées.
 */
class HomeScreen(
    private val act: Activity,
    private val ui: UiKit,
    private val onOpenHistory: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    private val status = ui.pill("", Ui.MUTED, Ui.CARD_HI)
    private val page = ui.page(
        title = "MyWisper",
        subtitle = "Ta dictée, sur le téléphone",
        trailing = status,
        scroll = false,
    )
    private val body = page.body
    val view = page.root

    /** Fenêtre du graphique : false = 30 jours, true = 7 jours. */
    private var week = false

    init {
        status.setOnClickListener { onOpenSettings() }
        refresh()
    }

    /** Relit les droits puis les chiffres (appelé à chaque retour sur l'onglet). */
    fun refresh() {
        paintStatus()
        render()
    }

    private fun ready() =
        Settings.canDrawOverlays(act) && SettingsScreen.isAccessibilityOn(act) && SettingsScreen.micGranted(act)

    private fun paintStatus() {
        val on = ready()
        status.text = if (on) "●  Prêt" else "Configurer"
        status.setTextColor(if (on) Ui.GOOD else Ui.ON_ACCENT)
        status.background = ui.rounded(if (on) Ui.GOOD_BG else Ui.ACCENT, 99)
    }

    private fun render() {
        body.removeAllViews()
        val days = if (week) 7 else 30
        val series = Store.wordsPerDay(days)
        val total = series.sum()
        val dayMs = 86_400_000L
        val now = System.currentTimeMillis()
        val previous = Store.totalWordsSince(now - 2L * days * dayMs) - Store.totalWordsSince(now - days * dayMs)

        if (Store.dictationCount() == 0) {
            body.addView(onboarding(), LinearLayout.LayoutParams(MATCH, 0, 1f))
            return
        }

        val hero = ui.card()
        hero.addView(ui.label("Mots dictés"))
        val figure = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(ui.display(formatCount(total)))
            deltaPercent(total, previous)?.let { delta ->
                val up = delta >= 0
                addView(ui.pill(
                    "${if (up) "↑" else "↓"} ${abs(delta)} %",
                    if (up) Ui.GOOD else Ui.WARN,
                    if (up) Ui.GOOD_BG else Ui.WARN_BG,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        leftMargin = ui.dp(12)
                        bottomMargin = ui.dp(10)
                    }
                })
            }
        }
        hero.addView(figure, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) })
        hero.addView(ui.meta("sur $days jours · ${formatCount(previous)} sur les $days jours d'avant").apply {
            setPadding(0, ui.dp(4), 0, 0)
        })
        hero.addView(ui.segmented(listOf("Semaine", "Mois"), if (week) 0 else 1) { i ->
            week = i == 0
            render()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(18) })

        // Un carré par jour : la grille se lit comme celle des contributions GitHub.
        val today = LocalDate.now()
        val cells = series.mapIndexed { i, v ->
            DayGrid.Day(today.minusDays((days - 1 - i).toLong()), v)
        }
        val grid = DayGrid(act).apply {
            setData(cells, week)
            selectLast()
        }
        hero.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(18) })

        // Détail du jour choisi (aujourd'hui au départ), plus la légende d'intensité.
        val caption = ui.micro(dayDetail(cells.last()), Ui.MUTED)
        grid.onPick = { day -> caption.text = dayDetail(day) }
        val foot = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caption, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(legend())
        }
        hero.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(12) })

        hero.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        body.addView(hero)

        body.addView(journal(), LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = ui.dp(16) })
    }

    /** Aucune dictée : l'écran entier devient une invitation, centrée, sans trou. */
    private fun onboarding(): LinearLayout = ui.card(26).apply {
        gravity = Gravity.CENTER
        addView(ui.icon(R.drawable.ic_words, Ui.ACCENT, 26).apply {
            background = ui.rounded(Ui.ACCENT_BG, 99)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            layoutParams = LinearLayout.LayoutParams(ui.dp(58), ui.dp(58))
        })
        addView(ui.heading("Aucun mot pour l'instant").apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(20), 0, 0)
        })
        addView(ui.body(
            "Ouvre un champ de texte, puis appuie sur la touche de dictée en bas du clavier. " +
                "Tes mots s'afficheront ici, jour après jour.",
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(10), 0, 0)
        })
        if (!ready()) {
            addView(ui.button("Mettre en place", primary = true) { onOpenSettings() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(24) })
        }
    }

    /** « Aujourd'hui · 30 mots », le détail d'un carré touché. */
    private fun dayDetail(day: DayGrid.Day): String {
        val label = if (day.date == LocalDate.now()) "Aujourd'hui" else dayLabel(day.date)
        return when {
            day.words == 0 -> "$label · aucune dictée"
            day.words == 1 -> "$label · 1 mot"
            else -> "$label · ${formatCount(day.words)} mots"
        }
    }

    /** Échelle d'intensité en cinq tons, du jour vide au jour plein. */
    private fun legend() = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.micro("moins").apply { setPadding(0, 0, ui.dp(6), 0) })
        (listOf(Ui.OFF) + Accent.ramp().toList()).forEach { c ->
            addView(View(act).apply {
                background = ui.rounded(c, 3)
                layoutParams = LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)).apply {
                    rightMargin = ui.dp(3)
                }
            })
        }
        addView(ui.micro("plus").apply { setPadding(ui.dp(3), 0, 0, 0) })
    }

    /** Dernières dictées : la carte prend la hauteur libre, la liste défile à l'intérieur. */
    private fun journal(): LinearLayout {
        val card = ui.card()
        card.addView(ui.label("Dernières dictées"))
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val last = Store.dictations().take(6)
        if (last.isEmpty()) {
            list.addView(ui.empty(
                R.drawable.ic_nav_history,
                "Rien pour l'instant",
                "Ta première dictée s'affichera ici, avec l'heure et le nombre de mots.",
            ), LinearLayout.LayoutParams(MATCH, 0, 1f))
        } else {
            last.forEachIndexed { i, d ->
                if (i > 0) list.addRule()
                list.addFull(ui.listRow(
                    title = d.text,
                    over = relativeStamp(d.ts),
                    badge = ui.pill(wordsLabel(d.words), Ui.MUTED, Ui.CARD_HI),
                ))
            }
        }
        card.addView(ScrollView(act).apply {
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            addView(list, LinearLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = ui.dp(4) })
        card.addRule(4)
        card.addFull(ui.textButton("Tout l'historique  →") { onOpenHistory() })
        return card
    }

    /** « Aujourd'hui · 14:32 », « Hier · 09:41 », « 9 novembre · 16:47 ». */
    private fun relativeStamp(ts: Long) = "${dayLabel(dayOf(ts))} · ${timeLabel(ts)}"
}
