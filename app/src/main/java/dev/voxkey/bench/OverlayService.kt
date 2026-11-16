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
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
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
        private const val ATTACH_GRACE_MS = 2500L

        /**
         * Bulle greffée sur la surface du clavier (`SurfaceControlViewHost`), essai de la branche
         * `exp/attached-overlay`. Désactivée : à la réouverture du clavier la surface restait
         * invisible (le système réutilise l'id de fenêtre IME, la surface survit au clavier fermé
         * et la couche se retrouve sous le clavier), et chaque attachement laissait une surface
         * orpheline. Le repli fenêtre `TYPE_ACCESSIBILITY_OVERLAY` est stable : la bulle nous
         * appartient, on la montre et on la masque nous-mêmes.
         */
        private const val ATTACHED_OVERLAY = false

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

        /** Redemande la recréation de la fenêtre (connexion d'accessibilité renouvelée). */
        const val EXTRA_REBUILD = "rebuild"
        private const val PREF_ENABLED = "bubble_enabled"

        /** Bulle voulue par l'utilisateur (Réglages). Par défaut : activée. */
        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences("voxkey", MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("voxkey", MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, on).apply()
            val intent = Intent(ctx, OverlayService::class.java)
            if (on) ctx.startService(intent) else ctx.stopService(intent)
        }

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
    private var kbSeen = false
    /** Libération différée du modèle : annulée dès qu'une nouvelle dictée commence. */
    private val releaseModel = Runnable {
        runCatching { ParakeetEngine.release() }
        runCatching { WhisperEngine.release() }
    }
    /** Dernière tentative de recréation de la fenêtre (anti-boucle). */
    private var lastReattachMs = 0L
    /** Dernier échec d'attachement à la fenêtre du clavier (anti-boucle). */
    private var lastAttachFailMs = 0L

    // Surcouche attachée à la fenêtre du clavier (API 34+) : elle est composée dans la fenêtre de
    // Gboard, donc elle suit ses animations sans polling ni recalage. Sinon, repli WindowManager.
    private var host: SurfaceControlViewHost? = null
    private var overlaySc: SurfaceControl? = null
    private var attachedId = 0
    private var posX = 0
    private var posY = 0
    private val attachMode: Boolean
        get() = ATTACHED_OVERLAY && Build.VERSION.SDK_INT >= 34 && VoxkeyAccessibilityService.instance != null
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("voxkey", MODE_PRIVATE) }

    private var recorder: Recorder? = null

    @Volatile
    private var recording = false

    /** Texte actuellement présent dans le champ (thread principal seulement). */
    private var pushed = ""
    /** Application active au début de la dictée : le texte n'est inséré que si on y est encore. */
    private var startPkg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(applicationContext)
        if (!isEnabled(this)) {
            stopSelf()
            return
        }
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
                // Ces deux écouteurs arrivent sur le thread principal : sans filet, une vue
                // détachée (`updateViewLayout` sur une fenêtre morte, vue ci-dessous) tuait le
                // processus — la bulle ne revenait alors qu'en relançant l'application.
                runCatching {
                    hiddenByFocus = !has
                    if (!recording) {
                        // Ne pas masquer tant qu'un clavier est à l'écran : les ROM envoient des
                        // événements de focus `false` sur les champs custom, ce qui faisait clignoter
                        // la bulle hors de l'écran.
                        if (attachMode) return@post
                        if (has) moveToKeyboard() else if (keyboard == null) root.visibility = View.GONE
                    }
                }.onFailure { Log.e("VXK", "focus failed", it) }
            }
        }
        keyboardListener = { bounds ->
            main.post {
                runCatching { onKeyboardRead(bounds) }.onFailure { Log.e("VXK", "kb failed", it) }
            }
        }
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
        // Mode attaché : la surface suit la fenêtre du clavier d'elle-même. Aucun recalage.
        if (attachMode) {
            checkIme(SystemClock.uptimeMillis(), "evt")
            return
        }
        val now = SystemClock.uptimeMillis()
        if ((kb != null) != kbSeen) {
            kbSeen = kb != null
            Log.d("VXK", if (kb == null) "kb lost" else "kb on $kb")
        }
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
            try {
                // Filet de sécurité pour une fenêtre détruite sans que le service le sache.
                val now = SystemClock.uptimeMillis()
                // En mode attaché, `root` n'est pas dans une fenêtre `wm` : `reattach()` n'aurait
                // qu'un effet, relâcher la surface toutes les 2 s et faire clignoter la bulle.
                if (!attachMode && !root.isAttachedToWindow && now - lastReattachMs > 2000) {
                    lastReattachMs = now
                    reattach()
                }
                val a11y = VoxkeyAccessibilityService.instance
                if (attachMode && a11y != null) {
                    checkIme(now, "poll")
                } else if (recording) {
                    if (root.visibility != View.VISIBLE) reveal()
                } else if (a11y != null) {
                    // `hasTextField` n'est pas fiable sur les champs custom : la géométrie du
                    // clavier suffit à savoir qu'un champ est actif.
                    val kb = a11y.imeBounds()
                    if (kb != null) {
                        onKeyboardRead(kb)
                    } else {
                        // Pas de repli ici : réafficher la bulle hors clavier la faisait clignoter
                        // puis rester à l'écran après la fermeture du clavier.
                        onKeyboardRead(null)
                    }
                }
            } catch (t: Throwable) {
                // Sans ça, une exception ici arrêterait définitivement la surveillance.
                Log.e("VXK", "poll failed", t)
            }
            if (running) main.postDelayed(this, POLL_MS)
        }
    }

    /**
     * Suit la fenêtre du clavier pour y accrocher ou décrocher la bulle.
     *
     * Appelée par le tick de 50 ms **et** par les événements d'accessibilité : la géométrie du
     * clavier est publiée par le système par à-coups (on l'a vue sauter de « fermé » à « ouvert »
     * sans valeur intermédiaire), donc attendre le tick suivant ne fait qu'ajouter du retard.
     */
    private fun checkIme(now: Long, src: String) {
        // Seul travail : suivre l'identité de la fenêtre du clavier. La position et la visibilité
        // sont portées par la fenêtre parente.
        val w = (VoxkeyAccessibilityService.instance ?: return).imeWindow()
        if (w == null) {
            // `imeWindow()` renvoie null par intermittence même clavier ouvert : détacher trop
            // tôt faisait disparaître la bulle en pleine saisie.
            if (now - lastKbMs > ATTACH_GRACE_MS) detachBubble()
            return
        }
        lastKbMs = now
        // La fenêtre IME survit clavier fermé (elle glisse sous l'écran : bornes à ~2300..3260 au
        // lieu de 1509..2392), et le système réutilise le même id d'une application à l'autre :
        // un id inchangé ne prouve pas que la surface est encore accrochée à une fenêtre vivante.
        val visible = w.first.top < wm.currentWindowMetrics.bounds.height() - 300
        // On ne détache pas quand le clavier se ferme : la fenêtre de Gboard est unique et persiste
        // d'une application à l'autre, et une surface déjà en place glisse avec le clavier dès la
        // première image. La détacher obligeait à la reconstruire à chaque application, et la bulle
        // n'arrivait qu'une fois la géométrie finale publiée.
        if (!visible) return
        if (w.second != attachedId || overlaySc == null) {
            attachTo(w.second, w.first)
        } else if (root.visibility != View.VISIBLE) {
            // Fin de dictée clavier fermé : `stopSession` a masqué la vue, mais la fenêtre du
            // clavier survit avec le même id et la surface reste accrochée — rien ne remettait
            // la vue en visible, donc la bulle ne revenait jamais à la réouverture du clavier.
            reveal()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Une connexion d'accessibilité renouvelée détruit les fenêtres qui en dépendent ;
        // le service, lui, survit : on recrée la sienne, sinon elle ne revient jamais.
        if (intent?.getBooleanExtra(EXTRA_REBUILD, false) == true && this::root.isInitialized) {
            main.post { reattach() }
        }
        return START_STICKY
    }

    /** Recrée la fenêtre de la bulle (l'ancienne est orpheline, `visibility` n'y fait plus rien).
     *  Le WindowManager est repris sur la connexion d'accessibilité courante : celui capturé au
     *  démarrage porte le jeton de la connexion précédente, et `addView` échoue alors en
     *  `BadTokenException` — la bulle ne revenait jamais. */
    private fun reattach() {
        if (!running) return
        // En mode attaché, `root` n'appartient à aucune fenêtre WindowManager : l'ajouter ici en
        // ferait une fenêtre flottante classique, et la surface ne pourrait plus l'héberger.
        if (attachMode) {
            detachBubble()
            return
        }
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        try {
            val a11y = VoxkeyAccessibilityService.instance
            wm = (a11y ?: this).getSystemService(WINDOW_SERVICE) as WindowManager
            params.type = if (a11y != null)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            // WMS réécrit `token` dans nos params au premier ajout : le garder ferait échouer
            // tous les ajouts suivants en `BadTokenException` (connexion morte).
            params.token = null
            wm.addView(root, params)
            root.visibility = if (recording) View.VISIBLE else View.GONE
            Log.d("VXK", "reattach ok a11y=${a11y != null}")
        } catch (t: Throwable) {
            Log.e("VXK", "reattach failed", t)
        }
    }

    override fun onDestroy() {
        focusListener = null
        keyboardListener = null
        main.removeCallbacks(poll)
        running = false
        recording = false
        // `onCreate` sort tôt quand la bulle est désactivée ou la permission absente : les vues
        // n'existent alors pas, et toucher un `lateinit` ici tuait le processus (crash à la
        // désactivation de la bulle dans les Réglages).
        if (this::keyView.isInitialized) keyView.active = false
        detachBubble()
        if (this::wm.isInitialized && this::root.isInitialized) {
            try {
                wm.removeView(root)
            } catch (_: Exception) {
            }
        }
        main.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        main.postDelayed(releaseModel, 1000)
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
        if (!attachMode) {
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
     * Surface de touche : sombre et opaque, sans bordure.
     * En écoute, un liseré jaune reprend l'accent de l'app sur la même matière.
     */
    private fun keyBackground(active: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        setColor(0xFF292E33.toInt())
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
    /**
     * Attache la bulle à la fenêtre du clavier.
     *
     * Sans permission supplémentaire, la surface devient un enfant de la fenêtre de Gboard : elle
     * apparaît, glisse et disparaît avec lui. `focusable = false` est indispensable, sinon la
     * surcouche prend le focus au champ texte et le clavier ne revient plus (`setView(v, w, h)`
     * crée justement une fenêtre focalisable).
     */
    private fun attachTo(id: Int, kb: Rect) {
        val a11y = VoxkeyAccessibilityService.instance ?: return
        val now = SystemClock.uptimeMillis()
        // Un attachement qui échoue était retenté à chaque tick (20 fois par seconde), en créant
        // une surface à chaque fois : elle restait vivante à la sortie de `attachTo` (le `catch`
        // appelait `detachBubble`, qui ne connaît pas l'hôte local) et la bulle clignotait.
        if (now - lastAttachFailMs < 500) return
        detachBubble()
        // Une View ne peut appartenir qu'à un seul hôte, et `release()` ne la détache pas : la
        // réutiliser faisait échouer silencieusement le second attachement (bulle perdue à la
        // réouverture du clavier). On reconstruit donc la hiérarchie à chaque fois.
        buildViews()
        var vh: SurfaceControlViewHost? = null
        try {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val hostView = SurfaceControlViewHost(this, display, null as IBinder?)
            vh = hostView
            hostView.setView(root, SurfaceControlViewHost.LayoutParams(dp(KEY_W_DP), dp(KEY_H_DP), false))
            val sc = hostView.surfacePackage?.surfaceControl ?: run { hostView.release(); return }
            // La surface du ViewHost naît masquée (cf. test CTS) : l'allumer explicitement.
            SurfaceControl.Transaction().setVisibility(sc, true).apply()
            a11y.attachAccessibilityOverlayToWindow(id, sc)
            placeAttached(sc, kb)
            host = hostView
            overlaySc = sc
            attachedId = id
            root.visibility = View.VISIBLE
            // Hiérarchie reconstruite : la touche doit reprendre son état d'écoute en cours.
            keyView.active = recording
            Log.d("VXK", "attach ok id=$id kb=$kb pos=$posX,$posY")
        } catch (t: Throwable) {
            Log.e("VXK", "attach failed", t)
            lastAttachFailMs = now
            vh?.release()
            detachBubble()
        }
    }

    private fun detachBubble() {
        val sc = overlaySc ?: return
        overlaySc = null
        attachedId = 0
        try {
            SurfaceControl.Transaction().reparent(sc, null).apply()
        } catch (t: Throwable) {
            Log.e("VXK", "detach failed", t)
        }
        host?.release()
        host = null
    }

    /**
     * Position dans le repère de la surface du clavier.
     *
     * Ce repère n'est pas celui des 883 px visibles du clavier : la surface de la fenêtre IME
     * couvre tout l'écran sous la barre d'état. Vérifié au compositeur —
     * `VRI-InputMethod` occupe 0,126 → 1080,2392 sur un écran de 2392 px. Son origine est donc le
     * haut de la barre d'état, et son bas celui de l'écran ; rien de la géométrie du clavier
     * n'entre dans le calcul, ce qui rend la position insensible aux animations de Gboard.
     *
     * Sans le « +50 px » du placement historique : la couche est découpée aux bornes de la
     * surface parente, qui s'arrête au bas de l'écran.
     */
    private fun placeAttached(sc: SurfaceControl, kb: Rect) {
        val metrics = wm.currentWindowMetrics
        val screenBottom = metrics.bounds.height()
        val top = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        posX = (kb.width() * KEY_LEFT_RATIO).toInt() - 30
        posY = screenBottom - dp(KEY_H_DP) - dp(4) - top
        SurfaceControl.Transaction().setPosition(sc, posX.toFloat(), posY.toFloat()).setLayer(sc, 1).apply()
        Log.d("VXK", "place pos=$posX,$posY ecran=$screenBottom haut=$top")
    }

    private fun moveToKeyboard() {
        val kb = keyboard ?: return
        // Fenêtre morte ou pas encore posée : `updateViewLayout` lèverait.
        if (!this::root.isInitialized || !root.isAttachedToWindow) return
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
        // Une dictée est encore en cours de transcription : en démarrer une autre ferait écrire
        // deux textes dans le même champ (le résultat précédent arrive après).
        if (!recording && keyView.busy) return
        if (recording) stopSession() else startSession()
    }

    private fun startSession() {
        pushed = ""
        startPkg = VoxkeyAccessibilityService.instance?.activePackage()
        // `startForeground` (type micro) et l'ouverture du micro peuvent échouer : permission
        // retirée, micro pris par une autre application, démarrage refusé en arrière-plan.
        // Sans ce filet, l'exception remontait au gestionnaire de clic et tuait le processus.
        try {
            startForeground(
                NOTIF_ID, notification(getString(R.string.notif_dictating)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            recorder = Recorder().also { it.start() }
        } catch (t: Throwable) {
            Log.e("VXK", "start failed", t)
            runCatching { recorder?.stop() }
            recorder = null
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            key.background = keyBackground(active = false)
            keyView.active = false
            return
        }
        recording = true

        // Le chargement (2 s pour parakeet q8) se fait pendant la dictée, plus au stop.
        main.removeCallbacks(releaseModel)
        preloadModel()

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

    /**
     * Une abréviation dictée seule devient le texte complet enregistré pour ce mot-clé.
     * Correspondance sur la dictée entière uniquement : « signature » est aussi un mot courant,
     * l'étendre au milieu d'une phrase abîmerait le texte.
     */
    private fun expandKeywords(text: String): String {
        if (text.isBlank()) return text
        val bare = text.trim().trimEnd('.', '!', '?', ',', ';', ':', ' ').lowercase()
        val hit = runCatching {
            Store.keywords().firstOrNull { it.trigger.trim().lowercase() == bare }
        }.getOrNull() ?: return text
        Log.d("VXK", "keyword « ${hit.trigger} » → ${hit.replacement.length} chars")
        return hit.replacement
    }

    /** Charge le modèle en tâche de fond : `ensure()` au stop le trouvera déjà prêt. */
    private fun preloadModel() {
        val model = selectedModelPath() ?: return
        thread(name = "voxkey-load") {
            // Un modèle absent ou corrompu lève ici ; sans filet, le thread non capturé tuait
            // le processus, donc la bulle.
            runCatching {
                if (isParakeetModel(model)) ParakeetEngine.ensure(model) else WhisperEngine.ensure(model)
            }.onFailure { Log.e("VXK", "preload failed", it) }
        }
    }

    private fun stopSession() {
        recording = false
        keyView.active = false
        key.background = keyBackground(active = false)

        val rec = recorder
        recorder = null
        val raw = try {
            rec?.stop() ?: FloatArray(0)
        } catch (t: Throwable) {
            Log.e("VXK", "stop failed", t)
            FloatArray(0)
        }
        val pcm = Dsp.bandpass(raw)

        keyView.busy = true
        thread(name = "voxkey-final") {
            var finalText = ""
            val model = selectedModelPath()
            val parakeet = model?.let { isParakeetModel(it) } == true
            try {
                val ready = if (parakeet) ParakeetEngine.ensure(model!!) else model != null && WhisperEngine.ensure(model)
                // porte de silence : sur du silence whisper hallucine ([Musique], sous-titres…)
                if (pcm.size >= 8000 && Dsp.rms(pcm) > Dsp.SILENCE_RMS && ready) {
                    finalText = expandKeywords(
                        cleanWhisper(
                            if (parakeet) ParakeetEngine.transcribe(pcm) else WhisperEngine.transcribe(pcm, "fr", 4)
                        )
                    )
                }
                Log.d("VXK", "final model=${model?.substringAfterLast('/')} parakeet=$parakeet ready=$ready pcm=${pcm.size} chars=${finalText.length}")
            } catch (t: Throwable) {
                // Sans ce filet, une erreur du moteur laissait l'animation « recherche » pour toujours.
                Log.e("VXK", "final failed", t)
            } finally {
                main.post {
                    keyView.busy = false
                    if (finalText.isNotBlank()) {
                        val a11y = VoxkeyAccessibilityService.instance
                        // Même application qu'au départ seulement : sinon, après un changement
                        // d'écran pendant la transcription, le texte atterrit dans le champ d'une
                        // autre app. Le texte reste de toute façon dans l'historique.
                        val sameApp = startPkg == null || a11y?.activePackage() == startPkg
                        if (a11y != null && sameApp) {
                            // la transcription finale remplace exactement ce qui a été inséré
                            if (!a11y.replaceText(pushed, finalText)) a11y.paste(finalText)
                            pushed = finalText
                        } else {
                            Log.d("VXK", "insert skipped start=$startPkg now=${a11y?.activePackage()}")
                        }
                    }
                    overlayStopForeground()
                    if (!VoxkeyAccessibilityService.hasTextField) root.visibility = View.GONE
                    main.postDelayed(releaseModel, 60_000)
                }
            }
            // Une exception ici tuerait le processus (thread sans filet) : plus de bulle du tout.
            if (finalText.isNotBlank()) {
                runCatching { Store.addDictation(finalText) }
                    .onFailure { Log.e("VXK", "history failed", it) }
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
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun overlayStopForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
