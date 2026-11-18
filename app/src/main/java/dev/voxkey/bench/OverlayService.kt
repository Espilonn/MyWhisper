package dev.voxkey.bench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.File
import kotlin.concurrent.thread

/**
 * Bulle flottante + dictée.
 *
 * Service démarré normalement (pas de notification au repos). Il passe en
 * foreground seulement pendant l'enregistrement, car Android exige un service
 * foreground pour accéder au micro en arrière-plan.
 *
 * La géométrie du clavier vit dans `BubblePlacement.kt`, la construction des vues dans
 * `BubbleView.kt` et le post-traitement du texte dans `PostProcess.kt`.
 */
class OverlayService : Service() {

    companion object {
        private const val TICK_MS = 700L
        private const val PARTIAL_WINDOW_SAMPLES = 16000 * 4
        private const val CHANNEL_ID = "voxkey-dictation"
        private const val NOTIF_ID = 42
        /** Intervalle de lecture du clavier (filet de sécurité ; les événements d'accessibilité passent avant). */
        internal const val POLL_MS = 50L
        /** Une zone clavier n'est suivie qu'une fois identique pendant ce délai : pas les frames d'animation. */
        internal const val SETTLE_MS = 16L
        /** Délai avant de masquer la touche quand le clavier n'est plus lu du tout. */
        internal const val HIDE_GRACE_MS = 400L
        /** Une capture de clavier par ouverture suffit : le système limite leur fréquence. */
        internal const val SAMPLE_MS = 2000L

        /**
         * Bulle greffée sur la surface du clavier (`SurfaceControlViewHost`).
         *
         * Mesuré : c'est le seul mode où la bulle apparaît sans délai (elle est déjà dans la
         * fenêtre de Gboard, donc elle suit son animation) et où elle disparaît avec le clavier,
         * sans traîner. Le mode fenêtre `TYPE_ACCESSIBILITY_OVERLAY` a été retenté pour anticiper
         * l'ouverture depuis le focus : la bulle y restait affichée après la fermeture de
         * l'application et n'apparaissait toujours qu'après l'animation. Ne pas y revenir.
         */
        internal const val ATTACHED_OVERLAY = true
        /** Touche de dictée : mêmes proportions qu'une touche de la rangée du bas. */
        internal const val KEY_W_DP = 56
        internal const val KEY_H_DP = 44
        /** Part de la largeur du clavier laissée à sa gauche (touches ?123 et virgule). */
        internal const val KEY_LEFT_RATIO = 0.24f

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

    // Champs des vues (`BubbleView.kt`) et de la géométrie du clavier (`BubblePlacement.kt`).
    internal lateinit var wm: WindowManager
    internal lateinit var root: LinearLayout
    internal lateinit var key: FrameLayout
    internal lateinit var keyView: WaveBarsView
    internal lateinit var params: WindowManager.LayoutParams

    /** Dernière zone clavier stable (emplacement connu, gardé même après fermeture). */
    internal var keyboard: Rect? = null
    /** Dernière lecture brute, et l'instant où elle a été vue pour la première fois. */
    internal var candidate: Rect? = null
    internal var candidateMs = 0L
    internal var lastKbMs = 0L
    /** Vrai après une perte de focus : Gboard se referme encore, on ne doit pas réafficher la bulle. */
    internal var hiddenByFocus = false
    internal var kbSeen = false
    /** Libération différée du modèle : annulée dès qu'une nouvelle dictée commence. */
    private val releaseModel = Runnable {
        runCatching { ParakeetEngine.release() }
        runCatching { WhisperEngine.release() }
    }
    /** Dernière tentative de recréation de la fenêtre (anti-boucle). */
    private var lastReattachMs = 0L
    /** Dernier échec d'attachement à la fenêtre du clavier (anti-boucle). */
    internal var lastAttachFailMs = 0L

    // Surcouche attachée à la fenêtre du clavier (API 34+) : elle est composée dans la fenêtre de
    // Gboard, donc elle suit ses animations sans polling ni recalage. Sinon, repli WindowManager.
    internal var host: SurfaceControlViewHost? = null
    internal var overlaySc: SurfaceControl? = null
    internal var attachedId = 0
    /** Dernier instant où la fenêtre du clavier a été vue (délai de grâce avant libération). */
    internal var lastImeMs = 0L
    /** Instant où la fenêtre du clavier a été vue pour la première fois (diagnostic de latence). */
    internal var imeSeenMs = 0L
    /** Vrai quand le clavier était ouvert au dernier tick (une réouverture force un rattachement). */
    internal var kbWasVisible = false
    internal var posX = 0
    internal var posY = 0
    /** Dernière capture de la couleur du clavier (anti-rebond : une par ouverture). */
    internal var lastSampleMs = 0L
    internal val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("voxkey", MODE_PRIVATE) }

