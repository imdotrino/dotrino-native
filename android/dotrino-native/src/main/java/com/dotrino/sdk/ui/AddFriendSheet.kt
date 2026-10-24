package com.dotrino.sdk.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dotrino.sdk.BleWire
import com.dotrino.sdk.Delegation
import com.dotrino.sdk.Friends
import com.dotrino.sdk.Nearby
import com.dotrino.sdk.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * «AÑADIR CONTACTO», la hoja de cualquier app nativa: la versión de `<dotrino-add-friend>`
 * (`@dotrino/profile/friends-ui`) más lo que solo puede un teléfono.
 *
 *  - POR CÓDIGO: los 6 caracteres del otro (tecleados, pegados o escaneados) y un apodo opcional →
 *    una SOLICITUD ([Friends.requestByCode]).
 *  - MI CÓDIGO: el mío y su QR (un enlace: la cámara de un teléfono abre la app con el código puesto).
 *  - CERCA: mientras está abierta, mi tarjeta de contacto sale por Bluetooth y por la red local
 *    ([Nearby]) y entran las de quien esté cerca (`dotrino-vault/docs/pairing-protocol.md` §1.8);
 *    «Añadir» manda la misma solicitud. Que respondan varios es lo normal: aquí no hay incidente.
 *
 * Lo que no puede hacer por sí misma lo pide la app: escanear ([scan], que pide la cámara y luego
 * llama a [scanned]) y preparar el Bluetooth ([prepareBluetooth], que pide el permiso). Con un
 * enlace `#to=` ([peer]) ya se sabe a quién y solo queda el apodo.
 */
