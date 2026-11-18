package dev.voxkey.bench

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.voxkey.bench.OverlayService.Companion.KEY_H_DP
import dev.voxkey.bench.OverlayService.Companion.KEY_W_DP
import dev.voxkey.bench.OverlayService.Companion.SAMPLE_MS

// Construction de la bulle et couleur de la touche. Les champs `root`, `key`, `keyView` et
// `params` restent déclarés dans `OverlayService` : une propriété d'extension n'a pas de stockage.

internal fun OverlayService.buildViews() {
    root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
    }

    // Touche de dictée : même matière et même rayon qu'une touche Gboard.
    keyView = WaveBarsView(this)

    key = FrameLayout(this).apply {
        contentDescription = getString(R.string.a11y_dictate)
        isClickable = true
        background = keyBackground(active = false)
        foreground = RippleDrawable(
            ColorStateList.valueOf(0x1FFFFFFF),
            null,
            GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.WHITE) }
        )
        addView(keyView, FrameLayout.LayoutParams(dp(26), dp(20), Gravity.CENTER))
        setOnClickListener { toggleRecording() }
    }

    root.addView(key, LinearLayout.LayoutParams(dp(KEY_W_DP), dp(KEY_H_DP)))
    applyBubbleColor()

    params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (VoxkeyAccessibilityService.instance != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // sans ça, y est relatif à la zone sous la barre d'état : la bulle sortait de l'écran
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(6)
        y = dp(260)
    }

    // En mode attaché, la vue n'entre dans aucune fenêtre ici : elle sera hébergée par la
    // surface attachée au clavier, à l'ouverture de celui-ci.
    if (!canAttach) {
        // `addView` peut échouer (jeton d'accessibilité mort) : sans filet, l'exception
        // remontait à `onCreate` et tuait le processus du service, donc la bulle pour de bon.
        try {
            wm.addView(root, params)
            root.visibility = View.GONE
            Log.d("VXK", "window added type=${params.type} a11y=${VoxkeyAccessibilityService.instance != null}")
        } catch (t: Throwable) {
            Log.e("VXK", "window add failed", t)
        }
    }
}

/**
 * Surface de touche : opaque, sans bordure, dans la couleur réglée (celle du clavier par
 * défaut). Le liseré de l'écoute est porté par les bandes, pas par le fond.
 */
internal fun OverlayService.keyBackground(active: Boolean) = GradientDrawable().apply {
    cornerRadius = dp(9).toFloat()
    setColor(Bubble.background(this@keyBackground))
}

/** Applique le fond et l'encre des bandes : au montage, à chaque ouverture, après une lecture. */
internal fun OverlayService.applyBubbleColor() {
    if (!keyReady) return
    key.background = keyBackground(active = recording)
    keyView.ink = Bubble.idleInk(this)
    keyView.litInk = Bubble.litInk(this)
}

/**
 * Lit la couleur du clavier, une fois par ouverture.
 *
 * Gboard n'expose aucun thème : la seule source est une capture de sa fenêtre, que le
 * système limite en fréquence. La valeur est mémorisée, donc une capture ratée ou refusée
 * laisse simplement le fond précédent.
 */
internal fun OverlayService.sampleBubbleColor() {
    if (Bubble.mode(this) != Bubble.MODE_KEYBOARD) return
    val now = SystemClock.uptimeMillis()
    if (now - lastSampleMs < SAMPLE_MS) return
    val a11y = VoxkeyAccessibilityService.instance ?: return
    // Le clavier doit être levé : capturé pendant la montée, il n'est pas encore dessiné et
    // la médiane sort en noir pur.
    val w = a11y.imeWindow() ?: return
    val kb = w.first
    val screenH = wm.currentWindowMetrics.bounds.height()
    if (kb.height() <= dp(120)) return
    if (kb.top > screenH - dp(200)) return
    if (kb.bottom > screenH + dp(24)) return
    lastSampleMs = now
    a11y.sampleKeyboardColor { color ->
        Log.d("VXK", "sample=#${Integer.toHexString(color)}")
        if (color == 0 || color == Bubble.sampled(this)) return@sampleKeyboardColor
        Bubble.setSampled(this, color)
        applyBubbleColor()
    }
}

/** Affichage immédiat : pas d'animation d'entrée, qui ajouterait une frame invisible. */
internal fun OverlayService.reveal() {
    key.alpha = 1f
    key.scaleX = 1f
    key.scaleY = 1f
    root.visibility = View.VISIBLE
}
