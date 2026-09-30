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
import com.dotrino.sdk.IdentityClient
import com.dotrino.sdk.PhoneProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /**
     * false = WITHOUT the profile button. Only for a screen that is not of ONE profile (the
     * Dotrino app's Requests list every profile of the phone): there a single avatar says the
     * wrong thing.
     */
    showProfile: Boolean = true,
    onBrand: () -> Unit,
) {
    private val showProfile = showProfile
    private val scope = kotlinx.coroutines.MainScope()
    data class Brand(val name: String, val icon: Int)
    /**
     * [name]: the profile's name. [key]: what its identicon is drawn from (the profile's key).
     * [avatar]: the photo the person uploaded (data-URI), if any. `Profile.topbar()` builds it.
     */
    data class Profile(val name: String?, val key: String, val avatar: String? = null)
    private val profile = profile

    companion object {
        val KOFI: Uri = Uri.parse("https://ko-fi.com/dotrino")
        val DISCORD: Uri = Uri.parse("https://discord.gg/D648uq7cth")
        const val HOME = "https://dotrino.com/"
        // The same pages the web topbar's menu opens.
        const val PROFILE_URL = "https://profile.dotrino.com/"
        const val CREATE_URL = "https://profile.dotrino.com/create"
        const val ADOPT_URL = "https://vault.dotrino.com/d"
        const val LOGIN_URL = "https://profile.dotrino.com/login"
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
        if (showProfile) addView(profileButton(), LinearLayout.LayoutParams(px(40), px(40)).apply { marginStart = px(10) })
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

    /** The PROFILE'S AVATAR, like the web topbar: its photo or its identicon; a silhouette without one. */
    private fun profileButton(): View {
        val p = profile
        val v: View = if (p != null) DotrinoAvatarView(activity, p.key, p.avatar) else TextView(activity).apply {
            text = "👤"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.dotrino_muted)) }
        }
        return v.apply {
            contentDescription = activity.getString(R.string.dotrino_profile_cta)
            tag = "profile-button"
            isClickable = true
            setOnClickListener { showProfile() }
        }
    }

    /**
     * THE PROFILE MENU, like the web topbar's: this phone's profiles (avatar, name, the active one
     * ticked) to switch, and «Open my profile», «Create profile», «Adopt a profile» and «Sign in»
     * (or «Sign out» when the active one was entered with a password). Everything happens INSIDE
     * this app: the profiles are the phone's (the identity app), and the pages open here with the
     * same identity ([DotrinoWebActivity]). No other Dotrino app is needed (CONVENCIONES §16.2).
     */
    private fun showProfile() {
        val sheet = DotrinoSheet(activity)
        sheet.heading(activity.getString(R.string.dotrino_profiles))
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sheet.column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val links = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sheet.column.addView(links, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun link(label: String, url: String) = links.addView(TextView(activity).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(color(R.color.dotrino_fg))
            setPadding(px(4), px(12), px(4), px(12)); tag = "profile-link"
            setOnClickListener { sheet.dialog.dismiss(); DotrinoWebActivity.open(activity, url) }
        })
        fun renderLinks(signedIn: Boolean) {
            links.removeAllViews()
            link(activity.getString(R.string.dotrino_profile_open_mine), PROFILE_URL)
            link("＋ " + activity.getString(R.string.dotrino_profile_new), CREATE_URL)
            link("↧ " + activity.getString(R.string.dotrino_profile_adopt), ADOPT_URL)
            // «Sign out» of an account entered with a password is done on its page (it releases
            // the place in the vault); «Sign in» opens the login page.
            if (signedIn) link("⇥ " + activity.getString(R.string.dotrino_profile_logout), PROFILE_URL)
            else link("⇤ " + activity.getString(R.string.dotrino_profile_login), LOGIN_URL)
        }
        renderLinks(false)
        sheet.button(activity.getString(R.string.dotrino_support_close)) { sheet.dialog.dismiss() }
        sheet.show()
        // While they are read (the identity's store can be large and travel in pieces), it says so.
        val loading = TextView(activity).apply {
            text = activity.getString(R.string.dotrino_profiles_loading); setTextColor(color(R.color.dotrino_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(px(4), px(8), px(4), px(8)); tag = "profiles-loading"
        }
        list.addView(loading)
        scope.launch {
            val entries = try { withContext(Dispatchers.IO) { PhoneProfiles.load(IdentityClient.shared(activity)) } }
            catch (e: Exception) {
                // Said, not swallowed: without the list the menu would look like there were no profiles.
                android.util.Log.w("dotrino-topbar", "could not read the profiles", e)
                val code = (e as? IdentityClient.IdentityError)?.code ?: e.javaClass.simpleName
                loading.text = activity.getString(R.string.dotrino_profiles_error, code); loading.setTextColor(color(R.color.dotrino_fg))
                return@launch
            }
            list.removeView(loading)
            renderLinks(entries.any { it.current && it.login != null })
            for (e in entries) list.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(px(4), px(8), px(4), px(8)); tag = "profile-" + e.id
                addView(DotrinoAvatarView(activity, e.seed, e.avatar), LinearLayout.LayoutParams(px(36), px(36)))
                addView(TextView(activity).apply {
                    text = (e.name ?: activity.getString(R.string.dotrino_profile_unnamed)) + (e.login?.let { "\n$it" } ?: "")
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(color(R.color.dotrino_fg)); setPadding(px(12), 0, 0, 0)
                    if (e.current) setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (e.current) addView(TextView(activity).apply { text = "✓"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(color(R.color.dotrino_accent)) })
                else setOnClickListener {
                    isEnabled = false
                    scope.launch {
                        val ok = runCatching { withContext(Dispatchers.IO) { PhoneProfiles.switchTo(IdentityClient.shared(activity), e.id) } }.isSuccess
                        if (ok) DotrinoApps.restart(activity) else isEnabled = true
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            list.addView(android.view.View(activity).apply { setBackgroundColor(color(R.color.dotrino_muted)); alpha = 0.3f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { topMargin = px(6); bottomMargin = px(6) })
        }
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
