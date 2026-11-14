package dev.voxkey.bench

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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
        // la bulle se lance seule dès que l'accessibilité est active (sans passer par l'app)
        startService(Intent(this, OverlayService::class.java))
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
        OverlayService.notifyKeyboard(imeBounds())
    }

    /** Rectangle du clavier à l'écran, ou null si aucun clavier n'est affiché. */
    fun imeBounds(): Rect? = windows
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable && node.isEnabled) node else null
    }

    /** Remplace, dans le champ actif, l'occurrence de [old] par [new], sans toucher au reste.
     *  Cherche [old] dans le texte courant ; à défaut, écrit [new] à la position du curseur. */
    fun replaceText(old: String, new: String): Boolean {
        val node = focusedEditable() ?: return false
        val text = node.text?.toString() ?: ""
        val sel = node.textSelectionStart
        val cursor = if (sel in 0..text.length) sel else text.length

        // old vide = rien de connu dans le champ : on insère au curseur.
        val idx = if (old.isEmpty()) -1 else nearestOccurrence(text, old, cursor)
        if (idx < 0) {
            select(cursor, cursor)
            // premier partiel : insertion au curseur, succès si le collage a marché
            if (old.isEmpty()) return pasteAndCheck(new)
            // old disparu (champ réécrit) : on ajoute quand même, mais on le signale
            paste(new)
            return false
        }
        if (!select(idx, idx + old.length)) return false
        return pasteAndCheck(new)
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

    private fun pasteAndCheck(new: String): Boolean {
        if (!paste(new)) return false
        return focusedEditable()?.text?.toString()?.contains(new) ?: false
    }

    /** Met [text] dans le presse-papier puis colle à la sélection courante. */
    fun paste(text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("voxkey", text))
        val node = focusedEditable() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
