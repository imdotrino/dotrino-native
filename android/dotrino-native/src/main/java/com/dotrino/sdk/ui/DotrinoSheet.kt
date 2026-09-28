package com.dotrino.sdk.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dotrino.sdk.R

/**
 * The ecosystem's modal on native screens: a sheet from the bottom across the whole width (a
 * centred Dialog wraps its content and came out narrow), scrollable so it fits on a small
 * screen or with large text. [fill] adds the content to the column; [heading], [message] and
 * [button] build the usual pieces.
 */
class DotrinoSheet(private val activity: Activity) {
    private val dp = activity.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    private fun color(id: Int) = activity.getColor(id)

    val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val column: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(px(24), px(12), px(24), px(24))
        // The handle of a sheet: says it can be dismissed by swiping or tapping outside.
        addView(View(activity).apply {
            background = GradientDrawable().apply { cornerRadius = px(2).toFloat(); setColor(color(R.color.dotrino_muted)) }
        }, LinearLayout.LayoutParams(px(36), px(4)).apply { bottomMargin = px(16) })
    }

    private fun text(value: String, size: Float, c: Int, bold: Boolean) = TextView(activity).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); gravity = Gravity.CENTER
        setTextColor(color(c)); if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    fun heading(value: String) = text(value, 20f, R.color.dotrino_fg, bold = true).also {
        column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })
    }

    fun message(value: String) = text(value, 15f, R.color.dotrino_muted, bold = false).also {
        column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8); bottomMargin = px(12) })
    }

    fun button(label: String, filled: Boolean = false, run: () -> Unit) = dotrinoButton(activity, label, filled, run).also {
        column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)).apply { topMargin = px(10) })
    }

    fun show(): Dialog {
        dialog.setContentView(ScrollView(activity).apply {
            background = GradientDrawable().apply {
                val r = px(20).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(color(R.color.dotrino_card))
            }
            addView(column)
        })
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(0) })
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.show()
        return dialog
    }
}
