package com.dotrino.sdk.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.dotrino.sdk.IdentityClient
import com.dotrino.sdk.PhoneProfiles
import com.dotrino.sdk.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A PAGE OF THE ECOSYSTEM INSIDE THE APP, with the phone's identity: open your profile, create
 * one, adopt one, sign in. They are web (they live in profile.dotrino.com / vault.dotrino.com and
 * are the same for every app), so a WebView is right here (CONVENCIONES §16.2: WebView only for
 * content that IS web). What makes it the SAME identity and not another one is
 * [IdentityWebBridge]: the page's identity uses the keys and the store of the identity app.
 *
 * On leaving, if the active profile changed (created, adopted, signed in, switched), the app
 * restarts with it — changing profile is not reactive, as on the web.
 */
class DotrinoWebActivity : Activity() {
    companion object {
        private const val URL = "url"
        private const val REQ_FILE = 71
        private const val REQ_CAMERA = 72
        private val INSIDE = Regex("^([a-z0-9-]+\\.)?dotrino\\.com$")

        fun open(context: Context, url: String) {
            context.startActivity(Intent(context, DotrinoWebActivity::class.java).putExtra(URL, url)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    private lateinit var web: WebView
    private val scope = MainScope()
    private var pidAtStart: String? = null
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(URL)?.takeIf { Uri.parse(it).host?.matches(INSIDE) == true } ?: run { finish(); return }
        scope.launch { pidAtStart = runCatching { withContext(Dispatchers.IO) { PhoneProfiles.currentPid(IdentityClient.shared(this@DotrinoWebActivity)) } }.getOrNull() }
        web = WebView(this)
        setupWeb()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.dotrino_bg))
            fitsSystemWindows = true
            addView(header(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        if (savedInstanceState == null) web.loadUrl(url) else web.restoreState(savedInstanceState)
    }

    /** Only «Close»: the page has its own navigation, and closing returns to the app. */
    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(getColor(R.color.dotrino_card))
        setPadding(px(8), 0, px(8), 0)
        addView(TextView(context).apply {
            text = "✕"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setTextColor(getColor(R.color.dotrino_fg))
            gravity = Gravity.CENTER; contentDescription = getString(R.string.dotrino_support_close); tag = "web-close"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(px(44), px(44)))
        addView(TextView(context).apply {
            text = getString(R.string.dotrino_profile_cta); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.dotrino_fg)); setPadding(px(8), 0, 0, 0)
        })
    }

    private fun setupWeb() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        // The identity iframe (id.dotrino.com) is "third party" for the WebView: without this it keeps nothing.
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        IdentityWebBridge.install(web, this)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host?.matches(INSIDE) == true) return false
                // Outside the ecosystem: the system browser, not this screen.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileChooser?.onReceiveValue(null)
                fileChooser = callback
                val type = params.acceptTypes.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
                runCatching { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type), REQ_FILE) }
                    .onFailure { fileChooser = null; callback.onReceiveValue(null) }
                return true
            }
            // The camera (scanning a pairing QR): the system permission is asked, then granted to the page.
            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.origin.host?.matches(INSIDE) != true) { request.deny(); return }
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                    checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    pendingPermission = request; requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                } else request.grant(request.resources)
            }
        }
    }

    @Deprecated("Activity without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileChooser?.onReceiveValue(data?.data?.takeIf { resultCode == RESULT_OK }?.let { arrayOf(it) })
            fileChooser = null
        } else @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode != REQ_CAMERA) return
        val p = pendingPermission ?: return
        pendingPermission = null
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) p.grant(p.resources) else p.deny()
    }

    @Deprecated("Activity without AndroidX: the back button still comes here")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    /** Leaving: if the page changed the active profile, the whole app restarts with it. */
    override fun finish() {
        val before = pidAtStart
        CoroutineScope(Dispatchers.IO).launch {
            val now = runCatching { PhoneProfiles.currentPid(IdentityClient.shared(this@DotrinoWebActivity)) }.getOrNull()
            if (now != null && now != before) withContext(Dispatchers.Main) { DotrinoApps.restart(applicationContext) }
        }
        super.finish()
    }

    override fun onDestroy() { scope.cancel(); if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
