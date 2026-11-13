package dev.voxkey.bench

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class VoxkeyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: VoxkeyAccessibilityService? = null

        @Volatile
        var hasTextField = false
    }

    override fun onServiceConnected() {
        instance = this
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
        val editable = focusedEditable() != null
        if (editable != hasTextField) {
            hasTextField = editable
            OverlayService.notifyFocus(editable)
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable && node.isEnabled) node else null
    }

    /** Texte du champ et position du curseur au moment où la dictée commence. */
    fun capture(): Pair<String, Int>? {
        val node = focusedEditable() ?: return null
        val text = node.text?.toString() ?: ""
        val sel = node.textSelectionStart
        val cursor = if (sel in 0..text.length) sel else text.length
        return text to cursor
    }

    /** Remplace tout le contenu du champ et replace le curseur. */
    fun applyText(full: String, cursor: Int): Boolean {
        val node = focusedEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false

        focusedEditable()?.let { n2 ->
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            n2.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return true
    }

    /** Repli quand le champ refuse ACTION_SET_TEXT : presse-papier + collage. */
    fun paste(text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("voxkey", text))
        val node = focusedEditable() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
