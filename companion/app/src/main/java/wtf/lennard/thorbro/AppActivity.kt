package wtf.lennard.thorbro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** ThorBro on the device itself: the same UI as in the browser, usable with touch and gamepad. */
class AppActivity : Activity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ThorService.running) ThorService.start(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFF111317.toInt())
            addJavascriptInterface(Bridge(), "ThorBroApp")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // the server may still be starting: try again shortly
                    if (request.isForMainFrame) ui.postDelayed({ load() }, 800)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.host == "127.0.0.1") return false
                    startActivity(Intent(Intent.ACTION_VIEW, u)) // RomM pairing, RetroAchievements … in the browser
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = cb
                    return runCatching { startActivityForResult(p.createIntent(), REQ_FILES); true }.getOrElse { fileCallback = null; false }
                }
            }
        }
        setContentView(web)
        load()
    }

    private fun load() = web.loadUrl("http://127.0.0.1:${ThorService.PORT}/?app=1&token=${Prefs.token(this)}")

    @Deprecated("plain Activity, no androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILES) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // B on a gamepad / back: go back inside the UI first
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B)) {
            web.evaluateJavascript("window.thorbroBack && window.thorbroBack()") { r -> if (r != "true") finish() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class Bridge {
        @JavascriptInterface fun openSettings() = startActivity(Intent(this@AppActivity, MainActivity::class.java))
        @JavascriptInterface fun version(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }

    companion object { private const val REQ_FILES = 51 }
}
