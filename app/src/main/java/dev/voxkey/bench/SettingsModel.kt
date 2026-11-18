package dev.voxkey.bench

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import java.io.File

internal fun SettingsScreen.buildModelCard() {
    val model = ui.card().also { modelCard = it }
    val modelHead = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.label(ui.t(R.string.settings_model)), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(ui.text(ui.t(R.string.settings_refresh), 13f, Ui.ACCENT).apply {
            modelRefresh = this
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(ui.dp(10), ui.dp(10), 0, ui.dp(10))
            setOnClickListener { refreshModels() }
        })
    }
    model.addView(modelHead)
    // Un sélecteur maison : le Spinner système impose sa propre taille de texte.
    modelSpinner = Spinner(act).apply {
        isFocusable = false
        background = null
        setPadding(ui.dp(14), 0, ui.dp(6), 0)
    }
    modelField = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ui.rounded(Ui.CARD_HI, 14, Ui.LINE)
        addView(modelSpinner, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        addView(ui.icon(R.drawable.ic_chevron, Ui.MUTED, 18).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(ui.dp(26), ui.dp(18)).apply { rightMargin = ui.dp(14) }
        })
        setOnClickListener { modelSpinner.performClick() }
    }
    model.addFull(modelField, 10)
    deleteModel = ui.button(ui.t(R.string.settings_model_delete), primary = false) { confirmDeleteModel() }
    model.addFull(deleteModel, 8)
    modelEmpty = ui.meta(
        ui.t(R.string.settings_model_empty),
    )
    model.addFull(modelEmpty, 8)
    downloadBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    model.addFull(downloadBox, 6)
    downloadStatus = ui.meta("")
    model.addFull(downloadStatus, 12)
    content.addFull(model, 16)
}

internal fun SettingsScreen.refreshModels() {
    val files = act.getExternalFilesDir(null)?.listFiles()
        ?.filter { it.name.endsWith(".bin") || it.name.endsWith(".gguf") }
        ?.sortedBy { it.length() }
        ?: emptyList()
    modelFiles = files

    modelSpinner.adapter = object : ArrayAdapter<String>(act, 0, files.map {
        ui.t(R.string.settings_model_file, it.name, ui.t(R.string.megabytes, it.length() / 1_048_576))
    }) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            ui.body(getItem(position).orEmpty(), Ui.INK).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = android.widget.AbsListView.LayoutParams(MATCH, ui.dp(52))
                setPadding(ui.dp(2), 0, ui.dp(2), 0)
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            ui.body(getItem(position).orEmpty(), Ui.INK).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.tappable(Ui.CARD_HI, 0)
                layoutParams = android.widget.AbsListView.LayoutParams(MATCH, ui.dp(48))
                setPadding(ui.dp(16), 0, ui.dp(16), 0)
            }
    }
    modelSpinner.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    modelField.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    modelEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    deleteModel.visibility = if (files.isEmpty() || downloading) View.GONE else View.VISIBLE

    val prefs = act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE)
    val saved = prefs.getString("model", null)
    val idx = files.indexOfFirst { it.name == saved }

    // Le listener est posé AVANT setSelection : sinon un rafraîchissement de l'écran
    // réécrivait le modèle choisi par le premier de la liste (le plus petit).
    modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (suppressModelWrite) return
            files.getOrNull(pos)?.let {
                prefs.edit().putString("model", it.name).apply()
            }
        }

        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
    suppressModelWrite = true
    if (idx >= 0) modelSpinner.setSelection(idx)
    modelSpinner.post { suppressModelWrite = false }

    renderDownloads(files.map { it.name }.toSet())
}

/** Une ligne par modèle du catalogue absent du téléphone. */
private fun SettingsScreen.renderDownloads(present: Set<String>) {
    downloadBox.removeAllViews()
    val missing = ModelCatalog.all.filter { it.file !in present }
    if (missing.isEmpty()) {
        downloadBox.addFull(ui.meta(ui.t(R.string.settings_models_installed)), 6)
        return
    }
    downloadBox.addFull(ui.label(ui.t(R.string.settings_download_title)), 16)
    for (m in missing) {
        downloadBox.addFull(
            ui.meta(ui.t(R.string.settings_model_line, ui.t(m.labelRes), ui.t(R.string.megabytes, m.mo))),
            10,
        )
        val b = ui.button(ui.t(R.string.settings_download_action), primary = false) { startDownload(m) }
        b.isEnabled = !downloading
        b.alpha = if (downloading) 0.4f else 1f
        downloadBox.addFull(b, 8)
    }
}

private fun SettingsScreen.confirmDeleteModel() {
    val f = modelFiles.getOrNull(modelSpinner.selectedItemPosition) ?: return
    AlertDialog.Builder(act)
        .setMessage(ui.t(R.string.settings_model_delete_confirm, f.name))
        .setNegativeButton(ui.t(R.string.cancel), null)
        .setPositiveButton(ui.t(R.string.settings_model_delete)) { _, _ -> removeModel(f) }
        .show()
}

private fun SettingsScreen.removeModel(f: File) {
    if (!f.delete()) {
        Toast.makeText(act, ui.t(R.string.settings_model_delete_failed), Toast.LENGTH_SHORT).show()
        return
    }
    // Le modèle supprimé peut être celui que la dictée charge au démarrage.
    val prefs = act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE)
    if (prefs.getString("model", null) == f.name) {
        val next = modelFiles.firstOrNull { it != f }
        if (next != null) prefs.edit().putString("model", next.name).apply()
        else prefs.edit().remove("model").apply()
    }
    Toast.makeText(act, ui.t(R.string.settings_model_deleted), Toast.LENGTH_SHORT).show()
    refreshModels()
}

private fun SettingsScreen.startDownload(m: ModelCatalog.Remote) {
    val dir = act.getExternalFilesDir(null)
    if (dir == null) {
        Toast.makeText(act, ui.t(R.string.settings_storage_unavailable), Toast.LENGTH_SHORT).show()
        return
    }
    downloading = true
    downloadStatus.text = ui.t(R.string.settings_download_connecting, ui.t(m.labelRes))
    refreshModels()
    ModelCatalog.download(
        act, m.file, dir,
        onProgress = { done, total ->
            act.runOnUiThread {
                downloadStatus.text = if (total > 0) {
                    ui.t(
                        R.string.settings_download_percent,
                        ui.t(m.labelRes),
                        done * 100 / total,
                        ui.t(R.string.megabytes, done / 1_048_576),
                        ui.t(R.string.megabytes, m.mo),
                    )
                } else {
                    ui.t(
                        R.string.settings_download_size,
                        ui.t(m.labelRes),
                        ui.t(R.string.megabytes, done / 1_048_576),
                    )
                }
            }
        },
        onDone = {
            act.runOnUiThread {
                downloading = false
                act.getSharedPreferences("voxkey", Activity.MODE_PRIVATE)
                    .edit().putString("model", m.file).apply()
                downloadStatus.text = ui.t(R.string.settings_download_done, ui.t(m.labelRes))
                Toast.makeText(act, ui.t(R.string.settings_model_ready), Toast.LENGTH_SHORT).show()
                refreshModels()
            }
        },
        onError = { msg ->
            act.runOnUiThread {
                downloading = false
                downloadStatus.text = ui.t(R.string.settings_download_failed, msg)
                Toast.makeText(act, ui.t(R.string.settings_failed, msg), Toast.LENGTH_LONG).show()
                refreshModels()
            }
        },
    )
}
