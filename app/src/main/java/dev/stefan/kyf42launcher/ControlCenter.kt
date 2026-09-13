package dev.stefan.kyf42launcher

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

/** Quick-toggle control center: flashlight, brightness, volume, ringer, wifi/bt/airplane. */
class ControlCenter(
    private val a: Activity,
    private val grid: GridLayout,
    private val onSettings: () -> Unit,
    /** Fired when D-pad focus lands on a different tile, so the caller can
     *  relabel the soft-key bar (see [focusedCanStep]). */
    private val onFocusChanged: () -> Unit = {},
) {

    private class Tile(
        val view: View,
        val state: TextView,
        val stateFn: () -> String,
        /** Non-null when the soft keys can nudge this value up/down. */
        val step: ((up: Boolean) -> Unit)? = null,
    )

    private val tiles = mutableListOf<Tile>()

    private var torchOn = false
    private var flashCamId: String? = null

    fun build() {
        grid.removeAllViews(); tiles.clear()
        if (a.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)) {
            addTile(R.drawable.ic_flash, "Flashlight", { toggleTorch() }) { if (torchOn) "On" else "Off" }
        }
        addTile(R.drawable.ic_bright, "Brightness", { cycleBrightness() }, { brightnessStep(it) }) { "${brightnessPct()}%" }
        // This phone has no volume key at all (no VOLUME_* in any built-in
        // keylayout), so without this tile the only way to change volume is
        // Settings or headset buttons.
        addTile(R.drawable.ic_vol, "Volume", { cycleVolume() }, { volumeStep(it) }) { "${volumePct()}%" }
        addTile(R.drawable.ic_ringer, "Profile", { cycleRinger() }) { ringerLabel() }
        // Settings.Panel exists only on API 29+; older releases get the full wifi screen.
        val wifiAction = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        addTile(R.drawable.ic_wifi_settings, "Wi-Fi", { openPanel(wifiAction) }) { onOff(wifiOn()) }
        addTile(R.drawable.ic_bt, "Bluetooth", { open(Settings.ACTION_BLUETOOTH_SETTINGS) }) { onOff(btOn()) }
        addTile(R.drawable.ic_air, "Airplane", { open(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }) { onOff(airplaneOn()) }
        addTile(R.drawable.ic_settings, "Settings", { onSettings() }) { "" }
        refresh()
    }

    fun firstView(): View? = tiles.firstOrNull()?.view
    fun refresh() = tiles.forEach { it.state.text = it.stateFn() }

    /**
     * True when the focused tile is one the soft keys can step (Brightness,
     * Volume). Other tiles leave them inert rather than pretending to act.
     */
    fun focusedCanStep(): Boolean = focusedTile()?.step != null

    /** Nudge the focused tile's value. No-op unless [focusedCanStep]. */
    fun stepFocused(up: Boolean) {
        focusedTile()?.step?.invoke(up)
        refresh()
    }

    private fun focusedTile(): Tile? {
        val f = grid.findFocus() ?: return null
        return tiles.firstOrNull { it.view === f }
    }

    private fun addTile(
        iconRes: Int,
        label: String,
        onClick: () -> Unit,
        step: ((up: Boolean) -> Unit)? = null,
        stateFn: () -> String,
    ) {
        val v = LayoutInflater.from(a).inflate(R.layout.item_ctrl, grid, false)
        v.findViewById<ImageView>(R.id.ctrlIcon).setImageResource(iconRes)
        v.findViewById<TextView>(R.id.ctrlLabel).text = label
        val st = v.findViewById<TextView>(R.id.ctrlState)
        v.setOnClickListener { onClick(); refresh() }
        // Moving between tiles changes whether the soft keys do anything, so the
        // bar has to be relabelled as focus travels.
        v.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocusChanged() }
        val m = (5 * a.resources.displayMetrics.density).toInt()
        v.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(m, m, m, m)
        }
        grid.addView(v)
        tiles.add(Tile(v, st, stateFn, step))
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    // --- Flashlight (no permission needed for torch) ---
    private fun toggleTorch() {
        val cm = a.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        if (flashCamId == null) {
            flashCamId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }
        flashCamId?.let {
            torchOn = !torchOn
            try { cm.setTorchMode(it, torchOn) } catch (_: Exception) { torchOn = !torchOn }
        }
    }

    // --- Brightness: cycle 25/50/75/100 (needs WRITE_SETTINGS) ---
    private fun cycleBrightness() {
        if (!Settings.System.canWrite(a)) {
            open(Settings.ACTION_MANAGE_WRITE_SETTINGS); return
        }
        val levels = intArrayOf(64, 128, 191, 255)
        val cur = brightness()
        val next = levels.firstOrNull { it > cur + 5 } ?: levels[0]
        Settings.System.putInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
    }

    private fun brightness(): Int =
        try { Settings.System.getInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
    private fun brightnessPct(): Int = (brightness() * 100 / 255)

    // Fine nudge for the soft keys, versus the coarse cycle on tap. ~10% a press.
    private fun brightnessStep(up: Boolean) {
        if (!Settings.System.canWrite(a)) {
            open(Settings.ACTION_MANAGE_WRITE_SETTINGS); return
        }
        val next = (brightness() + if (up) BRIGHT_STEP else -BRIGHT_STEP).coerceIn(10, 255)
        Settings.System.putInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
    }

    // --- Volume: cycle 25/50/75/100 on the media stream, like Brightness above.
    // Media, not ring: ringer level is already covered by the Profile tile. No
    // permission needed, unlike brightness (which is why this one can't fail).
    private fun cycleVolume() {
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val targets = intArrayOf(
            max / 4, max / 2, max * 3 / 4, max
        ).map { it.coerceAtLeast(1) }
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = targets.firstOrNull { it > cur + 1 } ?: targets[0]
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        } catch (_: SecurityException) {
            // DND can block volume changes; nothing useful to do but leave it.
        }
    }

    private fun volumePct(): Int {
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    // One stream step per press — the natural resolution, unlike brightness
    // which has no built-in step size.
    private fun volumeStep(up: Boolean) {
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0,
            )
        } catch (_: SecurityException) {
            // DND can block volume changes.
        }
    }

    // --- Ringer (silent needs notification-policy access) ---
    private fun cycleRinger() { Ringer.cycle(a) }
    private fun ringerLabel(): String = Ringer.label(a)

    // --- State readers ---
    private fun wifiOn(): Boolean =
        (a.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    private fun btOn(): Boolean = try { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (_: Exception) { false }
    private fun airplaneOn(): Boolean =
        Settings.Global.getInt(a.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun open(action: String) =
        try { a.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    private fun openPanel(action: String) = open(action)

    private companion object {
        /** Brightness is 0-255, so this is roughly 10% a press. */
        const val BRIGHT_STEP = 25
    }
}

/**
 * KaiOS-style quick profiles over the ringer modes, shared by the Control
 * Center tile and the side manner button (scancode 254 -> KEYCODE_CAMERA):
 * short press cycles, long press opens a picker. Silent needs
 * notification-policy access, otherwise the cycle skips it.
 */
internal object Ringer {
    /** label to ringer mode, in cycle order */
    val PROFILES = listOf(
        "Normal" to AudioManager.RINGER_MODE_NORMAL,
        "Meeting" to AudioManager.RINGER_MODE_VIBRATE,
        "Silent" to AudioManager.RINGER_MODE_SILENT,
    )

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun canSilent(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        return nm?.isNotificationPolicyAccessGranted == true
    }

    fun set(ctx: Context, mode: Int): String {
        try { am(ctx)?.ringerMode = mode } catch (_: Exception) {}
        return label(ctx)
    }

    fun cycle(ctx: Context): String {
        val am = am(ctx) ?: return label(ctx)
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE ->
                if (canSilent(ctx)) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return set(ctx, next)
    }

    fun label(ctx: Context): String {
        val mode = am(ctx)?.ringerMode
        return PROFILES.firstOrNull { it.second == mode }?.first ?: "Normal"
    }
}
