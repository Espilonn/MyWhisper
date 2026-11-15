package dev.voxkey.bench

import android.app.Activity
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
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
    )
    private val body = page.body
    val view = page.root

    /** Fenêtre du graphique : 0 = semaine, 1 = mois, 2 = six mois. */
    private var span = 1

    private val dayCount get() = when (span) {
        0 -> 7
        1 -> 30
        else -> 180
    }

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
        val days = dayCount
        val series = Store.wordsPerDay(days)
        val total = series.sum()
        val dayMs = 86_400_000L
        val now = System.currentTimeMillis()
        val previous = Store.totalWordsSince(now - 2L * days * dayMs) - Store.totalWordsSince(now - days * dayMs)

        if (Store.dictationCount() == 0) {
            body.center(onboarding())
            return
        }

        val hero = ui.card()
        // Intitulé et fenêtre sur la même ligne : le nombre et la grille gagnent la place.
        hero.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.label("Mots dictés"), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.meta(spanLabel()))
        })
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
        hero.addView(ui.segmented(listOf("Semaine", "Mois", "6 mois"), span) { i ->
            span = i
            render()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(14) })

        // Un carré par jour : la grille se lit comme celle des contributions GitHub.
        val today = LocalDate.now()
        val cells = series.mapIndexed { i, v ->
            DayGrid.Day(today.minusDays((days - 1 - i).toLong()), v)
        }
        val grid = DayGrid(act).apply {
            setData(cells, span)
            selectLast()
        }
        // Détail du jour choisi (aujourd'hui au départ), plus la légende d'intensité.
        val caption = ui.micro(dayDetail(cells.last()), Ui.MUTED)
        grid.onPick = { day -> caption.text = dayDetail(day) }

        hero.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
        hero.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caption, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(legend(), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = ui.dp(16) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })

        body.addView(hero)

        body.addView(journal(), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
    }

    /** « sur 7 jours », « sur 30 jours », « sur 6 mois » : la fenêtre lue sous le grand nombre. */
    private fun spanLabel() = when (span) {
        0 -> "sur 7 jours"
        1 -> "sur 30 jours"
        else -> "sur 6 mois"
    }

    /** Aucune dictée : une seule invitation, au milieu de l'écran. */
    private fun onboarding() = ui.empty(
        R.drawable.ic_words,
        "Aucun mot pour l'instant",
        "Ouvre un champ de texte, puis appuie sur la touche de dictée en bas du clavier. " +
            "Tes mots s'afficheront ici, jour après jour.",
        action = if (ready()) null else "Mettre en place",
        onAction = { onOpenSettings() },
    )

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

    /**
     * Dernières dictées : les plus récentes, puis le lien vers l'historique. La carte s'ajuste à
     * son contenu : elle affiche autant de lignes que la vue en laisse la place (quatre, deux
     * dans la vue Mois dont la grille est haute), et s'arrête sur la dernière — jamais de zone
     * vide sous la dernière ligne, jamais de ligne coupée en deux.
     */
    private fun journal(): LinearLayout {
        val card = ui.card()
        card.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.label("Dernières dictées"), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.link("Tout voir →") { onOpenHistory() })
        })
        // Autant de dictées que la vue en laisse la place : la grille de six mois prend plus
        // de hauteur que celle de la semaine, celle du mois plus encore.
        val room = when (span) {
            DayGrid.MONTH -> 1
            DayGrid.DENSE -> 3
            else -> 4
        }
        val last = Store.dictations().take(room)
        if (last.isEmpty()) {
            card.addView(ui.meta("Tes dictées s'afficheront ici.").apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(24), 0, ui.dp(24))
            })
        } else {
            last.forEachIndexed { i, d ->
                if (i > 0) card.addRule()
                card.addFull(ui.listRow(
                    title = d.text,
                    over = "${relativeStamp(d.ts)} · ${wordsLabel(d.words)}",
                ))
            }
        }
        return card
    }

    /** « Aujourd'hui · 14:32 », « Hier · 09:41 », « 9 novembre · 16:47 ». */
    private fun relativeStamp(ts: Long) = "${dayLabel(dayOf(ts))} · ${timeLabel(ts)}"
}
