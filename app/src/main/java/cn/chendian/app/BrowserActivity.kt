package cn.chendian.app

import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.chendian.app.ui.HandbookTheme

/** Compose owns the chrome; Android WebView renders web content. */
class BrowserActivity : ComponentActivity() {
    private var browser: WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HandbookTheme {
            var address by rememberSaveable { mutableStateOf("") }
            BackHandler { browser?.let { if (it.canGoBack()) it.goBack() else finish() } ?: finish() }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("浏览器", style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(address, { address = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("输入 HTTPS 网址") })
                        Button(onClick = {
                            val raw = address.trim().let { if ("://" in it) it else "https://$it" }
                            val uri = Uri.parse(raw)
                            if (uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) browser?.loadUrl(raw)
                            else Toast.makeText(this@BrowserActivity, "请输入有效 HTTPS 网址", Toast.LENGTH_SHORT).show()
                        }) { Text("前往") }
                    }
                    AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                        WebView(context).apply {
                            browser = this
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.domStorageEnabled = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !request.url.scheme.equals("https", true)
                                override fun onPageFinished(view: WebView, url: String) { address = url }
                            }
                            if (savedInstanceState != null) restoreState(savedInstanceState)
                        }
                    })
                    Text("识别待完善", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { finish() }) { Text("返回导入页") }
                }
            }
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) { browser?.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() { browser?.stopLoading(); browser?.destroy(); browser = null; super.onDestroy() }
}
