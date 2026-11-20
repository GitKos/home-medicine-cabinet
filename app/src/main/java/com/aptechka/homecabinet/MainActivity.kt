package com.aptechka.homecabinet

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("file:///android_asset/")) return false
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val hookJs = """
                    (function() {
                        if (window._androidHooked) return;
                        window._androidHooked = true;
                        const origTrigger = window.triggerAlarm;
                        if (typeof origTrigger === 'function') {
                            window.triggerAlarm = function(sched, timeStr) {
                                origTrigger(sched, timeStr);
                                if (window.AndroidBridge && window.AndroidBridge.showNotification) {
                                    window.AndroidBridge.showNotification(
                                        'Пора принять: ' + (sched.medName || 'Лекарство'),
                                        'Доза: ' + (sched.doseText || '') + (sched.note ? ' • ' + sched.note : '')
                                    );
                                }
                            };
                        }
                    })();
                """.trimIndent()
                view?.evaluateJavascript(hookJs, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: return false
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    class AndroidBridge(private val context: Context) {

        private fun httpGet(urlStr: String): String {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        private fun cleanHtml(raw: String): String {
            return raw
                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun extractVidalSection(html: String, ids: List<String>): String {
            for (id in ids) {
                val pattern = Regex(
                    """id=["']$id["'][\s\S]*?<div[^>]*class=["'][^"']*block-content[^"']*["'][^>]*>([\s\S]*?)</div>""",
                    RegexOption.IGNORE_CASE
                )
                val match = pattern.find(html)
                if (match != null) {
                    val txt = cleanHtml(match.groupValues[1])
                    if (txt.isNotEmpty()) {
                        return if (txt.length > 700) txt.take(700) + "…" else txt
                    }
                }
            }
            return ""
        }

        @JavascriptInterface
        fun fetchOfficialInstructionSync(drugName: String): String {
            return try {
                val cleanQuery = drugName
                    .replace(Regex("\\(.*?\\)"), "")
                    .replace(Regex("\\b(сироп|таблетки|мазь|капли|спрей|гель|раствор|капсулы)\\b", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifEmpty { drugName.trim() }

                val searchUrl = "https://www.vidal.ru/search?t=all&q=" + URLEncoder.encode(cleanQuery, "UTF-8")
                val searchHtml = httpGet(searchUrl)

                val drugLinkMatch = Regex("""href=["'](/drugs/[a-zA-Z0-9_-]+)["']""").find(searchHtml)
                    ?: return ""
                val drugUrl = "https://www.vidal.ru" + drugLinkMatch.groupValues[1]
                val drugHtml = httpGet(drugUrl)

                val indications = extractVidalSection(drugHtml, listOf("indication", "indications"))
                val dosage = extractVidalSection(drugHtml, listOf("dosage"))
                val warnings = extractVidalSection(drugHtml, listOf("contra", "side_effects", "special"))
                val storage = extractVidalSection(drugHtml, listOf("storage_conditions", "storage"))

                val imgMatch = Regex("""<img[^>]+src=["'](https://www\.vidal\.ru/upload/products/[^"']+|/upload/products/[^"']+)["']""").find(drugHtml)
                val photoUrl = imgMatch?.groupValues?.get(1)?.let {
                    if (it.startsWith("http")) it else "https://www.vidal.ru$it"
                } ?: ""

                JSONObject().apply {
                    put("source", "Vidal.ru (официальный справочник)")
                    put("url", drugUrl)
                    put("photo", photoUrl)
                    put("indications", indications)
                    put("dosage", dosage)
                    put("warnings", warnings)
                    put("storage", storage)
                }.toString()
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun showNotification(title: String, body: String) {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("body", body)
            }
            context.sendBroadcast(intent)
        }

        @JavascriptInterface
        fun scheduleDailyAlarm(idHash: Int, hour: Int, minute: Int, title: String, body: String) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra("title", title)
                    putExtra("body", body)
                    putExtra("notifId", idHash)
                }
                val pending = PendingIntent.getBroadcast(
                    context, idHash, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(Calendar.getInstance())) add(Calendar.DATE, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
                } else {
                    alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pending)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun cancelAlarm(idHash: Int) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, AlarmReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    context, idHash, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pending)
            } catch (_: Exception) {}
        }
    }
}
