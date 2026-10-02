package com.local.pickup

import android.app.*
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.widget.*
import java.util.WeakHashMap

object AppDialogs {
    private val active = WeakHashMap<Activity, MutableSet<Dialog>>()
    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density + .5f).toInt()
    private fun dark(a: Activity) = Store.prefs(a).getBoolean("dark", false)
    private fun ink(a: Activity) = if (dark(a)) 0xffeef5fc.toInt() else 0xff08263c.toInt()
    private fun blue(a: Activity) = if (dark(a)) 0xff8dceff.toInt() else 0xff1265d6.toInt()
    private fun shape(a: Activity, color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(a, radius).toFloat() }

    fun close(a: Activity) { active.remove(a)?.toList()?.forEach { it.dismiss() } }
    fun style(a: Activity, dialog: AlertDialog) {
        dialog.window?.apply {
            setBackgroundDrawable(shape(a, if (dark(a)) 0xff1c2937.toInt() else Color.WHITE, 22))
            setLayout(minOf(a.resources.displayMetrics.widthPixels - dp(a, 32), dp(a, 520)), -2)
            setDimAmount(.42f)
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            textSize = 15f; setTextColor(ink(a)); setLineSpacing(dp(a, 4).toFloat(), 1f)
        }
        for (which in listOf(-1, -2, -3)) dialog.getButton(which)?.takeIf { it.visibility == View.VISIBLE }?.apply {
            isAllCaps = false; textSize = 14f; minHeight = dp(a, 46)
            setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 8))
            setTextColor(if (which == -1) Color.WHITE else blue(a))
            background = shape(a, if (which == -1) 0xff1265d6.toInt() else if (dark(a)) 0xff253e55.toInt() else 0xffeaf3ff.toInt(), 12)
            // The framework button panel reserves its own padding. An extra bottom margin
            // pushes taller styled buttons above that panel, clipping their top corners.
            (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { it.leftMargin = dp(a, 6); it.bottomMargin = 0; layoutParams = it }
            (parent as? ViewGroup)?.let { panel ->
                panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, maxOf(panel.paddingBottom, dp(a, 16)))
            }
        }
    }

    private class Popup(private val a: Activity) : AlertDialog(a) {
        private var naturalHeight = 0
        private var keyboardSized = false
        private val fitListener = ViewTreeObserver.OnGlobalLayoutListener { fitWindow() }
        private fun fitWindow() {
            if (!isShowing) return
            val w = window ?: return
            val decor = w.decorView
            val frame = Rect().also { decor.getWindowVisibleDisplayFrame(it) }
            val keyboard = if (Build.VERSION.SDK_INT >= 30) decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                else a.resources.displayMetrics.heightPixels - frame.bottom > dp(a, 160)
            if (keyboard && frame.height() > 0) {
                if (!keyboardSized && decor.height > 0) naturalHeight = decor.height
                val height = minOf(naturalHeight.takeIf { it > 0 } ?: frame.height(), (frame.height() - dp(a, 32)).coerceAtLeast(dp(a, 120)))
                val y = ((frame.height() - height) / 2).coerceAtLeast(0)
                val attrs = w.attributes
                if (attrs.height != height || attrs.y != y || attrs.gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL)) {
                    // Floating dialog windows can ignore ADJUST_RESIZE. Explicitly reserve
                    // the visible viewport; the framework then gives the form a scrollable body.
                    attrs.height = height; attrs.y = y; attrs.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    w.attributes = attrs
                }
                keyboardSized = true
            } else if (keyboardSized) {
                keyboardSized = false
                val attrs = w.attributes
                attrs.height = ViewGroup.LayoutParams.WRAP_CONTENT; attrs.y = 0; attrs.gravity = Gravity.CENTER
                w.attributes = attrs
            } else if (decor.height > 0) naturalHeight = decor.height
        }
        override fun show() {
            if (a.isFinishing || a.isDestroyed) return
            if (!a.window.decorView.isAttachedToWindow) {
                a.window.decorView.post { if (!a.isFinishing && !a.isDestroyed && a.window.decorView.isAttachedToWindow) show() }
                return
            }
            try { super.show() } catch (e: WindowManager.BadTokenException) { android.util.Log.w("PickupDialog", "Window not ready", e) }
        }
        override fun onStart() {
            super.onStart(); style(a, this); active.getOrPut(a) { mutableSetOf() }.add(this)
            window?.decorView?.apply {
                viewTreeObserver.addOnGlobalLayoutListener(fitListener)
                setOnApplyWindowInsetsListener { v, insets -> v.post { fitWindow() }; v.onApplyWindowInsets(insets) }
            }
            a.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(fitListener)
        }
        override fun onStop() {
            window?.decorView?.let { if (it.viewTreeObserver.isAlive) it.viewTreeObserver.removeOnGlobalLayoutListener(fitListener); it.setOnApplyWindowInsetsListener(null) }
            a.window.decorView.viewTreeObserver.let { if (it.isAlive) it.removeOnGlobalLayoutListener(fitListener) }
            active[a]?.remove(this); super.onStop()
        }
    }

    class Builder(private val a: Activity) {
        private var title = ""
        private var message: CharSequence? = null
        private var view: View? = null
        private var items: Array<String>? = null
        private var selected = -1
        private var choice: DialogInterface.OnClickListener? = null
        private val buttons = mutableMapOf<Int, Pair<String, DialogInterface.OnClickListener?>>()
        fun setTitle(value: String) = apply { title = value }
        fun setMessage(value: CharSequence?) = apply { message = value }
        fun setView(value: View) = apply { view = value }
        fun setItems(values: Array<String>, listener: DialogInterface.OnClickListener) = apply { items = values; choice = listener }
        fun setSingleChoiceItems(values: Array<String>, checked: Int, listener: DialogInterface.OnClickListener) = apply { items = values; selected = checked; choice = listener }
        fun setPositiveButton(label: String, listener: DialogInterface.OnClickListener?) = apply { buttons[-1] = label to listener }
        fun setNegativeButton(label: String, listener: DialogInterface.OnClickListener?) = apply { buttons[-2] = label to listener }
        fun create(): AlertDialog {
            val dialog = Popup(a)
            val header = TextView(a).apply {
                text = title; textSize = 20f; setTextColor(ink(a)); setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(a, 24), dp(a, 24), dp(a, 24), dp(a, 12))
            }
            dialog.setCustomTitle(header)
            val values = items
            if (values != null) {
                val content = LinearLayout(a).apply { orientation = 1; setPadding(dp(a, 20), 0, dp(a, 20), dp(a, 12)) }
                if (!message.isNullOrEmpty()) content.addView(TextView(a).apply { text = message; textSize = 14f; setTextColor(ink(a)); setPadding(0, 0, 0, dp(a, 12)) })
                values.forEachIndexed { index, value ->
                    content.addView(TextView(a).apply {
                        text = if (selected == index) "$value   ✓" else value
                        textSize = 16f; setTextColor(if (selected == index) blue(a) else ink(a)); minHeight = dp(a, 48)
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10))
                        background = shape(a, if (dark(a)) 0xff25384a.toInt() else 0xfff2f6fa.toInt(), 12)
                        setOnClickListener { dialog.dismiss(); choice?.onClick(dialog, index) }
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(a, 6) })
                }
                dialog.setView(ScrollView(a).apply { addView(content) })
            } else if (view != null) dialog.setView(view)
            else dialog.setMessage(message)
            buttons.forEach { (which, pair) -> dialog.setButton(which, pair.first, pair.second) }
            return dialog
        }
        fun show(): AlertDialog = create().apply { show() }
    }

    class TimeDialog(private val a: Activity, listener: TimePickerDialog.OnTimeSetListener, hour: Int, minute: Int, clock24: Boolean) : TimePickerDialog(a, listener, hour, minute, clock24) {
        override fun onStart() { super.onStart(); style(a, this); active.getOrPut(a) { mutableSetOf() }.add(this) }
        override fun onStop() { active[a]?.remove(this); super.onStop() }
    }
}
