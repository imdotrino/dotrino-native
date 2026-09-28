package com.dotrino.sdk.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dotrino.sdk.R

/**
 * The Dotrino bar for NATIVE screens: what `<dotrino-topbar>` is on the web (brand, ES/EN and
 * the support coin), built with plain Android views so any Dotrino app can use it — the
 * identity app included, which carries no AppCompat or Material. No profile button: the
 * native screens that use it (Requests, the identity app) are about every account at once.
 *
 * `DotrinoTopbar(activity, repo = "imdotrino/dotrino-app") { open home }.view` is added on top of the screen.
 */
class DotrinoTopbar(
    private val activity: Activity,
    /** The GitHub repo where «Report a bug» goes. */
    private val repo: String,
    onBrand: () -> Unit,
) {
    companion object {
        val KOFI: Uri = Uri.parse("https://ko-fi.com/dotrino")
        val DISCORD: Uri = Uri.parse("https://discord.gg/D648uq7cth")
        const val HOME = "https://dotrino.com/"
    }

    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun color(id: Int) = activity.getColor(id)

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(color(R.color.dotrino_card))
        setPadding(px(16), px(8), px(16), px(8))
        addView(brand(onBrand), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(lang())
        addView(ImageButton(activity).apply {
            setImageResource(R.drawable.dotrino_coin)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            setPadding(px(4), px(4), px(4), px(4))
            contentDescription = activity.getString(R.string.dotrino_support_cta)
            setOnClickListener { showSupport() }
        }, LinearLayout.LayoutParams(px(44), px(44)).apply { marginStart = px(10) })
    }

    private fun brand(onBrand: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { onBrand() }
        addView(ImageView(activity).apply { setImageResource(R.drawable.dotrino_brand) }, LinearLayout.LayoutParams(px(28), px(28)))
        addView(TextView(activity).apply {
            text = "Dotrino"; setTextColor(color(R.color.dotrino_fg)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD); setPadding(px(8), 0, 0, 0)
        })
    }

    /** The TWO options always in sight, the active one highlighted (CONVENCIONES §9). */
    private fun lang() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        val active = DotrinoLocale.current(activity)
        for (l in listOf("es", "en")) addView(TextView(activity).apply {
            text = l.uppercase(); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(typeface, Typeface.BOLD)
            setPadding(px(12), 0, px(12), 0)
            val on = l == active
            setTextColor(color(if (on) R.color.dotrino_bg else R.color.dotrino_fg))
            background = GradientDrawable().apply {
                cornerRadius = px(8).toFloat(); setStroke(px(1), color(R.color.dotrino_muted))
                setColor(if (on) color(R.color.dotrino_fg) else 0)
            }
            contentDescription = l.uppercase()
            isSelected = on
            setOnClickListener { DotrinoLocale.set(activity, l) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36)).apply { marginStart = px(4) })
    }

    /** What the coin opens: the same texts and destinations as the `<dotrino-support>` modal. */
    private fun showSupport() {
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(24), px(24), px(24))
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(color(R.color.dotrino_bg)) }
        }
        col.addView(ImageView(activity).apply { setImageResource(R.drawable.dotrino_coin) }, LinearLayout.LayoutParams(px(72), px(72)))
        fun text(id: Int, size: Float, c: Int, bold: Boolean = false) = TextView(activity).apply {
            setText(id); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); gravity = Gravity.CENTER
            setTextColor(color(c)); if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(8), 0, px(8))
        }
        col.addView(text(R.string.dotrino_support_heading, 20f, R.color.dotrino_fg, bold = true))
        col.addView(text(R.string.dotrino_support_message, 15f, R.color.dotrino_muted))
        val out = { u: Uri -> activity.startActivity(Intent(Intent.ACTION_VIEW, u)) }
        col.addView(button(R.string.dotrino_support_donate, filled = true) { out(KOFI) })
        col.addView(button(R.string.dotrino_support_discord) { out(DISCORD) })
        col.addView(button(R.string.dotrino_support_bug) { out(Uri.parse("https://github.com/$repo/issues")) })
        col.addView(button(R.string.dotrino_support_share) {
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, HOME), null))
        })
        col.addView(button(R.string.dotrino_support_close) { dialog.dismiss() })
        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(0) })
        dialog.show()
    }

    private fun button(label: Int, filled: Boolean = false, run: () -> Unit) = dotrinoButton(activity, activity.getString(label), filled, run).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
    }
}

/** The ecosystem's button in plain Android views: filled (the main action) or outlined. */
fun dotrinoButton(activity: Activity, label: String, filled: Boolean, run: () -> Unit) = Button(activity).apply {
    val dp = activity.resources.displayMetrics.density
    text = label; isAllCaps = false; stateListAnimator = null
    setTextColor(activity.getColor(if (filled) R.color.dotrino_bg else R.color.dotrino_fg))
    background = GradientDrawable().apply {
        cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), activity.getColor(R.color.dotrino_accent))
        setColor(if (filled) activity.getColor(R.color.dotrino_accent) else 0)
    }
    setOnClickListener { run() }
}
