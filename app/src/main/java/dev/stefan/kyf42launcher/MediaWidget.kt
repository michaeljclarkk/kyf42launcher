package dev.stefan.kyf42launcher

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * Home "now playing" row: current track plus prev/play/next.
 *
 * Deliberately has no timer. Track changes arrive as media notifications, and
 * [refresh] is called from the notification-changed path, so the row stays
 * current without polling a media session every second on a 1GB device.
 *
 * When there's no session the row collapses to just a play button, so it can
 * start the last-used player rather than being useless at rest.
 */
class MediaWidget(
    private val activity: Activity,
    private val row: View,
    private val art: ImageView,
    private val title: TextView,
    private val artist: TextView,
    private val prev: ImageView,
    private val playPause: ImageView,
    private val next: ImageView,
    private val onLayoutChanged: () -> Unit,
) {
    private val prefs get() = activity.getSharedPreferences("kyf42", Context.MODE_PRIVATE)

    /** Remembered so "open player" still works when no session is live. */
    private var lastPackage: String? = null

    // Players publish album art at ~640px. Decoding that on every notification
    // would be brutal here, so art is only re-read when the track actually
    // changes, downscaled once, and the previous bitmap released.
    private var artKey: String? = null
    private var artBitmap: android.graphics.Bitmap? = null

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun attach() {
        prev.setOnClickListener { MediaControl.previous(activity); refreshSoon() }
        next.setOnClickListener { MediaControl.next(activity); refreshSoon() }
        playPause.setOnClickListener {
            if (MediaControl.now(activity) == null) {
                // Nothing loaded: a media key starts the player instead.
                MediaControl.play(activity)
            } else {
                MediaControl.toggle(activity)
            }
            refreshSoon()
        }
        // Long press opens the owning player — the one thing a transport button
        // can't express, and there's no touch target to tap for it.
        playPause.setOnLongClickListener {
            MediaControl.openPlayer(activity, lastPackage); true
        }
    }

    // Transport commands are async, so the session's own state hasn't flipped
    // yet; re-read after a beat rather than showing a stale icon.
    private fun refreshSoon() {
        row.postDelayed({ refresh() }, 350)
    }

    fun refresh() {
        if (!enabled()) {
            row.visibility = View.GONE
            onLayoutChanged()
            return
        }

        val now = MediaControl.now(activity)
        if (now == null) {
            // Idle state: play-only, so it can start something.
            row.visibility = View.VISIBLE
            title.text = idleLabel()
            artist.visibility = View.GONE
            prev.visibility = View.GONE
            next.visibility = View.GONE
            playPause.setImageResource(R.drawable.ic_play)
            setArt(null, "idle")
            onLayoutChanged()
            return
        }

        lastPackage = now.packageName
        row.visibility = View.VISIBLE
        title.text = now.title
        artist.text = now.artist
        artist.visibility = if (now.artist.isBlank()) View.GONE else View.VISIBLE
        prev.visibility = View.VISIBLE
        next.visibility = View.VISIBLE
        playPause.setImageResource(
            if (now.playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        setArt(now.art, "${now.title}|${now.artist}")
        onLayoutChanged()
    }

    /**
     * Show [raw] scaled to the view, skipping the work unless the track changed.
     * The source bitmap is our own unparceled copy, so it is released once the
     * scaled version exists — on a 1GB device a stray 640px ARGB bitmap is 1.6MB.
     */
    private fun setArt(raw: android.graphics.Bitmap?, key: String) {
        if (key == artKey) return
        artKey = key

        if (raw == null) {
            artBitmap?.recycle()
            artBitmap = null
            art.setImageDrawable(null)
            return
        }

        val maxPx = (ART_DP * activity.resources.displayMetrics.density).toInt()
            .coerceAtLeast(48)
        val scaled = if (raw.width <= maxPx && raw.height <= maxPx) raw else {
            val scale = maxPx.toFloat() / maxOf(raw.width, raw.height)
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            val out = android.graphics.Bitmap.createScaledBitmap(raw, w, h, true)
            if (out !== raw) raw.recycle()
            out
        }
        artBitmap?.recycle()
        artBitmap = scaled
        art.setImageBitmap(scaled)
    }

    private fun idleLabel(): String =
        lastPackage?.let { "Resume ${label(it)}" } ?: "Play music"

    private fun label(pkg: String): String = try {
        activity.packageManager.getApplicationLabel(
            activity.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) {
        "music"
    }

    companion object {
        const val KEY_ENABLED = "media_widget"

        /** Matches the layout's 52dp art box. */
        private const val ART_DP = 52

        fun setEnabled(ctx: Context, on: Boolean) =
            ctx.getSharedPreferences("kyf42", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
