package com.dotrino.sdk.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.ImageView
import android.widget.LinearLayout
import com.dotrino.sdk.IdentityClient
import com.dotrino.sdk.R

/**
 * ANDROID ONLY (CONVENCIONES §16.2): the profile of every Dotrino app on the phone lives in the
 * identity app (`com.dotrino.identity`). When an app needs it and it is not installed, it FIRST
 * explains why in this modal, and only then sends to Google Play — never straight to the store.
 * iOS has no identity app, so there is nothing like this there.
 *
 * `if (!IdentityRequired.check(activity)) return` before anything that speaks as the person.
 */
object IdentityRequired {
    /** True when the identity app is installed; otherwise shows the modal and returns false. */
    fun check(activity: Activity): Boolean {
        if (IdentityClient.isInstalled(activity)) return true
        show(activity)
        return false
    }

    fun show(activity: Activity) {
        val sheet = DotrinoSheet(activity)
        sheet.column.addView(ImageView(activity).apply { setImageResource(R.drawable.dotrino_brand) },
            LinearLayout.LayoutParams(sheet.px(64), sheet.px(64)))
        sheet.heading(activity.getString(R.string.dotrino_identity_needed_heading))
        val msg = sheet.message(activity.getString(R.string.dotrino_identity_needed_message))
        sheet.button(activity.getString(R.string.dotrino_identity_needed_install), filled = true) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, IdentityClient.installUri))
                sheet.dialog.dismiss()
            } catch (_: ActivityNotFoundException) {
                // Android apps go only through Play: without it, say so instead of sending elsewhere.
                msg.text = activity.getString(R.string.dotrino_apps_no_store)
            }
        }
        sheet.button(activity.getString(R.string.dotrino_identity_needed_later)) { sheet.dialog.dismiss() }
        sheet.show()
    }
}
