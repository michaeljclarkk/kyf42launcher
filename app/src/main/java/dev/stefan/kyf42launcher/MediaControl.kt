package dev.stefan.kyf42launcher

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Reads the active media session and drives it, for the home now-playing row.
 *
 * Requires Notification Access, which the launcher already holds for
 * [LockListenerService] — that same component is what MediaSessionManager
 * accepts as the "notification listener" granting visibility.
 *
 * When nothing is playing there is no session to control, so [play] falls back
 * to dispatching a media button. Spotify declares a MediaButtonReceiver, so
 * that starts it cold and resumes the last track rather than doing nothing.
 */
object MediaControl {

    /** Snapshot of what's playing, or null when there's no session. */
    data class Now(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val packageName: String,
    )

    private fun listener(ctx: Context): ComponentName =
        ComponentName(ctx, LockListenerService::class.java)

    private fun controller(ctx: Context): MediaController? = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        msm?.getActiveSessions(listener(ctx))
            ?.maxByOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    } catch (_: Exception) {
        null   // notification access missing
    }

    /** Current track, or null when nothing is loaded. */
    fun now(ctx: Context): Now? = try {
        val c = controller(ctx) ?: return null
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (title.isNullOrBlank()) null
        else Now(
            title = title,
            artist = artist.orEmpty(),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            packageName = c.packageName,
        )
    } catch (_: Exception) {
        null
    }

    fun play(ctx: Context) {
        val c = controller(ctx)
        if (c != null) {
            try { c.transportControls.play(); return } catch (_: Exception) {}
        }
        // No live session: a media key reaches the last registered media button
        // receiver, which lets a stopped player start without being launched.
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause(ctx: Context) {
        val c = controller(ctx)
        if (c != null) {
            try { c.transportControls.pause(); return } catch (_: Exception) {}
        }
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun next(ctx: Context) {
        val c = controller(ctx)
        if (c != null) {
            try { c.transportControls.skipToNext(); return } catch (_: Exception) {}
        }
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous(ctx: Context) {
        val c = controller(ctx)
        if (c != null) {
            try { c.transportControls.skipToPrevious(); return } catch (_: Exception) {}
        }
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun toggle(ctx: Context) {
        if (now(ctx)?.playing == true) pause(ctx) else play(ctx)
    }

    /** Open the player that owns the current session, else the last used one. */
    fun openPlayer(ctx: Context, fallbackPkg: String?) {
        val pkg = now(ctx)?.packageName ?: fallbackPkg ?: return
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            try { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) {}
        }
    }

    // Keycodes 85/87/88/126/127 are the media transport codes; dispatchDownTime
    // is required or AudioService drops the event as malformed.
    private fun mediaKey(ctx: Context, keyCode: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val now = android.os.SystemClock.uptimeMillis()
        try {
            am.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            )
            am.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            )
        } catch (_: Exception) {}
    }
}
