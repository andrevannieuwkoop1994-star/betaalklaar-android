package nl.betaalklaar.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var pendingShare: SharedInvoice? = null
    private val appUrl = "https://avn-factuurproef.andrevannieuwkoop199.chatgpt.site/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                pendingShare?.let { injectInvoice(it) }
            }
        }
        pendingShare = readSharedInvoice(intent)
        webView.loadUrl(appUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingShare = readSharedInvoice(intent)
        webView.loadUrl(appUrl)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun readSharedInvoice(intent: Intent): SharedInvoice? {
        if (intent.action != Intent.ACTION_SEND) return null
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        } ?: "factuur.pdf"
        val mime = contentResolver.getType(uri) ?: intent.type ?: "application/pdf"
        return SharedInvoice(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun injectInvoice(shared: SharedInvoice) {
        pendingShare = null
        val name = JSONObject.quote(shared.name)
        val mime = JSONObject.quote(shared.mime)
        val script = """
            (async function(){
              const binary=atob('${shared.base64}');
              const bytes=new Uint8Array(binary.length);
              for(let i=0;i<binary.length;i++) bytes[i]=binary.charCodeAt(i);
              const file=new File([bytes],$name,{type:$mime});
              const request=indexedDB.open('avn-share',1);
              request.onupgradeneeded=()=>request.result.createObjectStore('files');
              request.onsuccess=()=>{
                const tx=request.result.transaction('files','readwrite');
                tx.objectStore('files').put(file,'latest');
                tx.oncomplete=()=>location.replace('/?shared=1');
              };
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    data class SharedInvoice(val name: String, val mime: String, val base64: String)
}
