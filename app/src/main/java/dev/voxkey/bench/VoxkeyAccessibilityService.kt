package dev.voxkey.bench

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class VoxkeyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VXK"

        @Volatile
        var instance: VoxkeyAccessibilityService? = null

        @Volatile
        var hasTextField = false
    }

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "a11y connected")
        // la bulle se lance seule dès que l'accessibilité est active (sans passer par l'app).
        // `rebuild` : cette connexion a détruit les fenêtres de la précédente, qui sont orphelines.
        if (OverlayService.isEnabled(this)) {
            startService(
                Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_REBUILD, true)
            )
        }
    }

    override fun onDestroy() {
        instance = null
        hasTextField = false
        OverlayService.notifyFocus(false)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        // `windows` et `rootInActiveWindow` lèvent un `IllegalStateException` quand la connexion
        // d'accessibilité est en train de tomber (changement d'application, réveil) : l'exception
        // traversait ce rappel et tuait le processus, donc la bulle jusqu'au prochain lancement.
        runCatching {
            val has = focusedEditable() != null
            if (has != hasTextField) {
                hasTextField = has
                Log.d(TAG, "focus=$has")
                OverlayService.notifyFocus(has)
            }
            // le clavier peut apparaître/disparaître/bouger (rotation, thème, suggestions).
            // Ne jamais signaler « pas de clavier » sur la seule absence de champ éditable :
            // certaines apps (barre de recherche du launcher) ont un clavier sans nœud focalisable,
            // et la bulle disparaissait alors que le clavier était ouvert.
            // Diagnostic de latence : l'heure de l'appui permet de mesurer le délai réel entre
            // le toucher du champ et l'instant où la bulle est posée (`lag=` côté service).
            if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            ) Log.d(TAG, "evt=${event?.eventType} t=${SystemClock.uptimeMillis()}")
            OverlayService.notifyKeyboard(imeBounds())
        }.onFailure { Log.w(TAG, "event failed", it) }
    }

    /** Rectangle du clavier à l'écran, ou null si aucun clavier n'est affiché. */
    fun imeBounds(): Rect? = runCatching {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
    }.getOrNull()

    /** Zone et id de la fenêtre du clavier (l'id sert à y attacher une surcouche). */
    fun imeWindow(): Pair<Rect, Int>? = runCatching {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Pair(Rect().also { w.getBoundsInScreen(it) }, w.id) }
    }.getOrNull()

    /**
     * Couleur médiane du clavier, lue dans une capture de sa fenêtre.
     *
     * Gboard n'expose aucun thème : la capture est la seule source fiable. Elle ne quitte pas
     * la mémoire (aucun fichier), sert à teinter la bulle, et n'est prise qu'une fois par
     * ouverture de clavier par le service. Un clavier protégé refuse la capture : on garde
     * alors la couleur précédente.
     */
    fun sampleKeyboardColor(done: (Int) -> Unit) {
        val w = imeWindow() ?: return
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val color = medianColor(result, if (Build.VERSION.SDK_INT >= 34) null else w.first)
                if (color != 0) done(color)
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot failed code=$errorCode")
            }
        }
        runCatching {
            // API 34 : la fenêtre du clavier seule, donc sans notre bulle ni le reste de l'écran.
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(w.second, mainExecutor, callback)
            else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        }.onFailure { Log.w(TAG, "screenshot refused", it) }
    }

    /** Médiane par canal d'une grille de pixels du clavier ; 0 si la capture est inutilisable. */
    private fun medianColor(result: ScreenshotResult, crop: Rect?): Int {
        val bmp = try {
            val full = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) ?: return 0
            full.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            result.hardwareBuffer.close()
        } ?: return 0
        try {
            val zone = crop?.let { Rect(it) } ?: Rect(0, 0, bmp.width, bmp.height)
            if (!zone.intersect(0, 0, bmp.width, bmp.height) || zone.isEmpty) return 0
            // Une grille d'environ 1500 points : assez stable pour une médiane, sans lire les
            // 700 000 pixels du clavier.
            val step = maxOf(2, minOf(zone.width(), zone.height()) / 40)
            val r = ArrayList<Int>()
            val g = ArrayList<Int>()
            val b = ArrayList<Int>()
            var y = zone.top
            while (y < zone.bottom) {
                var x = zone.left
                while (x < zone.right) {
                    val c = bmp.getPixel(x, y)
                    r.add((c shr 16) and 0xFF)
                    g.add((c shr 8) and 0xFF)
                    b.add(c and 0xFF)
                    x += step
                }
                y += step
            }
            if (r.isEmpty()) return 0
            return (0xFF shl 24) or (median(r) shl 16) or (median(g) shl 8) or median(b)
        } finally {
            bmp.recycle()
        }
    }

    private fun median(v: ArrayList<Int>): Int {
        v.sort()
        return v[v.size / 2]
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable && node.isEnabled) node else null
    }

    /** Application au premier plan : sert à ne pas écrire dans le champ d'une autre app. */
    fun activePackage(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /** Remplace, dans le champ actif, l'occurrence de [old] par [new], sans toucher au reste.
     *  Cherche [old] dans le texte courant ; à défaut, écrit [new] à la position du curseur.
     *
     *  Le résultat dit seulement si le collage a été *envoyé* : ACTION_PASTE est asynchrone,
     *  relire le champ juste après renvoie encore l'ancien texte. Cette relecture faisait
     *  croire à un échec, et l'appelant collait une seconde fois — la transcription en double. */
    fun replaceText(old: String, new: String): Boolean {
        val node = focusedEditable() ?: return false
        val text = node.text?.toString() ?: ""
        val sel = node.textSelectionStart
        val cursor = if (sel in 0..text.length) sel else text.length

        // old vide = rien de connu dans le champ : on insère au curseur.
        val idx = if (old.isEmpty()) -1 else nearestOccurrence(text, old, cursor)
        if (idx < 0) {
            select(cursor, cursor)
            return paste(new)
        }
        if (!select(idx, idx + old.length)) return false
        return paste(new)
    }

    /** Occurrence de [old] la plus proche du curseur (celle qui le contient de préférence). */
    private fun nearestOccurrence(text: String, old: String, cursor: Int): Int {
        var best = -1
        var bestScore = Int.MAX_VALUE
        var i = text.indexOf(old)
        while (i >= 0) {
            val score = when {
                cursor in i..(i + old.length) -> 0
                cursor < i -> i - cursor
                else -> cursor - (i + old.length)
            }
            if (score < bestScore) {
                bestScore = score
                best = i
            }
            i = text.indexOf(old, i + 1)
        }
        return best
    }

    private fun select(start: Int, end: Int): Boolean {
        val node = focusedEditable() ?: return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    /** Met [text] dans le presse-papier puis colle à la sélection courante. */
    fun paste(text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("voxkey", text))
        val node = focusedEditable() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
