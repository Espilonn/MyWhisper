package dev.voxkey.bench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Bulle flottante + dictée.
 *
 * Service démarré normalement (pas de notification au repos). Il passe en
 * foreground seulement pendant l'enregistrement, car Android exige un service
 * foreground pour accéder au micro en arrière-plan.
 */
class OverlayService : Service() {

    companion object {
        private const val TICK_MS = 700L
        private const val PARTIAL_WINDOW_SAMPLES = 16000 * 4
        private const val CHANNEL_ID = "voxkey-dictation"
        private const val NOTIF_ID = 42

        /** Artefacts connus de whisper sur du silence : à ne pas publier. */
        private val ARTIFACTS = setOf(
            "Sous-titres par la communauté d'Amara.org",
            "Sous-titres réalisés par la communauté d'Amara.org",
            "Merci d'avoir regardé cette vidéo",
        )

        @Volatile
        var running = false
            private set

        private var focusListener: ((Boolean) -> Unit)? = null
        private var keyboardListener: ((Rect?) -> Unit)? = null

        fun notifyFocus(has: Boolean) {
            focusListener?.invoke(has)
        }

        /** Rectangle du clavier (coordonnées écran) fourni par le service d'accessibilité. */
        fun notifyKeyboard(bounds: Rect?) {
            keyboardListener?.invoke(bounds)
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var key: FrameLayout
    private lateinit var keyView: WaveBarsView
    private lateinit var panel: TextView
    private lateinit var params: WindowManager.LayoutParams

    /** Zone du clavier à l'écran ; null tant qu'aucun clavier n'est visible. */
    private var keyboard: Rect? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("voxkey", MODE_PRIVATE) }

    private var recorder: Recorder? = null

    @Volatile
    private var recording = false

    /** Texte actuellement présent dans le champ (thread principal seulement). */
    private var pushed = ""

    /** Texte validé de la dictée, recollé partiel après partiel (thread de décodage). */
    @Volatile
    private var committed = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()
        running = true

        focusListener = { has ->
            Log.d("VXK", "focus-> $has (recording=$recording)")
            main.post {
                if (recording) return@post
                if (has) {
                    root.visibility = View.VISIBLE
                    moveToKeyboard()
                } else {
                    root.visibility = View.GONE
                }
            }
        }
        keyboardListener = { bounds ->
            main.post {
                keyboard = bounds
                Log.d("VXK", "keyboard=$bounds")
                if (root.visibility == View.VISIBLE) moveToKeyboard()
            }
        }
        Log.d("VXK", "overlay created, a11y=${VoxkeyAccessibilityService.instance != null} text=${VoxkeyAccessibilityService.hasTextField}")
        if (VoxkeyAccessibilityService.instance != null) {
            val has = VoxkeyAccessibilityService.hasTextField
            root.visibility = if (has) View.VISIBLE else View.GONE
            if (has) moveToKeyboard()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        focusListener = null
        keyboardListener = null
        running = false
        recording = false
        keyView.active = false
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        main.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        main.postDelayed({ WhisperEngine.release() }, 1000)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- vues

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildViews() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        // Panneau de statut : chip compact, même matière que le clavier.
        panel = TextView(this).apply {
            visibility = View.GONE
            setTextColor(0xFFE6E8EC.toInt())
            textSize = 13f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xF01C1F25.toInt())
                setStroke(dp(1), 0x1FFFFFFF)
            }
        }

        // Touche de dictée : même matière et même rayon qu'une touche Gboard.
        keyView = WaveBarsView(this)

