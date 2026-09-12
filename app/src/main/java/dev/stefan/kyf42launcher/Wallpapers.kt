package dev.stefan.kyf42launcher

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View

/**
 * Home/lock wallpaper: the theme's bundled mesh, or an image the user picked
 * (kept as a persisted content URI so it survives reboots). Custom images are
 * decoded downsampled and drawn center-cropped, so any photo fills the screen
 * without distortion.
 */
object Wallpapers {

    private const val PREFS = "kyf42"
    private const val KEY_URI = "wallpaper_uri"
    private const val KEY_LOCK_SYNCED = "lock_wp_set"

    // One-deep decode cache: activities recreate on wallpaper/theme change, and
    // re-decoding a multi-megapixel photo each time is noticeable on these phones.
    private var cachedUri: String? = null
    private var cachedBitmap: Bitmap? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun customUri(ctx: Context): Uri? = prefs(ctx).getString(KEY_URI, null)?.let(Uri::parse)

    fun hasCustom(ctx: Context): Boolean = prefs(ctx).getString(KEY_URI, null) != null

    fun setCustom(ctx: Context, uri: Uri) {
        try {
            // OpenDocument grants read access for this session only unless we keep it.
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { /* provider offers no persistable grant */ }
        prefs(ctx).edit().putString(KEY_URI, uri.toString())
            .putBoolean(KEY_LOCK_SYNCED, false).apply()
        cachedUri = null
        cachedBitmap = null
    }

    fun clearCustom(ctx: Context) {
        customUri(ctx)?.let {
            try {
                ctx.contentResolver.releasePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { /* nothing held */ }
        }
        prefs(ctx).edit().remove(KEY_URI).putBoolean(KEY_LOCK_SYNCED, false).apply()
        cachedUri = null
        cachedBitmap = null
    }

    /** Labels the current choice for a settings row. */
    fun label(ctx: Context): String =
        if (hasCustom(ctx)) "Custom image" else "${Themes.current(ctx).label} (theme)"

    /** Paint [root] with the custom image when one is set, else the theme's mesh. */
    fun apply(ctx: Context, root: View, themeRes: Int) {
        val bmp = customBitmap(ctx)
        if (bmp != null) root.background = CropDrawable(bmp) else root.setBackgroundResource(themeRes)
    }

    /** Mirror the current wallpaper onto the system keyguard; once per change. */
    fun syncSystemLock(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_LOCK_SYNCED, false)) return
        val bmp = customBitmap(ctx) ?: themeBitmap(ctx) ?: return
        try {
            WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            p.edit().putBoolean(KEY_LOCK_SYNCED, true).apply()
        } catch (_: Exception) { /* SET_WALLPAPER unavailable: skip */ }
    }

    private fun themeBitmap(ctx: Context): Bitmap? = try {
        BitmapFactory.decodeResource(ctx.resources, Themes.current(ctx).wallpaperRes)
    } catch (_: Exception) { null }

    private fun customBitmap(ctx: Context): Bitmap? {
        val uri = customUri(ctx) ?: return null
        if (cachedBitmap != null && cachedUri == uri.toString()) return cachedBitmap
        val dm = ctx.resources.displayMetrics
        val bmp = decode(ctx, uri, dm.widthPixels, dm.heightPixels) ?: return null
        cachedUri = uri.toString()
        cachedBitmap = bmp
        return bmp
    }

    // Two passes: bounds-only to size the sample step, then the real decode. Keeps
    // a 12MP camera shot from allocating a full-screen-plus bitmap on a small phone.
    private fun decode(ctx: Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? = try {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, probe) }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) null else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(probe.outWidth, probe.outHeight, reqW, reqH)
                inPreferredConfig = Bitmap.Config.RGB_565   // opaque backdrop: half the memory
            }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Exception) { null }

    /** Largest power-of-two step that still leaves the image at or above the screen. */
    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2
        return s
    }

    /** Fills its bounds with [bmp], cropping the overflow (center-crop). */
    private class CropDrawable(private val bmp: Bitmap) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val scale = maxOf(b.width() / bmp.width.toFloat(), b.height() / bmp.height.toFloat())
            val w = bmp.width * scale
            val h = bmp.height * scale
            val left = b.left + (b.width() - w) / 2f
            val top = b.top + (b.height() - h) / 2f
            canvas.save()
            canvas.clipRect(b.left, b.top, b.right, b.bottom)
            canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
