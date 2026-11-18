package dev.voxkey.bench

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.WindowManager
import android.view.View
import android.view.WindowInsets
import dev.voxkey.bench.OverlayService.Companion.ATTACHED_OVERLAY
import dev.voxkey.bench.OverlayService.Companion.HIDE_GRACE_MS
import dev.voxkey.bench.OverlayService.Companion.KEY_H_DP
import dev.voxkey.bench.OverlayService.Companion.KEY_LEFT_RATIO
import dev.voxkey.bench.OverlayService.Companion.KEY_W_DP
import dev.voxkey.bench.OverlayService.Companion.SETTLE_MS

// Géométrie du clavier et greffe de la bulle sur la surface du clavier. Les champs correspondants
// restent déclarés dans `OverlayService` : une propriété d'extension n'a pas de stockage.

internal fun OverlayService.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal val OverlayService.attachMode: Boolean
    get() = canAttach && VoxkeyAccessibilityService.instance != null

/** Greffage possible sur la fenêtre du clavier : API 34+, et seulement si on l'a choisi.
 *  Décision indépendante de la connexion d'accessibilité : `buildViews` doit savoir s'il
 *  pose une fenêtre WindowManager ou non, et la connexion arrive souvent après `onCreate`.
 *  Poser la fenêtre puis greffer laissait la première orpheline à l'écran. */
internal val OverlayService.canAttach: Boolean
    get() = ATTACHED_OVERLAY && Build.VERSION.SDK_INT >= 34

/**
 * Traite une lecture de la zone clavier (événement d'accessibilité ou poll).
 *
 * Pendant l'animation du clavier, Gboard fait varier sa zone à chaque frame : la bulle ne suit
 * que les valeurs stables (SETTLE_MS), sinon elle « monte » avec le clavier. Elle apparaît tout
 * de suite à la dernière position stable connue, et disparaît dès que le clavier n'est plus lu.
 */
