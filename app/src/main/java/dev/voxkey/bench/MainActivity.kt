package dev.voxkey.bench

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import java.io.File

class MainActivity : Activity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusA11y: TextView
    private lateinit var modelSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun title(t: String) = TextView(this).apply {
            text = t
            textSize = 20f
            setPadding(0, pad, 0, pad / 2)
        }

        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

        statusOverlay = TextView(this)
        statusA11y = TextView(this)

        root.addView(title("voxkey"))

        root.addView(statusOverlay)
        root.addView(button("1. Autoriser la superposition") { askOverlay() })

        root.addView(statusA11y)
        root.addView(button("2. Activer le service d'accessibilité") { askAccessibility() })

        root.addView(button("3. Autoriser le micro") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })

        root.addView(title("Modèle"))
        modelSpinner = Spinner(this)
        root.addView(modelSpinner)
        root.addView(button("Rafraîchir") { refreshModels() })

        root.addView(button("4. Activer la bulle") { ensureBubble() })

        val note = TextView(this).apply {
            text = "La bulle apparaît quand un champ de texte a le focus. " +
                "Appui pour dicter, appui pour arrêter. Glisse pour la déplacer."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        root.addView(note)

        setContentView(
            ScrollView(this).apply { addView(root) },
            ViewGroup.LayoutParams.MATCH_PARENT.let {
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        refreshModels()
        // La bulle n'a pas besoin d'un bouton : dès que les droits sont là, elle tourne.
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java))
        }
    }

    private fun refreshState() {
        statusOverlay.text = if (Settings.canDrawOverlays(this)) "Superposition: OK" else "Superposition: manquante"
        statusA11y.text = if (isAccessibilityOn()) "Accessibilité: OK" else "Accessibilité: manquante"
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun refreshModels() {
        val files = getExternalFilesDir(null)?.listFiles()
            ?.filter { it.name.endsWith(".bin") }
            ?.sortedBy { it.length() }
            ?: emptyList()

        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            files.map { "${it.name} (${it.length() / 1_048_576} Mo)" }
        )

        val saved = getSharedPreferences("voxkey", MODE_PRIVATE).getString("model", null)
        val idx = files.indexOfFirst { it.name == saved }
        if (idx >= 0) modelSpinner.setSelection(idx)

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                files.getOrNull(pos)?.let {
                    getSharedPreferences("voxkey", MODE_PRIVATE).edit().putString("model", it.name).apply()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun askOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun askAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun ensureBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorise d'abord la superposition", Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Bulle activée", Toast.LENGTH_SHORT).show()
    }
}
