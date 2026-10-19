package com.dotrino.sdk.ui

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import com.dotrino.sdk.IdentityClient
import com.dotrino.sdk.R

/**
 * ANDROID ONLY (CONVENCIONES §16.2): the profile of every Dotrino app on the phone lives in the
 * identity app (`com.dotrino.identity`). When an app needs it and it is not installed, it FIRST
 * explains why in this modal, and only then sends to Google Play — never straight to the store.
 * iOS has no identity app, so there is nothing like this there.
 *
 * It also asks to UPDATE it when the installed one is older than the oldest this library works
 * with ([IdentityClient.MIN_VERSION_CODE], raised only by an incompatible change).
 *
 * `if (!IdentityRequired.check(activity)) return` before anything that speaks as the person.
 */
object IdentityRequired {
    /** True when the identity app is installed and up to date; otherwise shows the modal and returns false. */
    fun check(activity: Activity): Boolean {
        if (IdentityClient.isUpToDate(activity)) return true
        show(activity)
        return false
    }

    /**
     * Returns the dialog, so an app that needs the identity everywhere can close it on return.
     * It says «install» or «update» depending on what is on the phone.
     */
    fun show(activity: Activity): Dialog {
        val update = IdentityClient.isInstalled(activity)
        val sheet = DotrinoSheet(activity)
        sheet.appIcon(R.drawable.dotrino_brand)
        sheet.heading(activity.getString(if (update) R.string.dotrino_identity_update_heading else R.string.dotrino_identity_needed_heading))
        val msg = sheet.message(activity.getString(if (update) R.string.dotrino_identity_update_message else R.string.dotrino_identity_needed_message))
        sheet.button(activity.getString(if (update) R.string.dotrino_identity_update_install else R.string.dotrino_identity_needed_install), filled = true) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, IdentityClient.installUri))
                sheet.dialog.dismiss()
            } catch (_: ActivityNotFoundException) {
                // Android apps go only through Play: without it, say so instead of sending elsewhere.
                msg.text = activity.getString(R.string.dotrino_apps_no_store)
            }
        }
        sheet.button(activity.getString(R.string.dotrino_identity_needed_later)) { sheet.dialog.dismiss() }
        if (!update) sheet.footnote(activity.getString(R.string.dotrino_identity_needed_note))
        return sheet.show()
    }
}
