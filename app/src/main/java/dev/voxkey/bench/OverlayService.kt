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
import android.view.WindowInsets
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
        /** Intervalle de lecture du clavier (filet de sécurité ; les événements d'accessibilité passent avant). */
        private const val POLL_MS = 50L
        /** Une zone clavier n'est suivie qu'une fois identique pendant ce délai : pas les frames d'animation. */
        private const val SETTLE_MS = 16L
        /** Délai avant de masquer la touche quand le clavier n'est plus lu du tout. */
        private const val HIDE_GRACE_MS = 400L
        /** Touche de dictée : mêmes proportions qu'une touche de la rangée du bas. */
        private const val KEY_W_DP = 56
        private const val KEY_H_DP = 44
        /** Part de la largeur du clavier laissée à sa gauche (touches ?123 et virgule). */
        private const val KEY_LEFT_RATIO = 0.24f

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
    private lateinit var params: WindowManager.LayoutParams

    /** Dernière zone clavier stable (emplacement connu, gardé même après fermeture). */
    private var keyboard: Rect? = null
    /** Dernière lecture brute, et l'instant où elle a été vue pour la première fois. */
    private var candidate: Rect? = null
    private var candidateMs = 0L
    private var lastKbMs = 0L
    /** Vrai après une perte de focus : Gboard se referme encore, on ne doit pas réafficher la bulle. */
    private var hiddenByFocus = false
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("voxkey", MODE_PRIVATE) }

    private var recorder: Recorder? = null

    @Volatile
    private var recording = false

    /** Texte actuellement présent dans le champ (thread principal seulement). */
    private var pushed = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Avec le service d'accessibilité, la fenêtre est de type ACCESSIBILITY_OVERLAY :
        // aucune permission système n'est requise (et elle survit aux réinstallations).
        if (VoxkeyAccessibilityService.instance == null && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        // Fenêtres posées via le service d'accessibilité : elles passent au-dessus du clavier.
        wm = (VoxkeyAccessibilityService.instance ?: this).getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()
        running = true

        // Pas de visibilité ici : `onKeyboardRead` et `poll` en sont les seuls propriétaires.
        focusListener = { has ->
            Log.d("VXK", "focus-> $has (recording=$recording)")
            main.post {
                hiddenByFocus = !has
                if (!recording) {
                    // Ne pas masquer tant qu'un clavier est à l'écran : les ROM envoient des
                    // événements de focus `false` sur les champs custom, ce qui faisait clignoter
                    // la bulle hors de l'écran.
                    if (has) moveToKeyboard() else if (keyboard == null) root.visibility = View.GONE
                }
            }
        }
        keyboardListener = { bounds -> main.post { onKeyboardRead(bounds) } }
        Log.d("VXK", "overlay created, a11y=${VoxkeyAccessibilityService.instance != null} text=${VoxkeyAccessibilityService.hasTextField}")
        main.post(poll)
    }

    /**
     * Traite une lecture de la zone clavier (événement d'accessibilité ou poll).
     *
     * Pendant l'animation du clavier, Gboard fait varier sa zone à chaque frame : la bulle ne suit
     * que les valeurs stables (SETTLE_MS), sinon elle « monte » avec le clavier. Elle apparaît tout
     * de suite à la dernière position stable connue, et disparaît dès que le clavier n'est plus lu.
     */
    private fun onKeyboardRead(kb: Rect?) {
        val now = SystemClock.uptimeMillis()
        if (kb == null) {
            // `imeBounds()` renvoie parfois null une frame sur deux pendant que Gboard bouge :
            // on ne masque qu'après une absence continue, sinon la bulle clignote hors écran.
            if (now - lastKbMs > HIDE_GRACE_MS) {
                candidate = null
                if (!recording) root.visibility = View.GONE
            }
            return
        }
        lastKbMs = now
        if (kb != candidate) {
            candidate = kb
            candidateMs = now
        }
        val stable = now - candidateMs >= SETTLE_MS
        if (stable) keyboard = kb
        when {
            recording -> if (stable) moveToKeyboard()
            root.visibility != View.VISIBLE ->
                // Un clavier visible implique un champ : plus besoin de `hiddenByFocus` ici,
                // que les ROM ne renseignent pas de façon fiable sur les champs custom.
                if (keyboard != null) {
                    Log.d("VXK", "reveal hidden=$hiddenByFocus kb=$keyboard")
                    moveToKeyboard()
                    reveal()
                }
            stable -> moveToKeyboard()
        }
    }

    /** Filet de sécurité : relit le clavier à intervalle court, et gère l'enregistrement. */
    private val poll = object : Runnable {
        override fun run() {
            val a11y = VoxkeyAccessibilityService.instance
            if (recording) {
                if (root.visibility != View.VISIBLE) reveal()
            } else if (a11y != null) {
                // `hasTextField` n'est pas fiable sur les champs custom : la géométrie du
                // clavier suffit à savoir qu'un champ est actif.
                val kb = a11y.imeBounds()
                if (kb != null) {
                    onKeyboardRead(kb)
                } else if (!hiddenByFocus && root.visibility != View.VISIBLE) {
                    // Certaines ROM n'exposent pas la fenêtre du clavier : on garde une
                    // position de repli pour que la touche reste accessible.
                    moveToRest()
                    reveal()
                }
            }
            if (running) main.postDelayed(this, POLL_MS)
        }
    }

    /** Repli quand la géométrie du clavier est introuvable : coin bas-gauche, au-dessus du clavier. */
    private fun moveToRest() {
        val metrics = wm.currentWindowMetrics
        val nav = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
        val x = dp(24)
        val y = metrics.bounds.height() - nav - dp(KEY_H_DP) - dp(230)
        if (params.x == x && params.y == y) return
        params.x = x
        params.y = y
        wm.updateViewLayout(root, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        focusListener = null
        keyboardListener = null
        main.removeCallbacks(poll)
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

        // Touche de dictée : même matière et même rayon qu'une touche Gboard.
        keyView = WaveBarsView(this)

        key = FrameLayout(this).apply {
            contentDescription = "Dicter"
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

        wm.addView(root, params)
        root.visibility = View.GONE
    }

    /**
     * Surface de touche : sombre et translucide comme une touche Gboard, sans bordure.
     * En écoute, un liseré jaune reprend l'accent de l'app sur la même matière.
     */
    private fun keyBackground(active: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        setColor(0xF233373D.toInt())
    }

    /** Affichage immédiat : pas d'animation d'entrée, qui ajouterait une frame invisible. */
    private fun reveal() {
        key.alpha = 1f
        key.scaleX = 1f
        key.scaleY = 1f
        root.visibility = View.VISIBLE
    }

    /**
     * Cale la touche dans le coin bas-gauche de la zone clavier, dans la rangée du bas.
     *
     * Le clavier appartient à Gboard : on ne peut pas dessiner dedans, mais le service
     * d'accessibilité nous donne son rectangle exact (`AccessibilityWindowInfo`).
     *
     * - Vertical : on s'aligne sur la rangée du bas, au-dessus de la bande de navigation
     *   système (que le clavier inclut dans ses bornes quand il est edge-to-edge).
     * - Horizontal : on saute les deux touches du coin (`?123` et `,`) qui restent utilisables,
     *   et on mord sur l'extrémité gauche de la barre d'espace — large de plusieurs touches,
     *   elle reste tappable au centre.
     */
    private fun moveToKeyboard() {
        val kb = keyboard ?: return
        val metrics = wm.currentWindowMetrics
        val screenBottom = metrics.bounds.height()
        val nav = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
        // Bornes du clavier incluant déjà la barre de navigation ? On ne la retire qu'une fois.
        val keyAreaBottom = if (kb.bottom > screenBottom - nav) kb.bottom - nav else kb.bottom

        val x = kb.left + (kb.width() * KEY_LEFT_RATIO).toInt() - 30 // 30 px à gauche, demande utilisateur
        val y = keyAreaBottom - dp(KEY_H_DP) - dp(4) + 50 // 50 px plus bas au total, demande utilisateur
        if (params.x == x && params.y == y) return
        params.x = x
        params.y = y
        wm.updateViewLayout(root, params)
    }

    // ----------------------------------------------------------- dictée

    private fun toggleRecording() {
        if (recording) stopSession() else startSession()
    }

    private fun startSession() {
        pushed = ""

        startForeground(
            NOTIF_ID, notification("Dictée en cours…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        recorder = Recorder().also { it.start() }
        recording = true

        key.background = keyBackground(active = true)
        keyView.active = true
        // le clavier peut s'être ouvert entre-temps : on se recale avant d'afficher le texte
        moveToKeyboard()

    }

    /** Retire les jetons de whisper entre crochets, les notes ♪ et les artefacts connus. */
    private fun cleanWhisper(text: String): String {
        if (ARTIFACTS.any { text.contains(it) }) return ""
        return text.replace(Regex("\\[[^\\]]*\\]|♪"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun stopSession() {
        recording = false
        keyView.active = false
        key.background = keyBackground(active = false)

        val rec = recorder
        recorder = null
        val raw = rec?.stop() ?: FloatArray(0)
        val pcm = Dsp.bandpass(raw)

        keyView.busy = true
        thread(name = "voxkey-final") {
            var finalText = ""
            val model = selectedModelPath()
            // porte de silence : sur du silence whisper hallucine ([Musique], sous-titres…)
            if (pcm.size >= 8000 && Dsp.rms(pcm) > Dsp.SILENCE_RMS && model != null && WhisperEngine.ensure(model)) {
                finalText = cleanWhisper(WhisperEngine.transcribe(pcm, "fr", 4))
            }

            main.post {
                keyView.busy = false
                if (finalText.isNotBlank()) {
                    // la transcription finale remplace exactement ce qui a été inséré
                    if (VoxkeyAccessibilityService.instance?.replaceText(pushed, finalText) != true) {
                        VoxkeyAccessibilityService.instance?.paste(finalText)
                    }
                    pushed = finalText
                }
                overlayStopForeground()
                if (!VoxkeyAccessibilityService.hasTextField) root.visibility = View.GONE
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