class AddFriendSheet(
    private val activity: Activity,
    private val friends: Friends,
    private val scope: CoroutineScope,
    /** La página a la que lleva el QR de mi código (`<linkBase>#add=<código>`). */
    private val linkBase: String,
    code: String? = null,
    startTab: String = TAB_CODE,
    private val peer: Peer? = null,
    private val scan: (() -> Unit)? = null,
    /** Cerca: sin esto no hay pestaña. */
    private val nearby: ((bluetoothOn: Boolean) -> Nearby)? = null,
    /** Deja el Bluetooth listo (permiso incluido); `done(true)` si quedó encendido. */
    private val prepareBluetooth: ((done: (Boolean) -> Unit) -> Unit)? = null,
    private val palette: Palette = Palette.dark(activity),
) {
    companion object {
        const val TAB_CODE = "add"
        const val TAB_MINE = "mine"
        const val TAB_NEARBY = "nearby"
    }

    /** A quién pide un enlace `#to=`: su llave, el apodo y su tarjeta si vino. */
    data class Peer(val pubkey: String, val nickname: String, val card: JsonObject?)

    /** Los colores de la app que la monta (el tema de cada una); por defecto, el oscuro del ecosistema. */
    data class Palette(val card: Int, val card2: Int, val text: Int, val muted: Int, val line: Int, val accent: Int, val onAccent: Int, val danger: Int) {
        companion object {
            fun dark(c: Context) = Palette(c.getColor(R.color.dotrino_card), c.getColor(R.color.dotrino_bg), c.getColor(R.color.dotrino_fg),
                c.getColor(R.color.dotrino_muted), c.getColor(R.color.dotrino_muted), c.getColor(R.color.dotrino_accent), Color.WHITE, 0xFFE5534B.toInt())
        }
    }

    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Number) = (v.toFloat() * dp).toInt()
    private fun s(id: Int) = activity.getString(id)

    val dialog: Dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), px(24)) }
    private var tab = if (startTab == TAB_NEARBY && nearby == null) TAB_CODE else startTab
    private val input = field(s(R.string.dotrino_friends_ph_code)).apply { tag = "code-input"; setText(code ?: ""); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER }
    private val alias = field(s(R.string.dotrino_friends_ph_alias)).apply { tag = "alias-input"; peer?.nickname?.let { setText(it) } }
    private val errorView = label("", 14f, palette.danger).apply { visibility = View.GONE; tag = "add-error" }
    private var sending = false

    init {
        val head = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(16), px(12), px(4))
            addView(label(s(R.string.dotrino_friends_title), 20f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("✕", 20f, palette.muted).apply {
                setPadding(px(12), px(6), px(12), px(6)); contentDescription = s(R.string.dotrino_friends_close); tag = "sheet-close"
                setOnClickListener { dialog.dismiss() }
            })
        }
        dialog.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val r = px(28).toFloat()
            background = GradientDrawable().apply { cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setColor(palette.card) }
            addView(head)
            addView(ScrollView(activity).apply { addView(body) })
        })
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(0) })
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.setOnDismissListener { stopNearby() }
        render()
    }

    fun show() = dialog.show()

    /** Cambió algo (mi código se renovó): se repinta si se está enseñando. */
    fun refresh() { if (tab == TAB_MINE && dialog.isShowing) render() }

    /** Lo que leyó el escáner (o una foto): un código, o nada que sirva. */
    fun scanned(text: String?) {
        tab = TAB_CODE
        val code = text?.let(Friends::codeFromText)
        if (code == null || !Friends.CODE_RE.matches(code)) { render(); showError(R.string.dotrino_friends_err_no_code); return }
        input.setText(code); render(); submit()
    }

    // ---- piezas ----

    private fun label(text: String, sp: Float, color: Int = palette.text, bold: Boolean = false) = TextView(activity).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun pill(text: String, filled: Boolean = false, onClick: () -> Unit) = TextView(activity).apply {
        this.text = text; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (filled) palette.onAccent else palette.text)
        background = if (filled) rounded(palette.accent, px(24)) else rounded(palette.card, px(24), px(1), palette.line)
        setPadding(px(20), px(12), px(20), px(12))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun field(hint: String) = EditText(activity).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(palette.text); setHintTextColor(palette.muted)
        background = rounded(palette.card, px(16), px(1), palette.line)
        setPadding(px(14), px(12), px(14), px(12))
        isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun LinearLayout.add(v: View, top: Int = 0, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        addView(v, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) })

    private fun detach(v: View) { (v.parent as? ViewGroup)?.removeView(v) }

    private fun showError(id: Int) { errorView.text = s(id); errorView.visibility = View.VISIBLE }

    private fun errorFor(e: Exception): Int = when ((e as? Friends.FriendsError)?.code) {
        "own" -> R.string.dotrino_friends_err_own
        "offline" -> R.string.dotrino_friends_err_offline
        "invalid" -> R.string.dotrino_friends_err_invalid
        else -> R.string.dotrino_friends_err_send
    }

    // ---- pintar ----

    private fun render() {
        body.removeAllViews()
        val tabs = buildList {
            add(TAB_CODE to R.string.dotrino_friends_tab_code); add(TAB_MINE to R.string.dotrino_friends_tab_mine)
            if (nearby != null) add(TAB_NEARBY to R.string.dotrino_friends_tab_nearby)
        }
        if (peer == null) body.add(LinearLayout(activity).apply {
            background = rounded(palette.card2, px(24)); setPadding(px(4), px(4), px(4), px(4))
            for ((id, res) in tabs) addView(label(s(res), 14f, if (tab == id) palette.text else palette.muted, bold = tab == id).apply {
                gravity = Gravity.CENTER; setPadding(0, px(9), 0, px(9))
                tag = when (id) { TAB_MINE -> "share-my-token-tab"; TAB_NEARBY -> "nearby-tab"; else -> "add-tab" }
                if (tab == id) background = rounded(palette.card, px(20))
                setOnClickListener { tab = id; errorView.visibility = View.GONE; render() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        if (tab != TAB_NEARBY) stopNearby() else startNearby()
        when {
            peer != null -> peerTab(peer)
            tab == TAB_MINE -> mineTab()
            tab == TAB_NEARBY -> nearbyTab()
            else -> codeTab()
        }
    }

    private fun codeTab() {
        body.add(label(s(R.string.dotrino_friends_info), 14f, palette.muted), top = 16)
        body.add(label(s(R.string.dotrino_friends_field_code), 13f, palette.muted, bold = true), top = 14)
        detach(input)
        body.add(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill(s(R.string.dotrino_friends_paste)) {
                val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                clip?.getItemAt(0)?.coerceToText(activity)?.toString()?.let { input.setText(Friends.codeFromText(it)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8) })
        }, top = 6)
        scan?.let { open -> body.add(pill(s(R.string.dotrino_friends_scan)) { open() }.apply { tag = "scan-qr" }, top = 10, width = ViewGroup.LayoutParams.WRAP_CONTENT) }
        aliasAndSend { submit() }
    }

    /** Una solicitud a la llave de un enlace: a quién va, el apodo y «enviar». */
    private fun peerTab(p: Peer) {
        val nick = p.nickname.ifEmpty { s(R.string.dotrino_friends_to_no_nick) }
        body.add(label(activity.getString(R.string.dotrino_friends_to_info, nick), 14f, palette.muted).apply { tag = "to-info" }, top = 16)
        aliasAndSend { submitToPeer(p) }
    }

    private fun aliasAndSend(send: () -> Unit) {
        body.add(label(s(R.string.dotrino_friends_field_alias), 13f, palette.muted, bold = true), top = 14)
        detach(alias); body.add(alias, top = 6)
        detach(errorView); body.add(errorView, top = 10)
        body.add(label(s(R.string.dotrino_friends_hint), 13f, palette.muted), top = 10)
        body.add(pill(s(if (sending) R.string.dotrino_friends_sending else R.string.dotrino_friends_send), filled = true) { send() }.apply {
            tag = "send-hello"; isEnabled = !sending; alpha = if (sending) 0.5f else 1f
        }, top = 16)
    }

    private fun mineTab() {
        val code = friends.code
        body.add(label(s(R.string.dotrino_friends_mine_info), 14f, palette.muted), top = 16)
        body.add(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(code ?: "…", 28f, bold = true).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.15f; tag = "my-pairing-code" },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill(s(R.string.dotrino_friends_copy)) {
                code ?: return@pill
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", code))
            })
        }, top = 12)
        if (code != null) {
            val size = px(220)
            body.add(LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                addView(ImageView(activity).apply {
                    tag = "my-qr"
                    setImageBitmap(DotrinoQr.bitmap("$linkBase#add=$code", size, Color.BLACK, Color.WHITE))
                    background = rounded(Color.WHITE, px(16)); setPadding(px(10), px(10), px(10), px(10))
                }, LinearLayout.LayoutParams(size + px(20), size + px(20)))
            }, top = 16)
            body.add(label(s(R.string.dotrino_friends_qr_hint), 13f, palette.muted).apply { gravity = Gravity.CENTER }, top = 8)
        }
    }

    // ---- cerca ----

    private class Found(val card: BleWire.Contact, var state: String = "new")
    private val found = LinkedHashMap<String, Found>()
    private var nearbyOn = false
    private var lanOnly = false
    private var unannounce: (() -> Unit)? = null
    private var looking: Job? = null

    /** Encendido: anuncio mi tarjeta y busco, en rondas, hasta que se cierre la pestaña o la hoja. */
    private fun startNearby() {
        val make = nearby ?: return
        if (nearbyOn) return
        nearbyOn = true
        val go = { btOn: Boolean ->
            if (nearbyOn) {
                lanOnly = !btOn
                val n = make(btOn)
                val me = friends.profile.publickey
                unannounce = n.announce(BleWire.KIND_CONTACT, BleWire.encodeContact(me, friends.me()["nickname"]?.let { (it as? JsonPrimitive)?.content } ?: ""))
                looking = scope.launch {
                    val mine = friends.peers.contacts().mapNotNull { (it["publickey"] as? JsonPrimitive)?.content }
                    while (nearbyOn) {
                        try {
                            n.discover(BleWire.KIND_CONTACT, 6000) { data ->
                                val c = BleWire.decodeContact(data) ?: return@discover
                                if (Delegation.samePubkey(c.publickey, me)) return@discover
                                activity.runOnUiThread {
                                    if (found.keys.none { Delegation.samePubkey(it, c.publickey) }) {
                                        found[c.publickey] = Found(c, if (mine.any { Delegation.samePubkey(it, c.publickey) }) "contact" else "new")
                                        if (tab == TAB_NEARBY && dialog.isShowing) render()
                                    }
                                }
                            }
                        } catch (x: Exception) { android.util.Log.w("dotrino", "nearby: discover", x); delay(1000) }
                    }
                }
                if (tab == TAB_NEARBY && dialog.isShowing) render()
            }
        }
        val prep = prepareBluetooth
        if (prep == null) go(false) else prep { on -> activity.runOnUiThread { go(on) } }
    }

    private fun stopNearby() {
        nearbyOn = false
        unannounce?.invoke(); unannounce = null
        looking?.cancel(); looking = null
    }

    private fun nearbyTab() {
        body.add(label(s(R.string.dotrino_friends_nearby_info), 14f, palette.muted), top = 16)
        if (lanOnly && unannounce != null) body.add(label(s(R.string.dotrino_friends_nearby_lan_only), 13f, palette.muted).apply { tag = "nearby-lan-only" }, top = 8)
        if (found.isEmpty()) {
            body.add(label(s(R.string.dotrino_friends_nearby_searching), 14f).apply { tag = "nearby-searching" }, top = 16)
            body.add(label(s(R.string.dotrino_friends_nearby_none), 13f, palette.muted), top = 6)
            return
        }
        for (f in found.values) body.add(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; tag = "nearby-item"
            val name = f.card.nickname.ifEmpty { s(R.string.dotrino_friends_nearby_no_nick) }
            addView(DotrinoAvatarView(activity, f.card.publickey), LinearLayout.LayoutParams(px(40), px(40)))
            addView(label(name, 16f, bold = true).apply { setPadding(px(12), 0, px(8), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            when (f.state) {
                "contact" -> addView(label(s(R.string.dotrino_friends_nearby_contact), 13f, palette.muted))
                "sent" -> addView(label(s(R.string.dotrino_friends_nearby_sent), 13f, palette.muted))
                "sending" -> addView(label(s(R.string.dotrino_friends_sending), 13f, palette.muted))
                else -> addView(pill(s(R.string.dotrino_friends_nearby_add), filled = true) { addNearby(f) }.apply { tag = "nearby-add" })
            }
        }, top = 12)
        detach(errorView); body.add(errorView, top = 10)
    }

    private fun addNearby(f: Found) {
        errorView.visibility = View.GONE
        f.state = "sending"; render()
        scope.launch {
            try {
                friends.request(f.card.publickey, f.card.nickname)
                f.state = "sent"
            } catch (x: Exception) {
                android.util.Log.w("dotrino", "request nearby", x); f.state = "new"; showError(errorFor(x))
            }
            if (dialog.isShowing && tab == TAB_NEARBY) render()
        }
    }

    // ---- mandar ----

    private fun submitToPeer(p: Peer) {
        if (sending) return
        errorView.visibility = View.GONE
        sending = true; render()
        scope.launch {
            try {
                friends.request(p.pubkey, alias.text.toString(), p.card)
                dialog.dismiss()
            } catch (x: Exception) { android.util.Log.w("dotrino", "request by link", x); showError(errorFor(x)) }
            sending = false
            if (dialog.isShowing) render()
        }
    }

    private fun submit() {
        if (sending) return
        errorView.visibility = View.GONE
        val code = Friends.normalizeCode(input.text.toString())
        if (!Friends.CODE_RE.matches(code)) { showError(R.string.dotrino_friends_err_invalid); return }
        sending = true; render()
        scope.launch {
            try {
                friends.requestByCode(code, alias.text.toString())
                dialog.dismiss()
            } catch (x: Exception) {
                // «Sin red» y «ese código no vale» se arreglan distinto: se dice cuál.
                android.util.Log.w("dotrino", "add contact", x); showError(errorFor(x))
            }
            sending = false
            if (dialog.isShowing) render()
        }
    }
}
