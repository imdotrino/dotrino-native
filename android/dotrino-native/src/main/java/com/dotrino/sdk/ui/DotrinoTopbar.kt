package com.dotrino.sdk.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 *
 * An app with its own name passes [brand] (the web topbar's `brand` + `icon`) and its buttons
 * in [actions] (the web topbar's default slot): they go between the brand and ES/EN.
 */
class DotrinoTopbar(
    private val activity: Activity,
    /** The GitHub repo where «Report a bug» goes. */
    private val repo: String,
    /** The app's name and icon; null = «Dotrino» with the ecosystem mark. */
    brand: Brand? = null,
    /** The app's own buttons (e.g. «Results», «☰»), in order. */
    actions: List<View> = emptyList(),
    /**
     * The PROFILE BUTTON (CONVENCIONES §6.1: every page has it): the active profile's initial and
     * key. Null = the button still shows, as on the web without `.identity`. It does not edit
     * anything here: on a phone the profiles are managed in the Dotrino app, with the phone's
     * identity (opening profile.dotrino.com in a browser would show ANOTHER identity).
     */
    profile: Profile? = null,
    onBrand: () -> Unit,
) {
    data class Brand(val name: String, val icon: Int)
    data class Profile(val name: String?, val key: String)
    private val profile = profile

    companion object {
        val KOFI: Uri = Uri.parse("https://ko-fi.com/dotrino")
        val DISCORD: Uri = Uri.parse("https://discord.gg/D648uq7cth")
        const val HOME = "https://dotrino.com/"
    }

    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun color(id: Int) = activity.getColor(id)

    /**
     * On a phone the app's buttons go to a SECOND row, right-aligned, as the web topbar does:
     * in one row they squeeze the brand («Padel» came out as «Pad»). From 600 dp, one row.
     */
    private val secondRow = actions.isNotEmpty() && activity.resources.configuration.screenWidthDp < 600

    val view: View = if (!secondRow) mainRow(brand, actions, onBrand) else LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(R.color.dotrino_card))
        addView(mainRow(brand, emptyList(), onBrand))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(px(16), 0, px(16), px(4))
            for (a in actions) addView(a)
        })
    }

    private fun mainRow(brand: Brand?, actions: List<View>, onBrand: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(color(R.color.dotrino_card))
        setPadding(px(16), px(8), px(16), px(8))
        addView(brand(brand, onBrand), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (a in actions) addView(a)
        addView(lang())
        addView(profileButton(), LinearLayout.LayoutParams(px(40), px(40)).apply { marginStart = px(10) })
        addView(ImageButton(activity).apply {
            setImageResource(R.drawable.dotrino_coin)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            setPadding(px(4), px(4), px(4), px(4))
            contentDescription = activity.getString(R.string.dotrino_support_cta)
            setOnClickListener { showSupport() }
        }, LinearLayout.LayoutParams(px(44), px(44)).apply { marginStart = px(10) })
    }

    private fun brand(b: Brand?, onBrand: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { onBrand() }
        addView(ImageView(activity).apply { setImageResource(b?.icon ?: R.drawable.dotrino_brand) }, LinearLayout.LayoutParams(px(28), px(28)))
        addView(TextView(activity).apply {
            text = b?.name ?: "Dotrino"; isSingleLine = true; setTextColor(color(R.color.dotrino_fg)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD); setPadding(px(8), 0, 0, 0)
        })
    }

    private fun profileButton() = TextView(activity).apply {
        val p = profile
        text = p?.name?.trim()?.firstOrNull()?.uppercase() ?: "👤"
        gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        val palette = intArrayOf(0xFF00658C.toInt(), 0xFF006B5C.toInt(), 0xFF665590.toInt(), 0xFF8C4A00.toInt(), 0xFF3F6B00.toInt(), 0xFF7A3E6B.toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (p != null) palette[Math.floorMod(p.key.hashCode(), palette.size)] else color(R.color.dotrino_muted))
        }
        contentDescription = activity.getString(R.string.dotrino_profile_cta)
        tag = "profile-button"
        setOnClickListener { showProfile() }
    }

    /** Where the profiles are managed on a phone: the Dotrino app (or Play, if it is missing). */
    private fun showProfile() {
        val sheet = DotrinoSheet(activity)
        sheet.heading(profile?.name?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.dotrino_profile_cta))
        sheet.message(activity.getString(R.string.dotrino_profile_message))
        sheet.button(activity.getString(R.string.dotrino_profile_open), filled = true) {
            val app = "com.dotrino.app"
            try {
                if (DotrinoApps.isInstalled(activity, app)) {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://profile.dotrino.com/")).setPackage(app))
                } else activity.startActivity(Intent(Intent.ACTION_VIEW, DotrinoApps.storeUri(app)))
                sheet.dialog.dismiss()
            } catch (_: android.content.ActivityNotFoundException) {
                sheet.message(activity.getString(R.string.dotrino_apps_no_store))
            }
        }
        sheet.button(activity.getString(R.string.dotrino_support_close)) { sheet.dialog.dismiss() }
        sheet.show()
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

    /**
     * What the coin opens: the same texts and destinations as the `<dotrino-support>` modal, as
     * a sheet from the bottom across the whole width (a centred Dialog wraps its content and
     * came out narrow). It scrolls, so it fits on a small screen or with large text.
     */
    private fun showSupport() {
        val sheet = DotrinoSheet(activity)
        sheet.column.addView(ImageView(activity).apply { setImageResource(R.drawable.dotrino_coin) }, LinearLayout.LayoutParams(px(72), px(72)))
        sheet.heading(activity.getString(R.string.dotrino_support_heading))
        sheet.message(activity.getString(R.string.dotrino_support_message))
        val out = { u: Uri -> activity.startActivity(Intent(Intent.ACTION_VIEW, u)) }
        sheet.button(activity.getString(R.string.dotrino_support_donate), filled = true) { out(KOFI) }
        sheet.button(activity.getString(R.string.dotrino_support_discord)) { out(DISCORD) }
        sheet.button(activity.getString(R.string.dotrino_support_bug)) { out(Uri.parse("https://github.com/$repo/issues")) }
        sheet.button(activity.getString(R.string.dotrino_support_share)) {
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, HOME), null))
        }
        sheet.button(activity.getString(R.string.dotrino_support_close)) { sheet.dialog.dismiss() }
        sheet.show()
    }
}

/** The ecosystem's button in plain Android views: filled (the main action) or outlined. */
fun dotrinoButton(activity: Activity, label: String, filled: Boolean, run: () -> Unit) = Button(activity).apply {
    val dp = activity.resources.displayMetrics.density
    text = label; isAllCaps = false; stateListAnimator = null
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); minHeight = 0; minimumHeight = 0
    setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
    setTextColor(activity.getColor(if (filled) R.color.dotrino_bg else R.color.dotrino_fg))
    background = GradientDrawable().apply {
        cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), activity.getColor(R.color.dotrino_accent))
        setColor(if (filled) activity.getColor(R.color.dotrino_accent) else 0)
    }
    setOnClickListener { run() }
}
