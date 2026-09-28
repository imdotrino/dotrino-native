package com.dotrino.sdk.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dotrino.sdk.R

/**
 * The Dotrino Android apps that use the identity app, and the list that shows them: installed
 * ones open, missing ones go to Google Play. Adding an app is one line in [CATALOG] plus its
 * `<package>` in this library's manifest (`<queries>`: without it Android hides the package).
 */
object DotrinoApps {
    data class App(val pkg: String, val name: String, val desc: Int)

    val CATALOG = listOf(
        App("com.dotrino.app", "Dotrino", R.string.dotrino_app_dotrino_desc),
    )

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Its page in the Play app itself: Android apps go only through Play. */
    fun storeUri(pkg: String): Uri = Uri.parse("market://details?id=$pkg")

    /**
     * Fills [into] with one row per app. Call it again on `onResume`: coming back from Play,
     * an app that was just installed shows «Open». [onError] gets what could not be done.
     */
    fun render(activity: Activity, into: LinearLayout, onError: (String) -> Unit) {
        into.removeAllViews()
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        for (app in CATALOG) {
            val installed = isInstalled(activity, app.pkg)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(10), 0, px(10))
            }
            row.addView(ImageView(activity).apply {
                if (installed) setImageDrawable(activity.packageManager.getApplicationIcon(app.pkg))
                else setImageResource(R.drawable.dotrino_brand)
                alpha = if (installed) 1f else 0.5f
            }, LinearLayout.LayoutParams(px(44), px(44)))
            row.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), 0, px(12), 0)
                addView(TextView(activity).apply {
                    text = app.name; setTextColor(activity.getColor(R.color.dotrino_fg))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    setText(app.desc); setTextColor(activity.getColor(R.color.dotrino_muted)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                addView(TextView(activity).apply {
                    setText(if (installed) R.string.dotrino_apps_installed else R.string.dotrino_apps_missing)
                    setTextColor(activity.getColor(if (installed) R.color.dotrino_accent else R.color.dotrino_muted))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val label = activity.getString(if (installed) R.string.dotrino_apps_open else R.string.dotrino_apps_install)
            row.addView(dotrinoButton(activity, label, filled = !installed) {
                try {
                    if (installed) {
                        val launch = activity.packageManager.getLaunchIntentForPackage(app.pkg)
                            ?: throw ActivityNotFoundException("${app.pkg} has no launcher activity")
                        activity.startActivity(launch)
                    } else activity.startActivity(Intent(Intent.ACTION_VIEW, storeUri(app.pkg)))
                } catch (e: ActivityNotFoundException) {
                    onError(if (installed) (e.message ?: app.pkg) else activity.getString(R.string.dotrino_apps_no_store))
                }
            })
            into.addView(row)
        }
    }
}
