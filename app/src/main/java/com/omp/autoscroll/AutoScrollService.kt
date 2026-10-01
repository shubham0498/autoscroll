package com.omp.autoscroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs

class AutoScrollService : AccessibilityService() {

    private val targets = setOf("com.instagram.android", "com.google.android.youtube")
    private val handler = Handler(Looper.getMainLooper())

    private var active = false            // toggled from the floating button
    private var clipStartAt = 0L          // when the current clip started (for timer mode)
    private var lastSwipeAt = 0L
    private var lastProgress = -1f        // 0..1 from the app's progress bar, -1 = unknown

    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private val tick = object : Runnable {
        override fun run() {
            try { check() } catch (_: Exception) { }
            handler.postDelayed(this, 700)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createBubble()
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val cls = event.className?.toString() ?: ""
            val isFeed = cls.contains("Recycler") || cls.contains("Pager")
            val ourOwnSwipe = SystemClock.uptimeMillis() - lastSwipeAt < 1500
            if (isFeed && !ourOwnSwipe) resetClip()   // you swiped manually → restart the count
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bubble?.let { runCatching { wm?.removeView(it) } }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- core loop

    private fun check() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val inTarget = pkg != null && pkg in targets

        bubble?.visibility = if (inTarget) View.VISIBLE else View.GONE
        if (!inTarget) {
            if (active) { active = false; updateBubble() }   // never swipe outside IG/YT
            return
        }
        if (!active) return

        val now = SystemClock.uptimeMillis()
        if (now - lastSwipeAt < 2500) return

        val p = findProgress(root!!)
        if (p != null) {
            // Progress-bar mode: swipe at the end, or when the clip loops back to the start
            val looped = lastProgress >= 0.5f && p < lastProgress - 0.3f
            if (p >= 0.985f || looped) { swipeNext(); return }
            lastProgress = p
        } else {
            // Timer mode: app doesn't expose progress, so swipe after N seconds
            val sec = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .getInt(Prefs.KEY_FALLBACK_SEC, Prefs.DEFAULT_FALLBACK_SEC)
            if (now - clipStartAt >= sec * 1000L) swipeNext()
        }
    }

    /** Looks for a seek/progress bar with real range info and returns played fraction. */
    private fun findProgress(root: AccessibilityNodeInfo): Float? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 500) {
            val n = queue.removeFirst(); visited++
            val cls = n.className?.toString() ?: ""
            val r = n.rangeInfo
            if (r != null && (cls.contains("SeekBar") || cls.contains("ProgressBar")) &&
                r.max > r.min && n.isVisibleToUser
            ) {
                return ((r.current - r.min) / (r.max - r.min)).coerceIn(0f, 1f)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun swipeNext() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.75f)
            lineTo(x, dm.heightPixels * 0.25f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()
        lastSwipeAt = SystemClock.uptimeMillis()
        dispatchGesture(gesture, null, null)
        resetClip()
    }

    private fun resetClip() {
        clipStartAt = SystemClock.uptimeMillis()
        lastProgress = -1f
    }

    // ---------------------------------------------------------------- floating button
    // Tap = ON/OFF, long-press = skip now, drag = move up/down

    private fun createBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * d).toInt()
            y = (resources.displayMetrics.heightPixels * 0.35).toInt()
        }

        var downRawY = 0f; var startY = 0; var moved = false; var downTime = 0L
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY; startY = lp.y; moved = false; downTime = e.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downRawY
                    if (abs(dy) > 10 * d) moved = true
                    if (moved) { lp.y = startY + dy.toInt(); wm?.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (e.eventTime - downTime > 600) {
                        swipeNext()                       // long-press: skip now
                    } else {
                        active = !active; resetClip(); updateBubble()
                    }
                }
            }
            true
        }
        bubble = tv; params = lp
        updateBubble()
        wm?.addView(tv, lp)
    }

    private fun updateBubble() {
        val d = resources.displayMetrics.density
        bubble?.text = if (active) "⏸ Auto ON" else "▶ Auto OFF"
        bubble?.background = GradientDrawable().apply {
            cornerRadius = 40 * d
            setColor(if (active) 0xDD1E8E3E.toInt() else 0xCC222222.toInt())
        }
    }
}
