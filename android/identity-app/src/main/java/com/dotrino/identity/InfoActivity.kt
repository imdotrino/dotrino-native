package com.dotrino.identity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dotrino.sdk.AccountStore
import com.dotrino.sdk.ui.DotrinoApps
import com.dotrino.sdk.ui.DotrinoLocale
import com.dotrino.sdk.ui.DotrinoTopbar

/**
 * La única pantalla: qué es esta app, cuántas cuentas guarda y las apps de Dotrino que la usan
 * (abrir las instaladas, instalar las que faltan). Con la barra nativa de Dotrino, sin perfil:
 * esta app es de todas las cuentas a la vez. Las cuentas se gestionan desde la app de Dotrino.
 */
class InfoActivity : Activity() {
    private lateinit var apps: LinearLayout
    private lateinit var error: TextView

    override fun attachBaseContext(base: Context) = super.attachBaseContext(DotrinoLocale.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun text(s: String, size: Float, color: Int) = TextView(this).apply {
            text = s; setTextColor(getColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setPadding(0, px(8), 0, px(8))
        }
        val count = runCatching { AccountStore(this).list().size }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(16), px(24), px(24))
            addView(text(getString(R.string.info_title), 22f, com.dotrino.sdk.R.color.dotrino_fg))
            addView(text(getString(R.string.info_body), 16f, com.dotrino.sdk.R.color.dotrino_muted))
            // No poder leer las cuentas se dice con su error, no se enseña como «0».
            addView(text(count.fold({ getString(R.string.info_accounts, it) }, { "⚠ ${it.message}" }), 15f, com.dotrino.sdk.R.color.dotrino_fg))
            addView(text(getString(com.dotrino.sdk.R.string.dotrino_apps_title), 18f, com.dotrino.sdk.R.color.dotrino_fg).apply { setPadding(0, px(24), 0, px(4)) })
            apps = LinearLayout(this@InfoActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(apps)
            error = text("", 14f, com.dotrino.sdk.R.color.dotrino_muted)
            addView(error)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(com.dotrino.sdk.R.color.dotrino_bg))
            fitsSystemWindows = true
            addView(DotrinoTopbar(this@InfoActivity, repo = "imdotrino/dotrino-native") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DotrinoTopbar.HOME)))
            }.view)
            addView(ScrollView(this@InfoActivity).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    // Al volver de Play, la app recién instalada ya sale con «Abrir».
    override fun onResume() {
        super.onResume()
        error.text = ""
        DotrinoApps.render(this, apps) { error.text = it }
    }
}
