package com.ogesture.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.StringRes
import com.ogesture.R
import com.ogesture.data.GestureAction
import com.ogesture.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class EdgeGestureAccessibilityService : AccessibilityService(), GestureDispatcher {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val repo by lazy { SettingsRepository.get(this) }

    // Most-recent-first, distinct launchable app packages. Kept in memory only; we need the
    // last two to implement the previous-app gesture and do not persist app activity.
    private val recentApps = ArrayDeque<String>(2)

    // Owns the gesture-zone and indicator windows. Created when the service binds and
    // destroyed when it unbinds, so the windows follow the accessibility-service lifecycle
    // exactly: Android rebinds the service after process death and the controller comes
    // back with it, re-attaching zones if the master switch is still on — no foreground
    // service or boot receiver is needed for that revival.
    private var controller: EdgeOverlayController? = null

    // The gesture zones are accessibility-overlay windows, which the system does not hide on
    // secure screens and does not tie to a foreground service. The only requirement this
    // service can observe besides its own binding is unrestricted battery (the system and
    // OEM battery managers can still kill the process if it is restricted), so the watchdog
    // re-checks that in both directions and refreshes the IME list.
    private val watchdog = object : Runnable {
        override fun run() {
            syncMasterEnabled()
            refreshImePackages()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // Keyboards fire window-state events with their own package when they open, but there is
    // no matching event when they close — treating one as "the foreground app" would silently
    // end pass-through while the user is still inside an excluded app. So IME packages are
    // ignored for foreground tracking. Manifest <queries> grants visibility of IMEs only.
    @Volatile private var imePackages: Set<String> = emptySet()

    private fun refreshImePackages() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imePackages = try {
            imm.inputMethodList.mapTo(mutableSetOf()) { it.packageName }
        } catch (_: Throwable) {
            imePackages
        }
    }

    /** Add a new foreground launchable app; repeat events don't perturb the two-app order. */
    private fun rememberRecentApp(pkg: String) {
        if (pkg == packageName || pkg in imePackages) return
        // The narrow launcher-intent visibility in the manifest lets us identify apps that
        // can actually be reopened, while filtering system surfaces and transient windows.
        if (packageManager.getLaunchIntentForPackage(pkg) == null) return
        if (recentApps.firstOrNull() == pkg) return
        recentApps.remove(pkg)
        recentApps.addFirst(pkg)
        while (recentApps.size > MAX_RECENT_APPS) recentApps.removeLast()
    }

    private fun switchToPreviousApp() {
        val targetPkg = recentApps.getOrNull(1) ?: return
        val intent = packageManager.getLaunchIntentForPackage(targetPkg) ?: run {
            // The app may have been uninstalled or become unavailable since it was recorded.
            recentApps.remove(targetPkg)
            return
        }
        // NEW_TASK brings the existing app task forward when present; don't CLEAR_TOP, which
        // could discard the user's current screen within that app.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open previous app package $targetPkg", e)
            recentApps.remove(targetPkg)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshImePackages()
        controller = EdgeOverlayController(
            context = this,
            windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager,
            repo = repo,
            dispatcher = this,
            scope = scope,
        ).also { it.start() }
        // Being here means the accessibility requirement has just been met. If that was the
        // one thing missing, gestures come back now rather than up to a watchdog tick later;
        // the controller's master flow then attaches the zones.
        syncMasterEnabled()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged(newConfig)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(watchdog)
        controller?.stop()
        controller = null
        recentApps.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(watchdog)
        controller?.stop()
        controller = null
        recentApps.clear()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Background safety net, both ways. If gestures are on but something they need has gone
     * away, turn the switch off and say why; if the app turned them off earlier and
     * everything is back, turn them on again. The accessibility requirement is implied —
     * this only runs while the service is bound. The in-app screen does the same, faster,
     * while it is open; whichever gets there first wins and only that one tells the user.
     *
     * Which way to go is decided inside the store, not from a cached mirror of it — a mirror
     * would still be empty on the first call from [onServiceConnected], and stale by an IO
     * round trip afterwards. Each call is a no-op unless there was something to change.
     */
    private fun syncMasterEnabled() {
        val missingReason = GestureRequirements.missingReason(this)
        scope.launch {
            if (missingReason != null) {
                if (repo.disableForMissingRequirement()) toast(missingReason)
            } else if (repo.restoreIfAutoDisabled()) {
                toast(R.string.toast_gestures_on)
            }
        }
    }

    private fun toast(@StringRes message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only foreground package names are read — never window content. They drive per-app
        // pass-through and a two-entry, in-memory history for the previous-app gesture.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in imePackages) return
        rememberRecentApp(pkg)
        foregroundPackage.value = pkg
    }

    override fun onInterrupt() { /* no-op */ }

    override fun trigger(action: GestureAction) {
        when (action) {
            GestureAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GestureAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GestureAction.LAST_APP -> switchToPreviousApp()
        }
    }

    /**
     * Re-injects a touch the overlay consumed but didn't use, so it reaches the UI
     * underneath. Returns false if the gesture could not be dispatched; onDone always
     * runs otherwise, with whether the gesture played to completion.
     */
    override fun replay(gesture: GestureDescription, onDone: (completed: Boolean) -> Unit): Boolean =
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
            },
            handler,
        )

    companion object {
        private const val TAG = "EdgeGestureA11y"
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val MAX_RECENT_APPS = 2

        @Volatile
        var instance: EdgeGestureAccessibilityService? = null
            private set

        /**
         * Package of the app currently in front, from window-state-changed events (never
         * window content). Dialogs and keyboards can briefly report their own package;
         * the overlay only compares it against the user's excluded list, so that noise
         * at worst flips pass-through for a moment.
         */
        val foregroundPackage = MutableStateFlow<String?>(null)

        /** True iff the service appears in the system's enabled-accessibility-services setting. */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${EdgeGestureAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }

        /** True iff the system has actually bound this service in the current process. */
        fun isBound(): Boolean = instance != null
    }
}
