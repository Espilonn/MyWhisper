package dev.voxkey.bench

import android.content.Context

/**
 * Réglages de la bulle : sa place dans la fenêtre du clavier, et le fond de la touche.
 *
 * La position est faite de deux fractions du clavier (0 = bord gauche ou haut, 1 = bord droit
 * ou bas) : elle suit donc la rotation, un écran plié et la hauteur variable de Gboard sans
 * réglage par appareil. Tant que l'utilisateur n'y a pas touché, la position d'origine du
 * service est gardée telle quelle, au pixel près.
 */
object Bubble {

    private const val KEY_X = "bubble_x"
    private const val KEY_Y = "bubble_y"
    private const val KEY_MODE = "bubble_color"
    private const val KEY_SAMPLED = "bubble_color_sampled"

    const val MODE_KEYBOARD = "keyboard"
    const val MODE_DARK = "dark"
    const val MODE_ACCENT = "accent"

    /** Teinte d'origine de la bulle, et repli tant qu'aucune couleur de clavier n'a été lue. */
    const val DARK = 0xFF292E33.toInt()

    private fun p(ctx: Context) = ctx.getSharedPreferences("voxkey", Context.MODE_PRIVATE)

    /** Faux tant que rien n'a été réglé : le service garde alors sa position historique. */
    fun custom(ctx: Context): Boolean = p(ctx).contains(KEY_X) || p(ctx).contains(KEY_Y)

    fun x(ctx: Context): Float = p(ctx).getFloat(KEY_X, 0f)
    fun y(ctx: Context): Float = p(ctx).getFloat(KEY_Y, 1f)

    fun setPosition(ctx: Context, x: Float, y: Float) {
        p(ctx).edit().putFloat(KEY_X, x).putFloat(KEY_Y, y).apply()
    }

    fun reset(ctx: Context) {
        p(ctx).edit().remove(KEY_X).remove(KEY_Y).apply()
    }

    fun mode(ctx: Context): String = p(ctx).getString(KEY_MODE, MODE_KEYBOARD) ?: MODE_KEYBOARD

    fun setMode(ctx: Context, mode: String) = p(ctx).edit().putString(KEY_MODE, mode).apply()

    /** Dernière couleur médiane lue dans le clavier ; 0 = jamais lue. */
    fun sampled(ctx: Context): Int = p(ctx).getInt(KEY_SAMPLED, 0)

    fun setSampled(ctx: Context, color: Int) = p(ctx).edit().putInt(KEY_SAMPLED, color).apply()

    /** Fond effectif de la bulle, selon le mode choisi dans les réglages. */
    fun background(ctx: Context): Int = when (mode(ctx)) {
        MODE_DARK -> DARK
        MODE_ACCENT -> Accent.current(ctx)
        else -> sampled(ctx).takeIf { it != 0 } ?: DARK
    }

    /** Bandes au repos : encre sombre sur un fond clair, teintes d'origine sur un fond sombre. */
    fun idleInk(ctx: Context): Int? =
        background(ctx).let { if (Accent.on(it) == Accent.INK_DARK) Accent.INK_DARK else null }

    /** Bandes actives : l'accent, sauf quand le fond est déjà l'accent (elles y disparaîtraient). */
    fun litInk(ctx: Context): Int? = if (mode(ctx) == MODE_ACCENT) Accent.on(background(ctx)) else null
}
