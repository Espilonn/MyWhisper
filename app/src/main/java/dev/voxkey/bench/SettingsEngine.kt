package dev.voxkey.bench

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import kotlin.concurrent.thread

internal fun SettingsScreen.buildEngineCard() {
    val engine = ui.card()
    engine.addView(ui.label(ui.t(R.string.settings_engine)))
    engine.addFull(
        ui.segmented(
            listOf(ui.t(R.string.settings_engine_local), ui.t(R.string.settings_engine_cloud)),
            if (RemoteEngine.isCloud(act)) 1 else 0
        ) { i ->
            prefs.edit().putString(RemoteEngine.PREF_ENGINE, if (i == 0) "local" else "cloud").apply()
            renderEngine()
        }, 10
    )
    engine.addFull(ui.meta(ui.t(R.string.settings_engine_hint)), 8)

    cloudBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    cloudBox.addFull(
        ui.segmented(
            listOf("Groq", "OpenRouter"),
            if (RemoteEngine.provider(act) == RemoteEngine.OPENROUTER) 1 else 0
        ) { i ->
            val before = RemoteEngine.provider(act)
            val p = if (i == 0) RemoteEngine.GROQ else RemoteEngine.OPENROUTER
            // Le champ appartient encore au fournisseur précédent : on le range chez lui
            // avant de charger la clé de l'autre.
            RemoteEngine.saveKey(act, before, keyInput.text.toString())
            prefs.edit().putString(RemoteEngine.PREF_PROVIDER, p)
                .putString(RemoteEngine.PREF_MODEL, RemoteEngine.defaultModel(p)).apply()
            modelInput.setText(RemoteEngine.defaultModel(p))
            keyInput.setText(RemoteEngine.key(act, p))
            cloudStatus.text = ""
        }, 14
    )
    modelInput = ui.input(ui.t(R.string.settings_cloud_model_hint), RemoteEngine.model(act)).apply {
        setOnFocusChangeListener { _, focused ->
            if (!focused) saveCloudKey()
        }
    }
    cloudBox.addFull(modelInput, 10)
    keyInput = ui.input(ui.t(R.string.settings_cloud_key_hint), RemoteEngine.key(act)).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSelectAllOnFocus(true)
        setOnFocusChangeListener { _, focused ->
            if (!focused) saveCloudKey()
        }
    }
    cloudBox.addFull(keyInput, 8)
    cloudBox.addFull(ui.button(ui.t(R.string.settings_cloud_test), primary = false) { testCloud() }, 12)
    cloudStatus = ui.meta("")
    cloudBox.addFull(cloudStatus, 8)
    engine.addFull(cloudBox, 4)
    content.addFull(engine, 16)
}

/** Local : la carte du modèle ; cloud : fournisseur, modèle, clé. */
internal fun SettingsScreen.renderEngine() {
    val cloud = RemoteEngine.isCloud(act)
    cloudBox.visibility = if (cloud) View.VISIBLE else View.GONE
    modelCard.visibility = if (cloud) View.GONE else View.VISIBLE
}

private fun SettingsScreen.saveCloudKey() {
    prefs.edit().putString(RemoteEngine.PREF_MODEL, modelInput.text.toString().trim()).apply()
    RemoteEngine.saveKey(act, RemoteEngine.provider(act), keyInput.text.toString())
}

private fun SettingsScreen.testCloud() {
    saveCloudKey()
    cloudStatus.setTextColor(Ui.MUTED)
    cloudStatus.text = ui.t(R.string.settings_cloud_testing)
    thread(name = "voxkey-key") {
        val err = RemoteEngine.check(act)
        act.runOnUiThread {
            cloudStatus.setTextColor(if (err == null) Ui.GOOD else Ui.WARN)
            cloudStatus.text =
                if (err == null) ui.t(R.string.settings_cloud_test_ok)
                else ui.t(R.string.settings_cloud_test_fail, err)
        }
    }
}