        key = FrameLayout(this).apply {
            contentDescription = "Dicter"
            isClickable = true
            background = keyBackground(recording = false)
            foreground = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                null,
                GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.WHITE) }
            )
            addView(keyView, FrameLayout.LayoutParams(dp(30), dp(20), Gravity.CENTER))
            setOnClickListener { toggleRecording() }
        }

        root.addView(panel, LinearLayout.LayoutParams(dp(232), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        root.addView(key, LinearLayout.LayoutParams(dp(52), dp(40)))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(6)
            y = dp(260)
        }

        wm.addView(root, params)
        root.visibility = View.GONE
    }

    /** Surface de touche : sombre et translucide comme une touche Gboard, liseré bleu en dictée. */
    private fun keyBackground(recording: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(0xF02B3038.toInt())
        setStroke(dp(1), if (recording) 0x804C8DFF.toInt() else 0x1FFFFFFF)
    }

    /**
     * Cale la touche dans le coin bas-gauche de la zone clavier.
     *
     * Le clavier appartient à Gboard : on ne peut pas dessiner dedans, mais le service
     * d'accessibilité nous donne son rectangle exact (`AccessibilityWindowInfo`), donc le
     * bord bas de la touche s'aligne au pixel sur le bord haut du clavier, à la marge
     * gauche du clavier. Sans clavier visible on retombe sur une estimation.
     */
    private fun moveToKeyboard() {
        val screenH = wm.currentWindowMetrics.bounds.height()
        val kb = keyboard
        if (kb != null) {
            params.x = kb.left + dp(6)
            params.y = (screenH - kb.top).coerceAtLeast(0)
        } else {
            params.x = dp(6)
            // shortcut: aucun clavier mesurable (clavier matériel), on garde ~52 % de l'écran
            params.y = (screenH * 0.53f).toInt()
        }
        wm.updateViewLayout(root, params)
    }

    // ----------------------------------------------------------- dictée

    private fun toggleRecording() {
        if (recording) stopSession() else startSession()
    }

    private fun startSession() {
        pushed = ""
        committed = ""

        startForeground(
            NOTIF_ID, notification("Dictée en cours…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        recorder = Recorder().also { it.start() }
        recording = true

        key.background = keyBackground(recording = true)
        keyView.active = true
        panel.text = ""
        panel.visibility = View.VISIBLE
        // le clavier peut s'être ouvert entre-temps : on se recale avant d'afficher le texte
        moveToKeyboard()

        thread(name = "voxkey-loop") { decodeLoop() }
    }

    private fun decodeLoop() {
        while (recording) {
            val rec = recorder ?: break
            val pcm = rec.tail(PARTIAL_WINDOW_SAMPLES)
            if (pcm.size < 8000) { // < 0,5 s
                Thread.sleep(150)
                continue
            }
            val model = partialModelPath()
            if (model != null && WhisperEngine.ensure(model)) {
                val text = WhisperEngine.transcribe(pcm, "fr", 4)
                if (!isArtifact(text)) {
                    committed = mergeTail(committed, text.trim())
                    if (committed.isNotBlank()) publish(committed)
                }
            }
            Thread.sleep(TICK_MS)
        }
    }

    private fun isArtifact(text: String): Boolean {
        val t = text.trim()
        return t.isEmpty() || t in ARTIFACTS
    }

    private fun publish(text: String) {
        main.post {
            panel.text = text
            if (text != pushed) {
                // shortcut: un remplacement raté laisse le texte doublé jusqu'au remplacement final
                if (VoxkeyAccessibilityService.instance?.replaceText(pushed, text) == true) {
                    pushed = text
                }
            }
        }
    }

    private fun stopSession() {
        recording = false
        keyView.active = false
        key.background = keyBackground(recording = false)

        val rec = recorder
        recorder = null
        val pcm = rec?.stop() ?: FloatArray(0)

        keyView.busy = true
        thread(name = "voxkey-final") {
            var finalText = ""
            val model = selectedModelPath()
            if (pcm.size >= 8000 && model != null && WhisperEngine.ensure(model)) {
                finalText = WhisperEngine.transcribe(pcm, "fr", 4)
            }

            main.post {
                keyView.busy = false
                if (finalText.isNotBlank()) {
                    panel.text = finalText
                    // la transcription finale remplace exactement ce qui a été inséré
                    if (VoxkeyAccessibilityService.instance?.replaceText(pushed, finalText) != true) {
                        VoxkeyAccessibilityService.instance?.paste(finalText)
                    }
                    pushed = finalText
                }
                overlayStopForeground()
                if (!VoxkeyAccessibilityService.hasTextField) root.visibility = View.GONE
                main.postDelayed({ panel.visibility = View.GONE }, 2500)
                main.postDelayed({ WhisperEngine.release() }, 60_000)
            }
        }
    }

    // ------------------------------------------------------------ modèles

    private fun modelDir(): File? = getExternalFilesDir(null)

    private fun selectedModelPath(): String? {
        val name = getSharedPreferences("voxkey", MODE_PRIVATE).getString("model", null)
        val dir = modelDir() ?: return null
        val f = if (name != null) File(dir, name) else null
        if (f != null && f.exists()) return f.absolutePath
        return dir.listFiles()?.filter { it.name.endsWith(".bin") }
            ?.minByOrNull { it.length() }?.absolutePath
    }

    /** Les partiels utilisent le plus petit modèle disponible : plus réactif. */
    private fun partialModelPath(): String? {
        val dir = modelDir() ?: return null
        return dir.listFiles()?.filter { it.name.endsWith(".bin") }
            ?.minByOrNull { it.length() }?.absolutePath
    }

    // -------------------------------------------------------- notification

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dictée", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("voxkey")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun overlayStopForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
