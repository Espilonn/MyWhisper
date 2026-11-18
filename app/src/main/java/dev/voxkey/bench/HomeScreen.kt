package dev.voxkey.bench

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
        title = ui.t(R.string.app_name),
        subtitle = ui.t(R.string.home_tagline),
        trailing = status,
    )
    private val body = page.body
    val view = page.root

    /** Fenêtre du graphique : 0 = semaine, 1 = mois, 2 = six mois. */
    private var span = 1

    /** Release GitHub plus récente, si la vérification en a trouvé une. */
    private var release: Update.Release? = null
    private var checked = false

    private val dayCount get() = when (span) {
        0 -> 7
        1 -> 30
        else -> 180
    }

    init {
        status.setOnClickListener {
            val r = release
            when {
                r != null && r.newer -> showUpdate(r)
                ready() -> showVersion()
                else -> onOpenSettings()
            }
        }
        checkUpdate()
        refresh()
    }

    /** Relit les droits puis les chiffres (appelé à chaque retour sur l'onglet). */
    fun refresh() {
        checkUpdate()
        paintStatus()
        render()
    }

    /** Version de debug seulement : rappelle d'où vient le texte, jamais dans un build release. */
    private val debugBadge: TextView? =
        if ((act.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) ui.micro("") else null

    private fun paintDebug() {
        val badge = debugBadge ?: return
        badge.text = if (RemoteEngine.isCloud(act)) {
            ui.t(
                R.string.debug_engine_cloud,
                if (RemoteEngine.provider(act) == RemoteEngine.OPENROUTER) "OpenRouter" else "Groq",
            )
        } else {
            ui.t(R.string.debug_engine_local)
        }
    }

    /** Une seule vérification par lancement : le cache de 24 h évite d'interroger GitHub. */
    private fun checkUpdate() {
        if (checked) return
        checked = true
        val v = Update.currentVersion(act)
        Update.check(act) { r ->
            release = r
            // Une installation a pu changer la version pendant la vérification : on repart de ce verdict.
            if (Update.currentVersion(act) != v) checkNow()
            else paintStatus()
        }
    }

    private fun showUpdate(r: Update.Release) {
        val notes = r.notes.ifBlank { ui.t(R.string.update_no_notes) }
        ui.notes(ui.t(R.string.update_title, r.version), notes, ui.t(R.string.update_install)) {
            Update.install(
                act,
                r,
                onProgress = { pct -> status.text = ui.t(R.string.update_progress, pct) },
                onError = { msg ->
                    status.text = ui.t(R.string.home_status_update)
                    Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    /** Fiche « à jour » : version installée, notes de la dernière release, et de quoi re-chercher. */
    private fun showVersion() {
        val current = Update.currentVersion(act)
        val r = release
        val text = buildString {
            append(ui.t(R.string.update_up_to_date, current))
            if (r != null) {
                append("\n\n")
                append(ui.t(R.string.update_latest, r.version))
                append("\n\n")
                append(r.notes.ifBlank { ui.t(R.string.update_no_notes) })
            }
        }
        ui.notes(ui.t(R.string.update_title, current), text, ui.t(R.string.update_check)) { checkNow() }
    }

    /** Recherche à la demande : `force` ignore le cache de 24 h. */
    private fun checkNow() {
        status.text = ui.t(R.string.update_checking)
        Update.check(act, force = true) { r ->
            release = r
            paintStatus()
            when {
                r == null -> Toast.makeText(act, ui.t(R.string.update_check_failed), Toast.LENGTH_LONG).show()
                r.newer -> showUpdate(r)
                else -> Toast.makeText(act, ui.t(R.string.update_up_to_date, Update.currentVersion(act)), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ready() =
        Settings.canDrawOverlays(act) && SettingsScreen.isAccessibilityOn(act) && SettingsScreen.micGranted(act)

    private fun paintStatus() {
        if (release?.newer == true) {
            status.text = ui.t(R.string.home_status_update)
            status.setTextColor(Ui.ON_ACCENT)
            status.background = ui.rounded(Ui.ACCENT, 99)
            return
        }
        val on = ready()
        status.text = if (on) ui.t(R.string.home_status_ready) else ui.t(R.string.home_configure)
        status.setTextColor(if (on) Ui.GOOD else Ui.ON_ACCENT)
        status.background = ui.rounded(if (on) Ui.GOOD_BG else Ui.ACCENT, 99)
    }

    private fun render() {
        body.removeAllViews()
        debugBadge?.let { badge ->
            paintDebug()
            body.addView(badge, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ui.dp(10) })
        }
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
            addView(ui.label(ui.t(R.string.home_words_label)), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.meta(spanLabel()))
        })
        val figure = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(ui.display(formatCount(total)))
            deltaPercent(total, previous)?.let { delta ->
                val up = delta >= 0
                addView(ui.pill(
                    ui.t(R.string.home_delta, if (up) "↑" else "↓", abs(delta)),
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
        val spans = listOf(
            ui.t(R.string.home_span_week),
            ui.t(R.string.home_span_month),
            ui.t(R.string.home_span_six_months),
        )
        hero.addView(ui.segmented(spans, span) { i ->
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
        0 -> ui.t(R.string.home_caption_week)
        1 -> ui.t(R.string.home_caption_month)
        else -> ui.t(R.string.home_caption_six_months)
    }

    /** Aucune dictée : une seule invitation, au milieu de l'écran. */
    private fun onboarding() = ui.empty(
        R.drawable.ic_words,
        ui.t(R.string.home_empty_title),
        ui.t(R.string.home_empty_body),
        action = if (ready()) null else ui.t(R.string.home_setup),
        onAction = { onOpenSettings() },
    )

    /** « Aujourd'hui · 30 mots », le détail d'un carré touché. */
    private fun dayDetail(day: DayGrid.Day): String {
        val words = if (day.words == 0) {
            ui.t(R.string.home_wordless)
        } else {
            ui.q(R.plurals.words, day.words)
        }
        return ui.t(R.string.home_join, dayName(day.date), words)
    }

    /** Échelle d'intensité en cinq tons, du jour vide au jour plein. */
    private fun legend() = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.micro(ui.t(R.string.home_legend_less)).apply { setPadding(0, 0, ui.dp(6), 0) })
        (listOf(Ui.OFF) + Accent.ramp().toList()).forEach { c ->
            addView(View(act).apply {
                background = ui.rounded(c, 3)
                layoutParams = LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)).apply {
                    rightMargin = ui.dp(3)
                }
            })
        }
        addView(ui.micro(ui.t(R.string.home_legend_more)).apply { setPadding(ui.dp(3), 0, 0, 0) })
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
            addView(ui.label(ui.t(R.string.home_latest)), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.link(ui.t(R.string.home_see_all)) { onOpenHistory() })
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
            card.addView(ui.meta(ui.t(R.string.home_journal_empty)).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(24), 0, ui.dp(24))
            })
        } else {
            last.forEachIndexed { i, d ->
                if (i > 0) card.addRule()
                card.addFull(ui.listRow(
                    title = d.text,
                    over = ui.t(R.string.home_join, relativeStamp(d.ts), ui.q(R.plurals.words, d.words)),
                ))
            }
        }
        return card
    }

    /** « Aujourd'hui · 14:32 », « Hier · 09:41 », « 9 novembre · 16:47 ». */
    private fun relativeStamp(ts: Long) = ui.t(R.string.home_join, dayName(dayOf(ts)), timeLabel(ts))

    /** Nom du jour, « Aujourd'hui » et « Hier » venus des ressources. */
    private fun dayName(day: LocalDate) =
        dayLabel(day, LocalDate.now(), ui.t(R.string.today), ui.t(R.string.yesterday))
}
