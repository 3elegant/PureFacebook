package com.deenelife.facebook

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var errorLayout: LinearLayout
    private lateinit var blockLayout: LinearLayout
    private lateinit var safetyLayout: ScrollView
    private lateinit var cvCountdown: MaterialCardView
    private lateinit var tvCountdown: TextView
    private lateinit var tvBlockMessage: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnCloseApp: Button
    private lateinit var btnAgree: Button
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult

        val results: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            val dataString = result.data?.dataString
            val clipData = result.data?.clipData

            if (clipData != null) {
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else if (dataString != null) {
                arrayOf(Uri.parse(dataString))
            } else null
        } else null

        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    data class WarningData(val title: String, val text: String)

    private var intervalMinutes = 5
    private var warningList = mutableListOf<WarningData>()
    private var timeLeftSeconds: Int = 0
    private var isExtended = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private lateinit var timerRunnable: Runnable
    private val warningHandler = Handler(Looper.getMainLooper())
    private lateinit var warningRunnable: Runnable

    private val PREFS_NAME = "AppPrefs"
    private val KEY_IS_FIRST_RUN = "isFirstRun"

    private val KEY_DAILY_USAGE_SECONDS = "dailyUsageSeconds"
    private val KEY_LAST_USED_DATE = "lastUsedDate"
    private var dailyUsageSeconds = 0

    private val WARNING_JSON_URL = "https://raw.githubusercontent.com/3elegant/PureFacebook/refs/heads/master/data.json"
    private val UPDATE_JSON_URL = "https://raw.githubusercontent.com/3elegant/PureFacebook/refs/heads/master/update.json"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        firebaseAnalytics = Firebase.analytics

        webView = findViewById(R.id.webView)
        errorLayout = findViewById(R.id.errorLayout)
        blockLayout = findViewById(R.id.blockLayout)
        safetyLayout = findViewById(R.id.safetyLayout)
        cvCountdown = findViewById(R.id.cvCountdown)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvBlockMessage = findViewById(R.id.tvBlockMessage)
        btnRetry = findViewById(R.id.btnRetry)
        btnCloseApp = findViewById(R.id.btnCloseApp)
        btnAgree = findViewById(R.id.btnAgree)

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.surface))

        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true

            val defaultUserAgent = userAgentString
            userAgentString = defaultUserAgent.replace("; wv", "").replace("Version/4.0 ", "")
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(this, "Android")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFile(url, userAgent, contentDisposition, mimeType)
        }

        registerForContextMenu(webView)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                try {
                    if (intent != null) {
                        fileChooserLauncher.launch(intent)
                    }
                } catch (e: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_SHORT).show()
                    return false
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val url = uri.toString()

                if (url.startsWith("tel:", ignoreCase = true) ||
                    url.startsWith("mailto:", ignoreCase = true) ||
                    url.startsWith("sms:", ignoreCase = true) ||
                    url.startsWith("https://wa.me") ||
                    url.startsWith("whatsapp://") ||
                    url.startsWith("fb-messenger://") ||
                    url.startsWith("threads://") ||
                    url.startsWith("intent:", ignoreCase = true)) {

                    try {
                        val intent = if (url.startsWith("intent:", ignoreCase = true)) {
                            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        } else {
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        }
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this@MainActivity, "No appropriate app found to open this link!", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                val host = uri.host
                if (host != null && !host.contains("facebook.com") && !host.contains("fb.me") && !host.contains("fb.watch")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this@MainActivity, "Unable to open link!", Toast.LENGTH_SHORT).show()
                    }
                }

                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectOriginalAndNewScripts(view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                injectOriginalAndNewScripts(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectOriginalAndNewScripts(view)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) showErrorPage()
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                Toast.makeText(this@MainActivity, "Insecure connection (SSL Error) blocked.", Toast.LENGTH_SHORT).show()
            }
        }

        btnRetry.setOnClickListener { loadFacebook() }
        btnCloseApp.setOnClickListener { finish() }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean(KEY_IS_FIRST_RUN, true)

        if (isFirstRun) {
            showSafetyScreen()
        } else {
            startAppNormalFlow()
        }

        btnAgree.setOnClickListener {
            prefs.edit().putBoolean(KEY_IS_FIRST_RUN, false).apply()
            safetyLayout.visibility = View.GONE
            startAppNormalFlow()

            val bundle = Bundle()
            bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, "SafetyScreenAccepted")
            firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (safetyLayout.visibility == View.VISIBLE) return
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun showSafetyScreen() {
        safetyLayout.visibility = View.VISIBLE
    }

    private fun startAppNormalFlow() {
        showSessionSelector()
        fetchWarningData()
        checkForUpdates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_IS_FIRST_RUN, true)) {
            loadFacebook()
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu?, v: View?, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val result = webView.hitTestResult

        if (result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            val imageUrl = result.extra
            if (imageUrl != null && isDownloadableImage(imageUrl)) {
                menu?.setHeaderTitle("Image options")
                menu?.add(0, 1, 0, "Download image")?.setOnMenuItemClickListener {
                    downloadFile(imageUrl, null, null, null)
                    true
                }
            }
        }
    }

    private fun isDownloadableImage(url: String): Boolean {
        return !url.contains("rsrc.php") &&
                !url.contains("static.xx.fbcdn.net") &&
                !url.contains("facebook.com/images/") &&
                !url.contains("emoji")
    }

    private fun downloadFile(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:")) {
            val js = """
                (function() {
                    var img = new Image();
                    img.crossOrigin = 'Anonymous';
                    img.onload = function() {
                        var canvas = document.createElement('canvas');
                        canvas.width = this.naturalWidth;
                        canvas.height = this.naturalHeight;
                        var ctx = canvas.getContext('2d');
                        ctx.drawImage(this, 0, 0);
                        var base64 = canvas.toDataURL('image/jpeg', 1.0);
                        Android.saveBlobFile(base64);
                    };
                    img.onerror = function() {
                        Android.showToast('Unable to process image!');
                    };
                    img.src = '$url';
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            return
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            request.setMimeType(mimeType)
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent)
            request.setTitle(fileName)
            request.setDescription("Downloading...")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed!", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun saveBlobFile(base64Data: String) {
        try {
            val pureBase64 = base64Data.substringAfter(",")
            val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "FB_IMG_$timeStamp.jpg"

            val outputStream: OutputStream?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val imageUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                outputStream = imageUri?.let { resolver.openOutputStream(it) }
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadDir, fileName)
                outputStream = FileOutputStream(file)
            }

            outputStream?.use { it.write(decodedBytes) }

            runOnUiThread {
                Toast.makeText(this, "Image saved: $fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread { Toast.makeText(this, "There was a problem saving the image!", Toast.LENGTH_SHORT).show() }
        }
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun injectOriginalAndNewScripts(view: WebView?) {
        val jsCode = """
            javascript:(function() {
                try {
                    function setup() {
                        var currentUrl = window.location.href.toLowerCase();
                        var hasPasswordField = document.querySelector('input[type="password"], input[name="pass"], input[id="m_login_password"], input[name="approvals_code"]') !== null;
                        
                        if (hasPasswordField || currentUrl.includes('/login') || currentUrl.includes('checkpoint') || currentUrl.includes('2fac') || currentUrl.includes('security')) {
                            return;
                        }

                        if (!document.head || !document.body) {
                            setTimeout(setup, 50);
                            return;
                        }

                        var hasFeedContainer = document.querySelector('div[data-mcomponent="MContainer"]') !== null;
                        var hasNavigationBar = document.querySelector('div[role="navigation"]') !== null;
                        var hasLogo = document.querySelector('[aria-label="Facebook logo"]') !== null;

                        if (!hasFeedContainer && !hasNavigationBar && !hasLogo) {
                            setTimeout(setup, 200); 
                            return;
                        }

                        if (document.getElementById('fb-script-injected')) return;
                        var marker = document.createElement('meta');
                        marker.id = 'fb-script-injected';
                        document.head.appendChild(marker);

                        function toggleHidingStyles() {
                            var isBookmarksPage = window.location.href.includes('/bookmarks/');
                            var styleId = 'fb-hiding-style';
                            var existingStyle = document.getElementById(styleId);

                            if (!existingStyle) {
                                existingStyle = document.createElement('style');
                                existingStyle.id = styleId;
                                document.head.appendChild(existingStyle);
                            }

                            if (isBookmarksPage) {
                                existingStyle.innerHTML = `
                                    a[href*="/reels/"], 
                                    div[data-mcomponent="MContainer"] div[aria-label*="Reels"],
                                    div[data-mcomponent="MContainer"] div[aria-label*="রিলস"],
                                    div[data-mcomponent="MContainer"]:has(a[href*="play.google.com"]),
                                    div[data-mcomponent="MContainer"]:has(a[href*="store/apps/details"]) { 
                                        display: none !important; 
                                        visibility: hidden !important;
                                        height: 0 !important;
                                        overflow: hidden !important;
                                    }
                                `;
                            } else {
                                existingStyle.innerHTML = `
                                    div[role="tab"][aria-label*="reels"], 
                                    div[aria-label*="Reels"],
                                    [data-actual-height="67"],
                                    [data-actual-height="61"],
                                    div[data-mcomponent="MContainer"]:has(a[href*="play.google.com"]),
                                    div[data-mcomponent="MContainer"]:has(a[href*="store/apps/details"]) { 
                                        display: none !important; 
                                        visibility: hidden !important;
                                        height: 0 !important;
                                        overflow: hidden !important;
                                    }
                                `;
                            }

                            var logoStyleId = 'fb-logo-style';
                            if (!document.getElementById(logoStyleId)) {
                                var lStyle = document.createElement('style');
                                lStyle.id = logoStyleId;
                                lStyle.innerHTML = `
                                    [aria-label="Facebook logo"], [data-actual-height="23"], div[role="button"] img[src*="UYsfyMdMkZ_"] { 
                                        display: block !important; 
                                        visibility: visible !important; 
                                        opacity: 1 !important; 
                                        filter: none !important;
                                    }
                                `;
                                document.head.appendChild(lStyle);
                            }

                            var enableSelectionStyleId = 'fb-enable-selection-style';
                            if (!document.getElementById(enableSelectionStyleId)) {
                                var eStyle = document.createElement('style');
                                eStyle.id = enableSelectionStyleId;
                                eStyle.innerHTML = `
                                    body, div, span, p, a, h1, h2, h3, h4, h5, h6, 
                                    [data-long-click-action-id], ._52jc, ._5jmm, .story_body_container, 
                                    .native-text, [data-mcomponent="TextArea"], [data-mcomponent="MContainer"], 
                                    [data-focusable="true"], .m, .f4, .rslh {
                                        -webkit-user-select: text !important;
                                        user-select: text !important;
                                        -webkit-touch-callout: default !important;
                                    }
                                    .native-text, .native-text span, [data-mcomponent="TextArea"] div {
                                        pointer-events: auto !important;
                                    }
                                `;
                                document.head.appendChild(eStyle);
                            }

                            var calloutStyleId = 'fb-callout-style';
                            if (!document.getElementById(calloutStyleId)) {
                                var cStyle = document.createElement('style');
                                cStyle.id = calloutStyleId;
                                cStyle.innerHTML = `
                                    div[role="button"], 
                                    [aria-label*="reaction"], 
                                    [aria-label*="Like"], 
                                    [aria-label*="লাইক"] { 
                                        -webkit-touch-callout: none !important; 
                                        -webkit-user-select: none !important;
                                        user-select: none !important;
                                    }
                                `;
                                document.head.appendChild(cStyle);
                            }
                        }

                        function processMediaAndAds() {
                            toggleHidingStyles();

                            var isBookmarksPage = window.location.href.includes('/bookmarks/');
                            if (!isBookmarksPage) {
                                document.querySelectorAll('div[data-mcomponent="MContainer"]').forEach(function(el) {
                                    var text = el.innerText || "";
                                    var isShareMenu = el.closest('[role="presentation"]') || el.getAttribute('role') === 'presentation';
                                    
                                    if (!isShareMenu && (text.includes('Sponsored') || text.includes('স্পনসরড') || 
                                        text.includes('Reels') || text.includes('রিলস') || 
                                        text.includes('Open app') || text.includes('অ্যাপে দেখুন'))) {
                                        if (el.offsetHeight < 1500) el.style.setProperty('display', 'none', 'important');
                                    }
                                });
                            }

                            var mediaElements = document.querySelectorAll('img:not([data-processed]), video:not([data-processed])');
                            mediaElements.forEach(function(media) {
                                var isLogo = media.src.indexOf('UYsfyMdMkZ_') !== -1 || media.closest('[aria-label="Facebook logo"]');
                                if (isLogo) {
                                    media.setAttribute('data-processed', 'true');
                                    return;
                                }
                                if (media.clientWidth > 0 && media.clientWidth < 50) return;

                                media.setAttribute('data-processed', 'true');
                                
                                media.style.setProperty('filter', 'contrast(0) brightness(0.2)', 'important');
                                media.style.setProperty('transition', 'filter 0.3s ease', 'important');
                                
                                var parent = media.parentNode;
                                if(parent) {
                                    if(window.getComputedStyle(parent).position === 'static') parent.style.position = 'relative';
                                    
                                    if(!parent.querySelector('.blur-view-btn')) {
                                        var btn = document.createElement('div');
                                        btn.className = 'blur-view-btn';
                                        btn.innerHTML = 'View Content';
                                        btn.style.cssText = 'position:absolute; top:50%; left:50%; transform:translate(-50%, -50%); ' +
                                                           'background:rgba(0,0,0,0.85); color:#fff; padding:10px 18px; ' +
                                                           'border-radius:25px; font-size:12px; font-weight:bold; cursor:pointer; ' +
                                                           'z-index:999999; pointer-events:auto;';
                                        
                                        parent.appendChild(btn);
                                        btn.onclick = function(e) {
                                            e.preventDefault();
                                            e.stopPropagation();
                                            media.style.setProperty('filter', 'none', 'important');
                                            btn.remove();
                                            if(media.tagName === 'VIDEO') media.play();
                                        };
                                    }
                                }
                            });
                        }

                        var debounceTimer;
                        var observer = new MutationObserver(function(mutations) {
                            clearTimeout(debounceTimer);
                            debounceTimer = setTimeout(function() {
                                processMediaAndAds();
                            }, 500); 
                        });
                        
                        observer.observe(document.body, { childList: true, subtree: true });
                        processMediaAndAds();
                    }
                    
                    setup();

                } catch(err) { console.error(err); }
            })()
        """.trimIndent()
        view?.evaluateJavascript(jsCode, null)
    }

    private fun showSessionSelector() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val savedDate = prefs.getString(KEY_LAST_USED_DATE, "")

        if (savedDate != todayDate) {
            dailyUsageSeconds = 0
            prefs.edit().putString(KEY_LAST_USED_DATE, todayDate).putInt(KEY_DAILY_USAGE_SECONDS, 0).apply()
        } else {
            dailyUsageSeconds = prefs.getInt(KEY_DAILY_USAGE_SECONDS, 0)
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_session_select, null)

        val tvDailyUsage = dialogView.findViewById<TextView>(R.id.tvDailyUsage)
        val usageMins = dailyUsageSeconds / 60
        if (usageMins > 0) {
            tvDailyUsage.text = "Today's Usage: $usageMins minutes"
        } else {
            tvDailyUsage.text = "Today's Usage: Less than a minute"
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()
        applyBlurToWindow(dialog.window, true)

        dialogView.findViewById<Button>(R.id.btn5Min).setOnClickListener { startSession(5, dialog) }
        dialogView.findViewById<Button>(R.id.btn10Min).setOnClickListener { startSession(10, dialog) }
        dialogView.findViewById<Button>(R.id.btn15Min).setOnClickListener { startSession(15, dialog) }
    }

    private fun startSession(minutes: Int, dialog: android.app.Dialog) {
        dialog.dismiss()
        timeLeftSeconds = minutes * 60
        isExtended = false
        runSessionTimer()
        loadFacebook()
    }

    private fun runSessionTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                if (timeLeftSeconds > 0) {
                    timeLeftSeconds--

                    dailyUsageSeconds++
                    if (dailyUsageSeconds % 10 == 0) {
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putInt(KEY_DAILY_USAGE_SECONDS, dailyUsageSeconds).apply()
                    }

                    val mins = timeLeftSeconds / 60
                    val secs = timeLeftSeconds % 60
                    cvCountdown.visibility = View.VISIBLE
                    tvCountdown.text = String.format("Left: %02d:%02d", mins, secs)
                    timerHandler.postDelayed(this, 1000)
                } else {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putInt(KEY_DAILY_USAGE_SECONDS, dailyUsageSeconds).apply()
                    onSessionTimeUp()
                }
            }
        }
        timerHandler.post(timerRunnable)
    }

    private fun onSessionTimeUp() {
        if (!isExtended) {
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("Time Awareness")
                .setMessage("The scheduled time is up!\n\n'Then you will surely be asked that Day about pleasure (তারপর সেদিন অবশ্যই তোমরা নিআমত সম্পর্কে জিজ্ঞাসিত হবে।)' (Al Quran 102:8)\n\nDo you want to extend it by 5 minutes for the last time?")
                .setCancelable(false)
                .setPositiveButton("Extend 5 Minutes") { _, _ ->
                    isExtended = true
                    timeLeftSeconds = 5 * 60
                    timerHandler.post(timerRunnable)
                }
                .setNegativeButton("Close") { _, _ -> blockApp() }
                .create()

            dialog.show()
            applyBlurToWindow(dialog.window, false)
        } else {
            blockApp()
        }
    }

    private fun blockApp() {
        webView.visibility = View.GONE
        cvCountdown.visibility = View.GONE
        blockLayout.visibility = View.VISIBLE
        tvBlockMessage.text = "'Surely the wasteful are ˹like˺ brothers to the devils (নিশ্চয় অপব্যয়কারীরা শয়তানের ভাই)' (Al Quran 17:27)\n\nPlease focus on productive work!"
        timerHandler.removeCallbacksAndMessages(null)
        warningHandler.removeCallbacksAndMessages(null)
    }

    private fun loadFacebook() {
        if (isNetworkAvailable()) {
            webView.visibility = View.VISIBLE
            errorLayout.visibility = View.GONE

            val intentData = intent?.dataString
            if (intentData != null && (intentData.contains("facebook.com") || intentData.contains("fb.com") || intentData.contains("fb.watch"))) {
                webView.loadUrl(intentData)
            } else {
                webView.loadUrl("https://m.facebook.com")
            }
        } else showErrorPage()
    }

    private fun showErrorPage() {
        webView.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun fetchWarningData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = URL(WARNING_JSON_URL).openConnection().inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                intervalMinutes = jsonObject.getInt("interval_minutes")
                val jsonArray = jsonObject.getJSONArray("data")
                val newList = mutableListOf<WarningData>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    newList.add(WarningData(item.getString("title"), item.getString("text")))
                }
                if (newList.isNotEmpty()) {
                    warningList.clear()
                    warningList.addAll(newList)
                }
                withContext(Dispatchers.Main) {
                    startWarningTimer()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun startWarningTimer() {
        warningRunnable = Runnable {
            showWarningDialog()
            warningHandler.postDelayed(warningRunnable, intervalMinutes * 60 * 1000L)
        }
        warningHandler.postDelayed(warningRunnable, intervalMinutes * 60 * 1000L)
    }

    private fun showWarningDialog() {
        if (isFinishing || warningList.isEmpty() || blockLayout.visibility == View.VISIBLE) return
        val randomItem = warningList.random()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_warning, null)
        dialogView.findViewById<TextView>(R.id.tvWarningTitle).text = randomItem.title
        dialogView.findViewById<TextView>(R.id.tvWarningMessage).text = randomItem.text
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()
        applyBlurToWindow(dialog.window, true)

        dialogView.findViewById<Button>(R.id.btnWarningOk).setOnClickListener { dialog.dismiss() }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = URL(UPDATE_JSON_URL).openConnection().inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)

                val newVersionCode = jsonObject.getInt("new_version_code")
                val newVersionName = jsonObject.getString("new_version_name")
                val updateUrl = jsonObject.getString("update_url")
                val forceUpdate = jsonObject.getBoolean("force_update")

                val featuresArray = jsonObject.getJSONArray("features")
                val featuresText = StringBuilder()
                for (i in 0 until featuresArray.length()) {
                    featuresText.append("• ").append(featuresArray.getString(i)).append("\n")
                }

                val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L)).longVersionCode.toInt()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionCode
                }

                if (newVersionCode > currentVersionCode) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(newVersionName, featuresText.toString(), updateUrl, forceUpdate)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showUpdateDialog(versionName: String, features: String, url: String, isForce: Boolean) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("New Update (v$versionName)")
            .setMessage("What's New:\n\n$features\nUpdate app to enjoy latest features!")
            .setCancelable(!isForce)
            .setPositiveButton("Update Now") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                if (isForce) finish()
            }

        if (!isForce) {
            dialog.setNegativeButton("Close") { d, _ -> d.dismiss() }
        }

        val alertDialog = dialog.create()
        alertDialog.show()
        applyBlurToWindow(alertDialog.window, false)
    }

    private fun applyBlurToWindow(window: Window?, isCustomView: Boolean) {
        window?.let { w ->
            if (isCustomView) {
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.70f)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes.blurBehindRadius = 60
                w.attributes = w.attributes
            }
        }
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DAILY_USAGE_SECONDS, dailyUsageSeconds).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacksAndMessages(null)
        warningHandler.removeCallbacksAndMessages(null)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DAILY_USAGE_SECONDS, dailyUsageSeconds).apply()
    }
}