internal fun OverlayService.onKeyboardRead(kb: Rect?) {
    // Mode attaché : la surface suit la fenêtre du clavier d'elle-même. Aucun recalage.
    if (attachMode) {
        checkIme(SystemClock.uptimeMillis())
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

/**
 * Suit la fenêtre du clavier pour y accrocher ou décrocher la bulle.
 *
 * Appelée par le tick de 50 ms **et** par les événements d'accessibilité : la géométrie du
 * clavier est publiée par le système par à-coups (on l'a vue sauter de « fermé » à « ouvert »
 * sans valeur intermédiaire), donc attendre le tick suivant ne fait qu'ajouter du retard.
 */
internal fun OverlayService.checkIme(now: Long) {
    // La surface suit la fenêtre du clavier : une fois accrochée, elle glisse et disparaît
    // avec lui. Seul le rattachement à une nouvelle ouverture est de notre ressort.
    val a11y = VoxkeyAccessibilityService.instance ?: return
    val w = a11y.imeWindow()
    // Un seul pixel de clavier visible suffit : accrochée dès le début de la montée, la
    // surface suit ensuite l'animation d'elle-même. Attendre que le clavier soit levé de
    // 150 px ajoutait exactement le délai qu'on voyait à l'ouverture.
    // Sauf qu'une fenêtre basse (barre repliée, 37 px mesurés) n'est pas un clavier : la
    // placer et l'échantillonner dessus donnait une bulle noire collée en bas.
    val visible = w != null && w.first.top < wm.currentWindowMetrics.bounds.height() &&
        w.first.height() > dp(120)
    if (!visible) {
        // Clavier fermé : c'est ici que se jouait le bug de la bulle qui ne revenait jamais.
        // `imeWindow()` manque parfois une frame pendant l'animation, donc on note la fermeture
        // tout de suite (une réouverture doit rattacher) mais on ne libère la surface qu'après
        // une absence continue — sinon chaque ouverture clignotait.
        kbWasVisible = false
        imeSeenMs = 0L
        // Une dictée en cours garde sa surface même si Gboard se referme (bouton retour).
        if (overlaySc != null && !recording && now - lastImeMs > HIDE_GRACE_MS) detachBubble()
        return
    }
    lastImeMs = now
    // Diagnostic de latence : `ime seen` marque l'instant où la fenêtre du clavier apparaît,
    // et les lignes `attach`/`reparent` donnent le retard réel avant que la bulle soit posée.
    if (imeSeenMs == 0L) {
        imeSeenMs = now
        Log.d("VXK", "ime seen id=${w.second} top=${w.first.top} t=$now")
    }
    val sc = overlaySc
    when {
        sc == null -> attachTo(w.second, w.first)
        // Réouverture du clavier : on rattache toujours la surface à la fenêtre courante.
        // Le système réutilise le même id d'une ouverture à l'autre, et la surface d'une
        // fenêtre détruite ne revient jamais d'elle-même — c'est pour ça que la bulle
        // manquait à la réouverture. Un id inchangé ne prouve donc rien.
        w.second != attachedId || !kbWasVisible -> reparentTo(a11y, w.second, w.first)
    }
    // Fin de dictée clavier fermé : `stopSession` a masqué la vue, mais la surface reste
    // accrochée — sans ça, rien ne la remettait visible à la réouverture du clavier.
    if (root.visibility != View.VISIBLE) reveal()
    kbWasVisible = true
}

/** Recrée la fenêtre de la bulle (l'ancienne est orpheline, `visibility` n'y fait plus rien).
 *  Le WindowManager est repris sur la connexion d'accessibilité courante : celui capturé au
 *  démarrage porte le jeton de la connexion précédente, et `addView` échoue alors en
 *  `BadTokenException` — la bulle ne revenait jamais. */
internal fun OverlayService.reattach() {
    if (!OverlayService.running) return
    // En mode attaché, `root` n'appartient à aucune fenêtre WindowManager : l'ajouter ici en
    // ferait une fenêtre flottante classique, et la surface ne pourrait plus l'héberger.
    if (canAttach) {
        // Rien à réparer tant qu'aucune surface n'est accrochée : au démarrage à froid,
        // `EXTRA_REBUILD` arrive juste après `onCreate` et provoquait un second attachement
        // (deux hôtes de surface pour une seule bulle).
        if (overlaySc != null) detachBubble()
        return
    }
    try {
        wm.removeView(root)
    } catch (_: Exception) {
    }
    try {
        val a11y = VoxkeyAccessibilityService.instance
        wm = (a11y ?: this).getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
/** Crée l'hôte de surface une fois : il héberge [root] dans la fenêtre du clavier. */
internal fun OverlayService.ensureHost() {
    if (host != null) return
    val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val hostView = SurfaceControlViewHost(this, display, null as IBinder?)
    hostView.setView(root, SurfaceControlViewHost.LayoutParams(dp(KEY_W_DP), dp(KEY_H_DP), false))
    host = hostView
}

internal fun OverlayService.attachTo(id: Int, kb: Rect) {
    val a11y = VoxkeyAccessibilityService.instance ?: return
    val now = SystemClock.uptimeMillis()
    // Un attachement qui échoue était retenté à chaque tick (20 fois par seconde), en créant
    // une surface à chaque fois : elle restait vivante à la sortie de `attachTo` et la bulle
    // clignotait.
    if (now - lastAttachFailMs < 150) return
    try {
        // Hôte unique, créé une seule fois : une vue ne peut appartenir qu'à un seul hôte, et
        // `SurfaceControlViewHost.setView` deux fois sur la même vue échoue en silence.
        if (host == null) {
            buildViews()
            ensureHost()
        }
        val sc = host?.surfacePackage?.surfaceControl
            ?: throw IllegalStateException("surface absente")
        // La surface du ViewHost naît masquée (cf. test CTS) : l'allumer explicitement.
        SurfaceControl.Transaction().setVisibility(sc, true).apply()
        a11y.attachAccessibilityOverlayToWindow(id, sc)
        overlaySc = sc
        attachedId = id
        placeAttached(sc, kb)
        root.visibility = View.VISIBLE
        // Hiérarchie reconstruite : la touche doit reprendre son état d'écoute en cours.
        keyView.active = recording
        Log.d("VXK", "attach ok id=$id kb=$kb pos=$posX,$posY lag=${SystemClock.uptimeMillis() - imeSeenMs} t=${SystemClock.uptimeMillis()}")
    } catch (t: Throwable) {
        Log.e("VXK", "attach failed", t)
        lastAttachFailMs = now
        detachBubble()
    }
}

/** Nouvelle fenêtre de clavier (autre application) : la même surface y est replacée. */
internal fun OverlayService.reparentTo(a11y: VoxkeyAccessibilityService, id: Int, kb: Rect) {
    val sc = overlaySc ?: run { attachTo(id, kb); return }
    try {
        // Une surface rattachée à une fenêtre morte a pu être masquée avec elle : on la
        // rallume à chaque rattachement, sinon elle reste invisible sur la nouvelle fenêtre.
        SurfaceControl.Transaction().setVisibility(sc, true).apply()
        a11y.attachAccessibilityOverlayToWindow(id, sc)
        attachedId = id
        placeAttached(sc, kb)
        Log.d("VXK", "reparent ok id=$id kb=$kb lag=${SystemClock.uptimeMillis() - imeSeenMs} t=${SystemClock.uptimeMillis()}")
    } catch (t: Throwable) {
        Log.e("VXK", "reparent failed", t)
        // L'ancienne fenêtre est morte avec la surface : on repart d'un hôte neuf.
        detachBubble()
        lastAttachFailMs = SystemClock.uptimeMillis()
    }
}

internal fun OverlayService.detachBubble() {
    val sc = overlaySc
    overlaySc = null
    attachedId = 0
    kbWasVisible = false
    imeSeenMs = 0L
    if (sc != null) {
        try {
            SurfaceControl.Transaction().reparent(sc, null).apply()
        } catch (t: Throwable) {
            Log.e("VXK", "detach failed", t)
        }
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
internal fun OverlayService.placeAttached(sc: SurfaceControl, kb: Rect) {
    val metrics = wm.currentWindowMetrics
    val screenBottom = metrics.bounds.height()
    val top = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
    if (Bubble.custom(this)) {
        // Fractions du clavier : la bulle suit la rotation, un écran plié et la hauteur
        // variable du clavier, sans réglage par appareil.
        // Au moment du rattachement, le clavier n'a pas fini de monter : sa fenêtre est encore
        // collée en bas (mesurée à 38 px de haut, débordant sous l'écran) alors que sa hauteur
        // définitive, elle, est déjà bonne. On ancre donc sur le bas de l'écran, où le clavier
        // finit toujours, et sur la hauteur de la fenêtre.
        val w = dp(KEY_W_DP)
        val h = dp(KEY_H_DP)
        val kh = kb.height()
        posX = (kb.left + Bubble.x(this) * (kb.width() - w)).toInt()
        posY = (screenBottom - top - dp(4) - h - (1f - Bubble.y(this)) * (kh - h - dp(4))).toInt()
    } else {
        // Position d'origine, gardée telle quelle tant que l'utilisateur n'a rien réglé.
        posX = (kb.width() * KEY_LEFT_RATIO).toInt() - 30
        posY = screenBottom - dp(KEY_H_DP) - dp(4) - top
    }
    SurfaceControl.Transaction().setPosition(sc, posX.toFloat(), posY.toFloat()).setLayer(sc, 1).apply()
    applyBubbleColor()
    // Capture après la fin de l'animation, avec une seconde chance : pendant la montée, la
    // fenêtre du clavier laisse voir l'application derrière et la médiane prendrait sa couleur.
    main.postDelayed({ sampleBubbleColor() }, 500)
    main.postDelayed({ sampleBubbleColor() }, 1300)
    Log.d("VXK", "place pos=$posX,$posY ecran=$screenBottom haut=$top")
}

internal fun OverlayService.moveToKeyboard() {
    val kb = keyboard ?: return
    // Fenêtre morte ou pas encore posée : `updateViewLayout` lèverait.
    if (!rootReady || !root.isAttachedToWindow) return
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
