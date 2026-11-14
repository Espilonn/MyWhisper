package dev.voxkey.bench

import android.app.Activity
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/** Accueil : mots dictés ce mois-ci, graphique en points, dernières dictées. */
class HomeScreen(
    private val act: Activity,
    private val ui: UiKit,
    onOpenHistory: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    private val status = ui.text("", 12f, Ui.INK, bold = true)
    private val chart = DotBarsView(act)
    private val ticks = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    val view: ScrollView

    init {
        val brand = ImageView(act).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = ui.rounded(Ui.ON_ACCENT, 12)
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
        }
        val titles = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), 0, 0, 0)
            addView(ui.text("voxkey", 20f, Ui.INK, bold = true))
            addView(ui.text("Tes dictées ce mois-ci", 13f, Ui.MUTED))
        }
        status.setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
        status.setOnClickListener { onOpenSettings() }
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(brand)
            addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(status)
        }

        val hero = ui.card()
        hero.addView(ui.sectionTitle(R.drawable.ic_words, "Mots dictés ce mois"))
        hero.addFull(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(ui.text(formatCount(SampleData.wordsThisMonth), 46f, Ui.INK, bold = true))
            val delta = deltaPercent(SampleData.wordsThisMonth, SampleData.wordsLastMonth)
            if (delta != null) {
                val up = delta >= 0
                addView(ui.pill(
                    "${if (up) "↑" else "↓"} ${abs(delta)} %",
                    if (up) Ui.GOOD else Ui.WARN,
                    if (up) Ui.GOOD_BG else Ui.WARN_BG,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        leftMargin = ui.dp(10)
                        bottomMargin = ui.dp(10)
                    }
                })
            }
        })
        hero.addFull(
            ui.text("mots ce mois · vs octobre (${formatCount(SampleData.wordsLastMonth)})", 13f, Ui.MUTED)
        )
        hero.addFull(ui.segmented(listOf("Semaine", "Mois"), 1) { render(it == 0) }, 18)
        hero.addView(chart, LinearLayout.LayoutParams(MATCH, ui.dp(120)).apply { topMargin = ui.dp(16) })
        hero.addFull(ticks, 8)

        val recent = ui.card()
        recent.addView(ui.sectionTitle(R.drawable.ic_nav_history, "Dernières dictées"))
        SampleData.dictations.take(3).forEach { recent.addFull(ui.dictationRow(it), 14) }
        recent.addFull(ui.text("Voir tout l'historique  →", 14f, Ui.ACCENT, bold = true).apply {
            setPadding(0, ui.dp(16), 0, 0)
            setOnClickListener { onOpenHistory() }
        })

        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(24))
            addView(header)
            addFull(ui.sampleTag(), 14)
            addFull(hero, 20)
            addFull(recent, 16)
        }
        view = ScrollView(act).apply { addView(content) }
        render(week = false)
    }

    /** Met à jour la pastille « Prêt » ou « Configurer » selon les droits. */
    fun refresh() {
        val ready = Settings.canDrawOverlays(act) && SettingsScreen.isAccessibilityOn(act)
        status.text = if (ready) "● Prêt" else "Configurer"
        status.setTextColor(if (ready) Ui.GOOD else Ui.ON_ACCENT)
        status.background = ui.rounded(if (ready) Ui.GOOD_BG else Ui.ACCENT, 12)
    }

    private fun render(week: Boolean) {
        val values = if (week) SampleData.dailyWords.takeLast(7).toIntArray() else SampleData.dailyWords
        val labels = if (week) SampleData.weekdayInitials else SampleData.monthTicks
        chart.setData(values)
        ticks.removeAllViews()
        labels.forEach { label ->
            ticks.addView(ui.text(label, 11f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
        }
    }
}
