package io.github.syeohyun.smartphoto

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import io.github.syeohyun.smartphoto.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val saveLock = Any()
    private var pendingName = "photo.png"
    private var pendingMime = "image/png"
    private val pendingData = StringBuilder()

    private val fileChooser = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileCallback
        fileCallback = null
        if (callback == null) return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val webView = binding.web
        webView.setBackgroundColor(getColor(R.color.page_background))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = userAgentString + " SmartPhotoApp/1.0"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(DownloadBridge(), "SmartPhoto")
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueHttpDownload(url, userAgent, contentDisposition, mimeType)
        }

        binding.refresh.setColorSchemeResources(R.color.sky, R.color.navy)
        binding.refresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        binding.refresh.setOnRefreshListener {
            webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        binding.web.onResume()
    }

    override fun onPause() {
        binding.web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.web.apply {
            stopLoading()
            webChromeClient = null
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    private fun openExternal(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun enqueueHttpDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val manager = getSystemService(DownloadManager::class.java)
            manager.enqueue(request)
            toast(getString(R.string.saved_downloads))
        } catch (_: Exception) {
            toast(getString(R.string.save_failed))
        }
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray) {
        try {
            val safeName = sanitizeFileName(name)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SmartPhoto")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("insert")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            toast(getString(R.string.saved_downloads))
        } catch (_: Exception) {
            toast(getString(R.string.save_failed))
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "photo.png" }
        return cleaned.take(120)
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private inner class DownloadBridge {
        @JavascriptInterface
        fun start(name: String, mime: String) {
            synchronized(saveLock) {
                pendingName = name
                pendingMime = mime
                pendingData.setLength(0)
            }
        }

        @JavascriptInterface
        fun append(chunk: String) {
            synchronized(saveLock) {
                pendingData.append(chunk)
            }
        }

        @JavascriptInterface
        fun finish() {
            val name: String
            val mime: String
            val encoded: String
            synchronized(saveLock) {
                name = pendingName
                mime = pendingMime
                encoded = pendingData.toString()
                pendingData.setLength(0)
            }
            val bytes = try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                toast(getString(R.string.save_failed))
                return
            }
            saveBytes(name, mime, bytes)
        }
    }

    private inner class AppWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val host = uri.host ?: return false
            if (host == "syeohyun.github.io" || host.endsWith(".syeohyun.github.io")) {
                return false
            }
            return openExternal(uri)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.refresh.isRefreshing = false
            if (url != null && url.startsWith("https://syeohyun.github.io/")) {
                view.evaluateJavascript(DOWNLOAD_HOOK, null)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame || request.url.scheme == "data") return
            binding.refresh.isRefreshing = false
            view.loadDataWithBaseURL(HOME_URL, OFFLINE_HTML, "text/html", "utf-8", null)
        }
    }

    private inner class AppChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.progress.isVisible = newProgress in 1..99
            binding.progress.progress = newProgress
            if (newProgress == 100) binding.refresh.isRefreshing = false
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val message = resultMsg ?: return false
            val popup = WebView(this@MainActivity)
            popup.webViewClient = object : WebViewClient() {
                private var opened = false

                private fun openOnce(uri: Uri) {
                    if (opened) return
                    opened = true
                    openExternal(uri)
                    popup.post { popup.destroy() }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    openOnce(request.url)
                    return true
                }
            }
            val transport = message.obj as WebView.WebViewTransport
            transport.webView = popup
            message.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            val intent = try {
                fileChooserParams?.createIntent()
            } catch (_: Exception) {
                null
            } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return try {
                fileChooser.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                filePathCallback?.onReceiveValue(null)
                false
            }
        }
    }

    companion object {
        private const val HOME_URL = "https://syeohyun.github.io/-/"

        private val DOWNLOAD_HOOK = """
            (function () {
              if (window.__smartPhotoDownload) return;
              window.__smartPhotoDownload = true;
              document.addEventListener("click", function (event) {
                var node = event.target;
                var link = node && node.closest ? node.closest("a") : null;
                if (!link) return;
                var href = link.href || "";
                if (href.indexOf("blob:") !== 0 && href.indexOf("data:") !== 0) return;
                event.preventDefault();
                event.stopPropagation();
                var name = link.getAttribute("download") || "photo.png";
                var send = function (blob) {
                  var reader = new FileReader();
                  reader.onloadend = function () {
                    var result = String(reader.result || "");
                    var comma = result.indexOf(",");
                    if (comma < 0) return;
                    var meta = result.slice(0, comma);
                    var data = result.slice(comma + 1);
                    var mimeMatch = meta.match(/data:([^;]+)/);
                    var mime = (mimeMatch && mimeMatch[1]) || blob.type || "application/octet-stream";
                    SmartPhoto.start(name, mime);
                    var size = 256 * 1024;
                    for (var i = 0; i < data.length; i += size) {
                      SmartPhoto.append(data.substring(i, i + size));
                    }
                    SmartPhoto.finish();
                  };
                  reader.readAsDataURL(blob);
                };
                if (href.indexOf("data:") === 0) {
                  fetch(href).then(function (response) { return response.blob(); }).then(send);
                  return;
                }
                fetch(href).then(function (response) { return response.blob(); }).then(send).catch(function () {});
              }, true);
            })();
        """.trimIndent()

        private const val OFFLINE_HTML = """
            <!DOCTYPE html>
            <html lang="ko"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            </head><body style="margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f4f7fb;color:#0c2348;font-family:sans-serif">
            <div style="text-align:center;padding:24px">
            <p style="font-size:18px">인터넷에 연결할 수 없습니다.</p>
            <a href="https://syeohyun.github.io/-/" style="display:inline-block;margin-top:8px;background:#0c2348;color:#fff;text-decoration:none;padding:12px 20px;border-radius:999px">다시 시도</a>
            </div></body></html>
        """
    }
}
