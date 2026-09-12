package dev.stefan.kyf42launcher

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.View

/**
 * Which top bar to show: the launcher's own KaiOS-style bar, or the stock Android
 * status bar.
 *
 * Launcher is the default and keeps the KaiOS look. "System" is opt-in and exists
 * because mixing the two is inconsistent, and because our overlay bar cannot
 * coexist with the stock one over other apps: a TYPE_APPLICATION_OVERLAY window
 * is always layered below the status bar on Android O+, so both draw and their
 * rows collide. Choosing the stock bar sidesteps that entirely.
 */
object SystemBars {

    private const val PREFS = "kyf42"
    private const val KEY = "system_status_bar"

    // Opaque so the stock clock/battery stay legible over any wallpaper. The
    // launcher bar can use a gradient; a statusBarColor cannot.
    private const val STOCK_COLOR = 0xFF0A0A18.toInt()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the user opted into the stock Android status bar. */
    fun useStock(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY, false)

    fun setUseStock(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY, on).apply()

    /** Current choice, for a settings row. */
    fun label(ctx: Context): String =
        if (useStock(ctx)) "System (Android)" else "Launcher (KaiOS)"

    /**
     * Apply the window flags. In stock mode the status bar is left visible and our
     * content is inset below it; otherwise it is hidden and we draw our own.
     * The nav bar stays hidden in both modes.
     */
    fun apply(activity: Activity) {
        val custom = !useStock(activity)
        if (!custom) activity.window.statusBarColor = STOCK_COLOR
        // LAYOUT_* keeps our content full-bleed; only FULLSCREEN differs, since that
        // is what actually hides the status bar.
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (custom) flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = flags
    }

    /**
     * Keep content clear of the stock status bar. The nav-bar flag above doesn't
     * inset the top, so without this the first row lays out underneath the bar.
     * Pads only by the inset the window actually reports, so a build that already
     * insets the content gets no extra gap.
     */
    fun insetForStock(activity: Activity, root: View) {
        if (!useStock(activity)) return
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            if (v.paddingTop != top) {
                v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            }
            insets
        }
        root.requestApplyInsets()
    }

    /**
     * Hide an in-app status row so it can't double up with the stock bar. No-op
     * when the launcher bar is in use.
     */
    fun hideIfStock(ctx: Context, vararg views: View) {
        if (!useStock(ctx)) return
        views.forEach { it.visibility = View.GONE }
    }
}
