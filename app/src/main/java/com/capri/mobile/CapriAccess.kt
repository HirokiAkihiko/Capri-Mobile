package com.capri.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Her eyes on your phone (optional, you switch it on in Accessibility settings). Only three things are read, and
 * nothing is saved or sent anywhere:
 *  - which app is in front (so she can react to TikTok, games...),
 *  - where the app icons and buttons on screen are (so she can sit on them),
 *  - where you just tapped a button (so she looks at it).
 */
class CapriAccess : AccessibilityService() {
    interface Listener {
        fun onApp(pkg: String)
        fun onLedges()
        fun onClicked(r: Rect)
    }

    companion object {
        @Volatile var instance: CapriAccess? = null
            private set
        @Volatile var ledges: List<Rect> = emptyList()
            private set
        @Volatile var foreground = ""
            private set
        var listener: Listener? = null

        fun enabled(context: Context): Boolean {
            if (instance != null) return true
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return on.split(':').any { it.startsWith(context.packageName + "/") }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanQueued = false
    private val ignore by lazy { setOf(packageName, "com.android.systemui", "android") }

    override fun onServiceConnected() {
        instance = this
        scan()
    }

    override fun onDestroy() {
        instance = null
        ledges = emptyList()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignore) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != foreground) {
                    foreground = pkg
                    listener?.onApp(pkg)
                }
                queueScan(150)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> queueScan(450)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val r = Rect()
                e.source?.getBoundsInScreen(r)
                if (!r.isEmpty) listener?.onClicked(r)
            }
        }
    }

    private fun queueScan(delay: Long) {
        if (scanQueued) return
        scanQueued = true
        handler.postDelayed({ scanQueued = false; scan() }, delay)
    }

    /**
     * A screenshot for her eyes (Android 11+), scaled down so her brain can take it in. Only when you ask her to
     * look (or Scroll with me, when her brain has eyes); never saved.
     */
    fun screenshot(done: (android.graphics.Bitmap?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 30) { done(null); return }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hw = r.hardwareBuffer
                val full = android.graphics.Bitmap.wrapHardwareBuffer(hw, r.colorSpace)
                    ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (full == null) { done(null); return }
                val k = 768f / maxOf(full.width, full.height)
                done(if (k < 1f) android.graphics.Bitmap.createScaledBitmap(full, (full.width * k).toInt(),
                    (full.height * k).toInt(), true) else full)
            }

            override fun onFailure(errorCode: Int) = done(null)
        })
    }

    /**
     * The words on screen right now (captions, comments, titles), for "Scroll with me". Only ever asked for while a
     * feed app is open; never stored.
     */
    fun screenText(max: Int = 900): String {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return ""
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (queue.isNotEmpty() && n < 600) {
            val node = queue.removeFirst()
            n++
            if (!node.isVisibleToUser) continue
            if (node.isPassword || node.isEditable) continue  // never what you type
            for (t in listOf(node.text, node.contentDescription)) {
                val s = t?.toString()?.trim()?.replace(Regex("\\s+"), " ") ?: continue
                if (s.length >= 4 && !UI_WORDS.matches(s)) seen.add(s.take(200))
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return seen.joinToString(" | ").take(max)
    }

    private val UI_WORDS = Regex("(?i)^(like|likes|share|comment|comments|follow|following|search|home|inbox|profile|" +
        "friends|discover|reply|more|save|send|back|menu|close|add|create|shop|live|for you|explore|reels|shorts|" +
        "subscribe|subscriptions|library|\\d+[.,]?\\d*[kKmM]?)$")

    /** The tappable things on screen (app icons, buttons, cards): their tops are places she can sit. */
    private fun scan() {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val dm = resources.displayMetrics
        val minSide = (28 * dm.density).toInt()
        val out = ArrayList<Rect>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 500) {
            val n = queue.removeFirst()
            seen++
            if (!n.isVisibleToUser) continue
            if (n.isClickable || n.isLongClickable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() in minSide..(dm.widthPixels * 0.8).toInt() && r.height() in minSide..dm.heightPixels / 3) {
                    out.add(r)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        ledges = out
        listener?.onLedges()
    }
}
