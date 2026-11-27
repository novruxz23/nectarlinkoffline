// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import app.nectarlink.android.R
import app.nectarlink.core.MirrorInputEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The PC windows this phone shows in overlays (`docs/protocol/mirror.md` §7): at most
 * three, each its own floating window over whatever app is open, with its place and
 * size remembered per window app. Calls come from the core's threads; the overlays
 * themselves are only touched on the main thread.
 */
class WindowOverlays(context: Context, private val control: Control) {
    /** What the overlays need of the rest of the app. */
    interface Control {
        fun sendInput(pcId: String, session: Int, input: MirrorInputEvent)
        fun requestKeyframe(pcId: String, session: Int)

        /** Tells the PC the user closed this window's overlay (`mirror.stop`). */
        fun stop(pcId: String, session: Int)
    }

    data class Key(val pcId: String, val session: Int)

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val views = ConcurrentHashMap<Key, WindowOverlayView>()

    /** The windows that are shown or about to be: what the cap counts. */
    private val reserved = LinkedHashSet<Key>()
    private val store = OverlayStore(this.context)

    private val host = object : WindowOverlayView.Host {
        override fun sendInput(view: WindowOverlayView, input: MirrorInputEvent) =
            control.sendInput(view.key.pcId, view.key.session, input)

        override fun requestKeyframe(view: WindowOverlayView) = control.requestKeyframe(view.key.pcId, view.key.session)

        override fun closeByUser(view: WindowOverlayView) {
            remove(view.key)
            control.stop(view.key.pcId, view.key.session)
        }

        override fun remember(view: WindowOverlayView, placement: OverlayPlacement) = store.save(view.app, placement)
    }

    /**
     * A PC is about to show a window. True when its overlay will be drawn; false when it can't
     * (no "Display over other apps", or three windows already), and the PC is told so.
     */
    fun open(pcId: String, session: Int, app: String, title: String, width: Int, height: Int, viewOnly: Boolean): Boolean {
        if (!canDrawOverlays(context)) {
            OverlayPermission.askInNotification(context)
            return false
        }
        val key = Key(pcId, session)
        synchronized(reserved) {
            if (key !in reserved && reserved.size >= MAX_WINDOWS) return false
            reserved += key
        }
        main.post { add(key, app, title, width, height, viewOnly) }
        WindowOverlayService.start(context)
        return true
    }

    private fun add(key: Key, app: String, title: String, width: Int, height: Int, viewOnly: Boolean) {
        views.remove(key)?.detach()
        val (sw, sh) = screenSize(context)
        val density = context.resources.displayMetrics.density
        val index = views.size
        val placement = store.load(app)?.let { OverlayPlacement.clamp(it, sw, sh, density) }
            ?: OverlayPlacement.initial(sw, sh, width, height, index, density)
        val view = WindowOverlayView(context, key, app, title, width, height, viewOnly, placement, host)
        if (!view.attach()) {
            // Android refused (the permission went, say): the PC stops sending.
            synchronized(reserved) { reserved -= key }
            control.stop(key.pcId, key.session)
            stopServiceIfNone()
            return
        }
        views[key] = view
    }

    fun resize(pcId: String, session: Int, width: Int, height: Int) {
        views[Key(pcId, session)]?.onWindowSize(width, height)
    }

    fun state(pcId: String, session: Int, paused: Boolean) {
        views[Key(pcId, session)]?.onPaused(paused)
    }

    /** The PC closed the window, or stopped sharing it. */
    fun closed(pcId: String, session: Int) = remove(Key(pcId, session))

    /** Every window of one PC (it went offline). */
    fun closeAllOf(pcId: String) {
        val keys = synchronized(reserved) { reserved.filter { it.pcId == pcId } }
        keys.forEach { remove(it) }
    }

    fun closeAll() {
        val keys = synchronized(reserved) { reserved.toList() }
        keys.forEach { remove(it) }
    }

    fun onConfig(pcId: String, session: Int, codec: String, width: Int, height: Int) {
        views[Key(pcId, session)]?.onConfig(codec, width, height)
    }

    fun onFrame(pcId: String, session: Int, keyframe: Boolean, timeUs: Long, data: ByteArray) {
        views[Key(pcId, session)]?.onFrame(keyframe, timeUs, data)
    }

    fun onEnded(pcId: String, session: Int) = remove(Key(pcId, session))

    fun onViewOnly(pcId: String, session: Int) {
        views[Key(pcId, session)]?.onViewOnly()
    }

    private fun remove(key: Key) {
        synchronized(reserved) { reserved -= key }
        main.post {
            views.remove(key)?.detach()
            stopServiceIfNone()
        }
    }

    private fun stopServiceIfNone() {
        if (synchronized(reserved) { reserved.isEmpty() }) WindowOverlayService.stop(context)
    }

    companion object {
        /** Windows shown at once, at most. */
        const val MAX_WINDOWS = 3

        /** Whether `session` is a shared PC window's (1000 to 1999), not the screen's or an app window's. */
        fun isWindowSession(session: Int): Boolean = session in 1000..1999

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** The screen's size in pixels. */
        fun screenSize(context: Context): Pair<Int, Int> {
            val wm = context.getSystemService(WindowManager::class.java)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                @Suppress("DEPRECATION")
                val m = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
                m.widthPixels to m.heightPixels
            }
        }
    }
}

/** What is remembered of each window app's overlay: where it was, how big, whether a bubble. */
internal class OverlayStore(context: Context) {
    private val prefs = context.getSharedPreferences("window_overlays", Context.MODE_PRIVATE)

    // The app is a process name ("chrome"); no title is ever stored.
    fun load(app: String): OverlayPlacement? = OverlayPlacement.decode(prefs.getString(app, null))

    fun save(app: String, placement: OverlayPlacement) {
        prefs.edit().putString(app, placement.encode()).apply()
    }
}

/** Asking for "Display over other apps": a notification (a phone in the background can't open a screen), and the screen. */
object OverlayPermission {
    private const val CHANNEL = "window_overlay_permission"
    private const val NOTIFICATION_ID = 7312

    /** The system setting for this app, which is where "Display over other apps" is turned on. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun askInNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_window_overlay), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, OverlayPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.overlay_permission_notification_title))
            .setContentText(context.getString(R.string.overlay_permission_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
