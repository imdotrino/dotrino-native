package com.dotrino.sdk.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language (ES/EN), the same way in every Dotrino native app. Android 13+ keeps it
 * as the per-app language of the system; below that it is kept here and applied with [wrap].
 *
 * Every Activity that shows [DotrinoTopbar] calls `super.attachBaseContext(DotrinoLocale.wrap(base))`.
 */
object DotrinoLocale {
    private const val PREFS = "dotrino-locale"
    private const val KEY = "lang"

    /** The language in use: the one chosen in the app or, if none was chosen, the system's. */
    fun current(context: Context): String {
        val tag = chosen(context) ?: context.resources.configuration.locales[0].language
        return if (tag == "en") "en" else "es"
    }

    private fun chosen(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** Chooses [lang] ("es" / "en"). Android 13+ recreates the activity by itself; below, it is done here. */
    fun set(activity: Activity, lang: String) {
        if (lang == current(activity)) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(lang)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
            activity.recreate()
        }
    }

    /** Android 12 and below: the context with the chosen language. On 13+ the system already does it. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val lang = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return base.createConfigurationContext(config)
    }
}
