package com.dotrino.identity

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dotrino.sdk.AccountStore

/**
 * La única pantalla: qué es esta app, y cuántas cuentas guarda. Informativa (CONVENCIONES §5.1):
 * no ejecuta nada. Las cuentas se gestionan desde la app de Dotrino.
 */
class InfoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun text(s: String, size: Float, color: Int) = TextView(this).apply {
            text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val count = runCatching { AccountStore(this).list().size }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            val pad = (24 * dp).toInt(); setPadding(pad, pad, pad, pad)
            addView(text(getString(R.string.info_title), 22f, Color.parseColor("#DBE7F7")))
            addView(text(getString(R.string.info_body), 16f, Color.parseColor("#8A9BB5")))
            // No poder leer las cuentas se dice con su error, no se enseña como «0».
            addView(text(count.fold({ getString(R.string.info_accounts, it) }, { "⚠ ${it.message}" }), 15f, Color.parseColor("#DBE7F7")))
        })
    }
}