    private var recorder: Recorder? = null

    @Volatile
    internal var recording = false

    /** Texte actuellement présent dans le champ (thread principal seulement). */
    private var pushed = ""
    /** Application active au début de la dictée : le texte n'est inséré que si on y est encore. */
    private var startPkg: String? = null

    // `this::root.isInitialized` lit le champ de stockage : hors de la classe, seuls ces
    // accesseurs sont compilables (utilisés par `BubbleView.kt` et `BubblePlacement.kt`).
    internal val rootReady: Boolean get() = this::root.isInitialized
    internal val keyReady: Boolean get() = this::key.isInitialized

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
        // Hôte préparé dès le démarrage : à la première ouverture du clavier, il ne reste plus
        // qu'à accrocher la surface, au lieu de gonfler les vues et de créer l'hôte à ce moment.
        if (canAttach) runCatching { ensureHost() }.onFailure { Log.e("VXK", "host failed", it) }
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

    /** Filet de sécurité : relit le clavier à intervalle court, et gère l'enregistrement. */
    private val poll = object : Runnable {
        override fun run() {
            try {
                // Filet de sécurité pour une fenêtre détruite sans que le service le sache.
                val now = SystemClock.uptimeMillis()
                // En mode attaché, `root` n'est pas dans une fenêtre `wm` : `reattach()` n'aurait
                // qu'un effet, relâcher la surface toutes les 2 s et faire clignoter la bulle.
                if (!canAttach && !root.isAttachedToWindow && now - lastReattachMs > 2000) {
                    lastReattachMs = now
                    reattach()
                }
                val a11y = VoxkeyAccessibilityService.instance
                if (attachMode && a11y != null) {
                    checkIme(now)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Une connexion d'accessibilité renouvelée détruit les fenêtres qui en dépendent ;
        // le service, lui, survit : on recrée la sienne, sinon elle ne revient jamais.
        if (intent?.getBooleanExtra(EXTRA_REBUILD, false) == true && this::root.isInitialized) {
            main.post { reattach() }
        }
        return START_STICKY
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

    // ----------------------------------------------------------- dictée

    internal fun toggleRecording() {
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

    /** Charge le modèle en tâche de fond : `ensure()` au stop le trouvera déjà prêt. */
    private fun preloadModel() {
        // En mode cloud, rien à charger : le modèle distant n'occupe pas la mémoire du téléphone.
        if (RemoteEngine.isCloud(this)) return
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
            val cloud = RemoteEngine.isCloud(this@OverlayService)
            val model = if (cloud) null else selectedModelPath()
            val parakeet = model?.let { isParakeetModel(it) } == true
            try {
                val ready = if (cloud) RemoteEngine.key(this@OverlayService).isNotBlank()
                    else if (parakeet) ParakeetEngine.ensure(model!!) else model != null && WhisperEngine.ensure(model)
                // porte de silence : sur du silence whisper hallucine ([Musique], sous-titres…)
                if (pcm.size >= 8000 && Dsp.rms(pcm) > Dsp.SILENCE_RMS && ready) {
                    val raw = try {
                        if (cloud) RemoteEngine.transcribe(this@OverlayService, pcm)
                        else if (parakeet) ParakeetEngine.transcribe(pcm) else WhisperEngine.transcribe(pcm, "fr", 4)
                    } catch (t: Throwable) {
                        // Cloud en échec (réseau, quota, clé) : on retombe sur l'appareil si un
                        // modèle est installé, sinon l'erreur remonte et le journal la garde.
                        Log.e("VXK", "cloud failed", t)
                        val local = selectedModelPath() ?: throw t
                        when {
                            isParakeetModel(local) && ParakeetEngine.ensure(local) -> ParakeetEngine.transcribe(pcm)
                            WhisperEngine.ensure(local) -> WhisperEngine.transcribe(pcm, "fr", 4)
                            else -> throw t
                        }
                    }
                    finalText = expandKeywords(cleanWhisper(raw))
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
