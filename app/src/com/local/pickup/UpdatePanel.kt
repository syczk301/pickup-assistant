package com.local.pickup

import android.app.Activity
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.*
import android.widget.*
import java.util.WeakHashMap

// Keep update prompts inside the Activity: no separate system/OEM dialog window.
class UpdatePanel private constructor(private val activity: Activity) : DialogInterface {
    companion object {
        private val active = WeakHashMap<Activity, MutableList<UpdatePanel>>()
        fun close(activity: Activity) { active.remove(activity)?.toList()?.forEach { it.dismiss() } }
        fun dismissTop(activity: Activity): Boolean {
            val panel = active[activity]?.lastOrNull() ?: return false
            panel.dismiss(); return true
        }
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density + .5f).toInt()
    private val dark = Store.prefs(activity).getBoolean("dark", false)
    private val ink = if (dark) 0xffeef5fc.toInt() else 0xff08263c.toInt()
    private val blue = if (dark) 0xff8dceff.toInt() else 0xff1265d6.toInt()
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private val host = FrameLayout(activity).apply {
        setBackgroundColor(0x66000000); isClickable = true; isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(20))
        background = shape(if (dark) 0xff1c2937.toInt() else Color.WHITE, 22)
        isClickable = true
    }
    private val titleView = TextView(activity).apply { textSize = 20f; setTextColor(ink); setTypeface(Typeface.DEFAULT, Typeface.BOLD); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val messageView = TextView(activity).apply { textSize = 15f; setTextColor(ink); setLineSpacing(dp(4).toFloat(), 1f) }
    private val actions = LinearLayout(activity).apply {
        orientation = if (activity.resources.configuration.fontScale > 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.END; setPadding(0, dp(20), 0, 0)
    }
    private var dismissed: DialogInterface.OnDismissListener? = null
    private var shownAt = 0L
    private var backCallback: Any? = null
    private var previousAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    var isShowing = false
        private set

    init {
        card.addView(titleView)
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (activity.resources.displayMetrics.heightPixels - dp(280)).coerceAtLeast(dp(100))
                val size = View.MeasureSpec.getSize(heightMeasureSpec)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(if (size > 0) minOf(size, cap) else cap, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(messageView); isFillViewport = false }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        card.addView(actions)
        host.addView(card, FrameLayout.LayoutParams(minOf(activity.resources.displayMetrics.widthPixels - dp(32), dp(520)), -2, Gravity.CENTER))
        host.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) dismiss(); true } else false
        }
    }
    fun setMessage(message: CharSequence?) { messageView.text = message }
    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) { dismissed = listener }
    fun show() {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window.decorView as ViewGroup
        if (!decor.isAttachedToWindow) {
            decor.post { if (!activity.isFinishing && !activity.isDestroyed && decor.isAttachedToWindow) show() }
            return
        }
        previousAccessibility = activity.findViewById<View>(android.R.id.content).importantForAccessibility
        activity.findViewById<View>(android.R.id.content).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        decor.addView(host, ViewGroup.LayoutParams(-1, -1))
        isShowing = true; shownAt = SystemClock.uptimeMillis()
        active.getOrPut(activity) { mutableListOf() }.add(this)
        host.requestFocus()
        if (Build.VERSION.SDK_INT >= 33) backCallback = BackApi.register(activity) { dismiss() }
        android.util.Log.i("PickupUpdatePanel", "Update panel shown")
    }
    override fun dismiss() {
        if (!isShowing) return
        (host.parent as? ViewGroup)?.removeView(host)
        isShowing = false; active[activity]?.remove(this)
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { BackApi.unregister(activity, it) }
        backCallback = null
        if (active[activity].isNullOrEmpty()) activity.findViewById<View>(android.R.id.content)?.importantForAccessibility = previousAccessibility
        android.util.Log.i("PickupUpdatePanel", "Update panel dismissed")
        dismissed?.onDismiss(this)
    }
    override fun cancel() { dismiss() }
    private fun button(which: Int, label: String, listener: DialogInterface.OnClickListener?) {
        val button = TextView(activity).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(48); isFocusable = true
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setTextColor(if (which == DialogInterface.BUTTON_POSITIVE) Color.WHITE else blue)
            background = shape(if (which == DialogInterface.BUTTON_POSITIVE) 0xff1265d6.toInt() else if (dark) 0xff253e55.toInt() else 0xffeaf3ff.toInt(), 12)
            setOnClickListener {
                // Ignore a tap already in flight when the network result appears.
                if (SystemClock.uptimeMillis() - shownAt >= 450) { dismiss(); listener?.onClick(this@UpdatePanel, which) }
            }
        }
        actions.addView(button, LinearLayout.LayoutParams(if (actions.orientation == LinearLayout.VERTICAL) -1 else -2, -2).apply { if (actions.childCount > 0) { if (actions.orientation == LinearLayout.VERTICAL) topMargin = dp(8) else leftMargin = dp(8) } })
    }
    class Builder(private val activity: Activity) {
        private var title = ""
        private var message: CharSequence? = null
        private var negative: Pair<String, DialogInterface.OnClickListener?>? = null
        private var positive: Pair<String, DialogInterface.OnClickListener?>? = null
        fun setTitle(value: String) = apply { title = value }
        fun setMessage(value: CharSequence?) = apply { message = value }
        fun setNegativeButton(label: String, listener: DialogInterface.OnClickListener?) = apply { negative = label to listener }
        fun setPositiveButton(label: String, listener: DialogInterface.OnClickListener?) = apply { positive = label to listener }
        fun create() = UpdatePanel(activity).apply {
            titleView.text = title; setMessage(message)
            negative?.let { button(DialogInterface.BUTTON_NEGATIVE, it.first, it.second) }
            positive?.let { button(DialogInterface.BUTTON_POSITIVE, it.first, it.second) }
        }
        fun show() = create().apply { show() }
    }
    private object BackApi {
        fun register(activity: Activity, close: () -> Unit): Any {
            val callback = android.window.OnBackInvokedCallback { close() }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            return callback
        }
        fun unregister(activity: Activity, callback: Any) {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback as android.window.OnBackInvokedCallback)
        }
    }
}
