package dev.voxkey.bench

import android.content.Context

/**
 * Accent courant de l'application : une préférence, un cache, et ses dérivés.
 *
 * Le cache est posé au premier contexte disponible (l'activité ou la bulle de dictée) :
 * la bulle peut apparaître avant l'activité sans dépendre d'elle, et aucun autre point
 * d'entrée n'est nécessaire. Le défaut est le jaune d'origine.
 */
object Accent {

    /** Clé de préférence, dans le même fichier que le reste des réglages. */
    private const val KEY = "accent"
    const val DEFAULT = 0xFFFFD400.toInt()

    /** Cinq accents lisibles sur le fond de l'app, du plus vif au plus doux (libellé, couleur). */
    val palette = listOf(
        R.string.accent_yellow to 0xFFFFD400.toInt(),
        R.string.accent_blue to 0xFF4C8DFF.toInt(),
        R.string.accent_green to 0xFF3ED98B.toInt(),
        R.string.accent_purple to 0xFFB49BFF.toInt(),
        R.string.accent_orange to 0xFFFF8F45.toInt(),
    )

    private var cached = DEFAULT
    private var warm = false

    /** Lit la préférence une fois ; [ctx] sert seulement au premier appel. */
    fun current(ctx: Context? = null): Int {
        if (!warm) ctx?.let {
            cached = it.applicationContext
                .getSharedPreferences("voxkey", Context.MODE_PRIVATE)
                .getInt(KEY, DEFAULT)
            warm = true
        }
        return cached
    }

    fun set(ctx: Context, color: Int) {
        cached = color
        warm = true
        ctx.applicationContext.getSharedPreferences("voxkey", Context.MODE_PRIVATE)
            .edit().putInt(KEY, color).apply()
    }

    /** Libellé de l'accent courant, ou « Personnalisé » s'il ne vient pas de la palette. */
    fun nameRes(): Int = palette.firstOrNull { it.second == current() }?.first ?: R.string.accent_custom

    /** Encre posée sur l'accent : on garde celle qui contraste le plus, jamais une valeur figée. */
    fun on(color: Int): Int =
        if (contrast(color, INK_DARK) >= contrast(color, INK_LIGHT)) INK_DARK else INK_LIGHT

    /** Fond teinté à faible opacité derrière une icône accentuée. */
    fun bg(color: Int): Int = (color and 0x00FFFFFF) or 0x26000000

    /** Quatre tons du même accent, du plus terne au plein : l'échelle de la grille de jours. */
    fun ramp(): IntArray = floatArrayOf(0.18f, 0.40f, 0.66f, 1f)
        .map { mix(Ui.BG, current(), it) }
        .toIntArray()

    /** Éclaircit un accent, pour le haut des barres de la bulle. */
    fun light(color: Int, t: Float = 0.35f): Int = mix(color, 0xFFFFFFFF.toInt(), t)

    private fun mix(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Rapport de contraste WCAG entre deux couleurs opaques. */
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun ch(shift: Int): Double {
            val v = ((c shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private const val INK_DARK = 0xFF14161B.toInt()
    private const val INK_LIGHT = 0xFFFFFFFF.toInt()
}
