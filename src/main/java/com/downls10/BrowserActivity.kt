package com.downls10

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Patterns
import android.util.AttributeSet
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.BaseAdapter
import android.text.Editable
import android.text.TextWatcher
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.Locale
import org.json.JSONObject
import kotlin.jvm.JvmOverloads
import kotlin.math.abs

private data class Tab(
    val id: Long,
    val webView: WebView,
    var isHome: Boolean = true,
    var title: String = "صفحة جديدة",
    var desktopMode: Boolean = false,
    // Translation stays enabled for this tab while the user browses.
    var translationEnabled: Boolean = false,
    var translationInProgress: Boolean = false,
    var lastTranslatedUrl: String = "",
    val backStack: MutableList<String> = mutableListOf(),
    val forwardStack: MutableList<String> = mutableListOf(),
    // --- حفظ واستعادة موضع التمرير عند الرجوع/التقدم ---
    /** آخر موضع تمرير (بالبكسل) لكل رابط زاره هذا التبويب */
    val scrollPositions: MutableMap<String, Int> = mutableMapOf(),
    /** true أثناء تحميل صفحة جديدة (لا نسجّل التمرير حينها لأن الصفحة تبدأ من الصفر) */
    var pageLoading: Boolean = false,
    /** الموضع المطلوب استعادته الآن، أو -1 إذا لا توجد استعادة جارية */
    var pendingRestoreY: Int = -1,
    /** رقم يزيد عند بدء/إلغاء كل استعادة لإيقاف المحاولات القديمة */
    var restoreGeneration: Int = 0
)

class BrowserActivity : Activity() {

    companion object {
        const val EXTRA_OPEN_URL = "extra_open_url"
        private const val SEARCH_PROVIDER_KEY = "searchProvider"
        private const val SEARCH_SEARXNG = "searxng"
        private const val SEARCH_GOOGLE = "google"
        private const val SEARCH_VIDEO = "video"
        private const val SEARXNG_SEARCH_BASE = "https://searx.ononoki.org/search?q="
        private const val SEARXNG_ENGINE_PREFIX = "!ddg !br !qw !yh "
        private val VIDEO_SEARCH_ENGINES = listOf(
            "Google Videos", "Bing Videos", "Yahoo Videos", "DuckDuckGo Videos",
            "Brave Search Videos", "Yandex Videos", "SearXNG Videos", "Mojeek",
            "YouTube", "Dailymotion", "Vimeo", "Internet Archive", "PeerTube",
            "Rumble", "Twitch", "Facebook Watch", "TikTok", "Instagram",
            "Reddit Videos", "Odysee", "Bilibili", "Metacafe"
        )
        private const val VIDEO_SEARCH_ENGINES_KEY = "videoSearchEngines"
        // بحث الفيديو يجمع النتائج المتاحة من محركات الفيديو المفعّلة في SearXNG.
    }

    private lateinit var webViewContainer: FrameLayout
    private lateinit var editUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnTabs: Button
    private var suggestionsPopup: PopupWindow? = null
    private var suggestionGeneration = 0
    private var suggestionItems: List<String> = emptyList()

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = 0

    private var hideMedia = false
    private var hideImages = false
    private var hideVideos = false
    private var nightMode = false
    private var mobileUA: String = ""
    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

    private var pendingStorageAction: (() -> Unit)? = null
    private val STORAGE_PERM_CODE = 4102
    private val ANDROID_PERM_CODE = 4103
    private val FILE_CHOOSER_CODE = 4104

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingBookmarkExportContent: String? = null
    private var lastConfirmedSafetyUrl: String? = null
    private val bgExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val PREFS = "browser_settings"
    private fun settingsPrefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun selectedSearchProvider(): String =
        settingsPrefs().getString(SEARCH_PROVIDER_KEY, SEARCH_GOOGLE) ?: SEARCH_GOOGLE

    private fun saveSearchProvider(provider: String) {
        settingsPrefs().edit().putString(SEARCH_PROVIDER_KEY, provider).apply()
    }

    private fun savedFontZoom(): Int =
        settingsPrefs().getInt("fontZoom", 100).coerceIn(50, 200)

    private fun saveFontZoom(value: Int) {
        settingsPrefs().edit().putInt("fontZoom", value.coerceIn(50, 200)).apply()
    }

    private fun cameraAllowed() = settingsPrefs().getBoolean("camera", false)
    private fun micAllowed() = settingsPrefs().getBoolean("mic", false)
    private fun locationAllowed() = settingsPrefs().getBoolean("location", false)

    private fun permissionStateText(label: String, enabled: Boolean): String =
        "$label — ${if (enabled) "مفعّل" else "معطّل"}"

    private var pendingAndroidPermissionCallback: ((Boolean) -> Unit)? = null
    private var pendingAndroidPermissionAnyOf: Boolean = false

    /** يطلب أذونات أندرويد المطلوبة ويتحقق من النتيجة قبل متابعة طلب الموقع. */
    private fun ensureAndroidPermissions(
        permissions: Array<String>,
        anyOf: Boolean = false,
        callback: (Boolean) -> Unit
    ) {
        val required = permissions.distinct()
        if (required.isEmpty()) {
            callback(true)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            callback(true)
            return
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            callback(true)
            return
        }
        if (pendingAndroidPermissionCallback != null) {
            callback(false)
            return
        }
        pendingAndroidPermissionCallback = callback
        pendingAndroidPermissionAnyOf = anyOf
        requestPermissions(missing.toTypedArray(), ANDROID_PERM_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ANDROID_PERM_CODE) return
        val callback = pendingAndroidPermissionCallback ?: return
        val anyOf = pendingAndroidPermissionAnyOf
        pendingAndroidPermissionCallback = null
        pendingAndroidPermissionAnyOf = false
        val grantedCount = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
        callback(if (anyOf) grantedCount > 0 else grantResults.isNotEmpty() && grantedCount == grantResults.size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        title = ""

        webViewContainer = findViewById(R.id.webViewContainer)
        editUrl = findViewById(R.id.editUrl)
        // لا تفتح لوحة المفاتيح عند تشغيل المتصفح. لا نطلبها إلا بعد لمسة صريحة على حقل العنوان.
        editUrl.clearFocus()
        findViewById<View>(android.R.id.content).apply {
            isFocusableInTouchMode = true
            requestFocus()
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editUrl.setOnClickListener {
            editUrl.requestFocus()
            editUrl.post {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(editUrl, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        progressBar = findViewById(R.id.progressBar)
        val btnBookmarkStar = findViewById<Button>(R.id.btnBookmarkStar)
        val btnForward = findViewById<Button>(R.id.btnForward)
        val btnDownloads = findViewById<Button>(R.id.btnDownloads)
        btnTabs = findViewById(R.id.btnTabs)
        val btnBrowserMenu = findViewById<Button>(R.id.btnBrowserMenu)
        val btnReload = findViewById<Button>(R.id.btnReload)

        CookieManager.getInstance().setAcceptCookie(true)
        DownloadsRepository.ensureLoaded(this)
        AdBlocker.cleanupRemovedLists(this)
        hideMedia = settingsPrefs().getBoolean("hideMedia", false)
        hideImages = settingsPrefs().getBoolean("hideImages", hideMedia)
        hideVideos = settingsPrefs().getBoolean("hideVideos", false)
        nightMode = settingsPrefs().getBoolean("nightMode", false)

        editUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                loadFromAddressBar()
                hideKeyboard()
                true
            } else false
        }

        btnBookmarkStar.setOnClickListener { addCurrentPageToBookmarks() }
        btnForward.setOnClickListener {
            val tab = currentTab()
            if (!tab.isHome && tab.webView.canGoForward()) {
                tab.webView.goForward()
            } else {
                Toast.makeText(this, "لا توجد صفحة للتقدّم إليها", Toast.LENGTH_SHORT).show()
            }
        }
        btnTabs.setOnClickListener { showTabsDialog() }
        btnDownloads.setOnClickListener { openDownLS10() }
        btnBrowserMenu.setOnClickListener { showBrowserMenu() }
        btnReload.setOnClickListener {
            currentTab().webView.reload()
        }
        setupGoogleSuggestions()

        if (savedInstanceState != null && savedInstanceState.getInt("tabCount", -1) >= 0) {
            restoreTabsFromInstanceState(savedInstanceState)
        } else {
            restoreTabsOrCreateHome()
        }
        handleOpenUrlIntent(intent)
    }

    /**
     * يحوّل حالة WebView إلى نص قابل للحفظ بشكل دائم في SharedPreferences.
     * saveState() يحفظ سجل الرجوع/التقدم داخل WebView، لذلك لا يكفي حفظ URL الحالي فقط.
     */
    private fun serializeWebViewState(webView: WebView): String? {
        return try {
            val state = Bundle()
            val history = webView.saveState(state) ?: return null
            if (history.size <= 0) return null

            val parcel = Parcel.obtain()
            try {
                state.writeToParcel(parcel, 0)
                Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** يعيد حالة WebView المحفوظة، بما فيها سجل الرجوع/التقدم. */
    private fun restoreSerializedWebViewState(webView: WebView, encoded: String?): Boolean {
        if (encoded.isNullOrBlank()) return false
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val state = Bundle.CREATOR.createFromParcel(parcel)
                state.classLoader = WebView::class.java.classLoader
                webView.restoreState(state) != null
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tabCount", tabs.size)
        outState.putInt("currentTabIndex", currentTabIndex)
        tabs.forEachIndexed { i, tab ->
            outState.putBoolean("tab_${i}_isHome", tab.isHome)
            outState.putBoolean("tab_${i}_desktop", tab.desktopMode)
            outState.putStringArrayList("tab_${i}_back", ArrayList(tab.backStack))
            outState.putStringArrayList("tab_${i}_forward", ArrayList(tab.forwardStack))
            if (!tab.isHome) {
                val webState = Bundle()
                tab.webView.saveState(webState)
                outState.putBundle("tab_${i}_webstate", webState)
            }
        }
        // مهم: onSaveInstanceState وحده لا يكفي لإعادة تشغيل التطبيق لاحقًا.
        persistTabs()
    }

    /** يستعيد التبويبات من حالة أندرويد المحفوظة (تستخدم ذاكرة WebView المؤقتة بدل تحميل الصفحة من الإنترنت من جديد) */
    private fun restoreTabsFromInstanceState(state: Bundle) {
        val count = state.getInt("tabCount", 0)
        for (i in 0 until count) {
            val isHome = state.getBoolean("tab_${i}_isHome", true)
            val desktop = state.getBoolean("tab_${i}_desktop", false)
            val back = state.getStringArrayList("tab_${i}_back") ?: arrayListOf()
            val forward = state.getStringArrayList("tab_${i}_forward") ?: arrayListOf()
            val webState = state.getBundle("tab_${i}_webstate")

            val webView = WebView(this)
            setupWebView(webView)
            val cleanedUA = buildChromeLikeUserAgent(webView.settings.userAgentString)
            webView.settings.userAgentString = if (desktop) desktopUA else cleanedUA
            if (mobileUA.isEmpty()) mobileUA = cleanedUA

            val tab = Tab(id = System.currentTimeMillis() + i, webView = webView, isHome = isHome, desktopMode = desktop)
            tab.backStack.addAll(back)
            tab.forwardStack.addAll(forward)
            tabs.add(tab)

            if (!isHome && webState != null) {
                webView.restoreState(webState)
            }
        }
        val idx = state.getInt("currentTabIndex", 0).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        if (tabs.isEmpty()) {
            createHomeTab()
        } else {
            switchToTab(idx)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenUrlIntent(intent)
    }

    private fun handleOpenUrlIntent(intent: Intent?) {
        if (intent == null) return

        // الروابط القادمة من التطبيقات الأخرى (ومنها ChatGPT) تصل عادةً كـ ACTION_VIEW
        // مع URI في data. نفتحها دائمًا في تبويب جديد بدل استبدال التبويب الحالي.
        val extraUrl = intent.getStringExtra(EXTRA_OPEN_URL)
        val dataUrl = intent.data?.toString()
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        val url = listOf(extraUrl, dataUrl, sharedText)
            .asSequence()
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .mapNotNull { value ->
                when {
                    value.startsWith("http://") || value.startsWith("https://") -> value
                    else -> null
                }
            }
            .firstOrNull()

        if (!url.isNullOrBlank()) {
            // كل رابط خارجي يفتح تبويبًا جديدًا، سواء وصل من ChatGPT أو WhatsApp أو Telegram
            // أو أي تطبيق آخر يدعم روابط الويب.
            createBrowsingTab(url)
        }
    }

    // ---------------- استعادة/حفظ التبويبات ----------------

    private fun restoreTabsOrCreateHome() {
        val (saved, currentIndex) = TabPersistence.load(this)
        if (saved.isEmpty()) {
            createHomeTab()
            return
        }
        saved.forEach { savedTab ->
            if (savedTab.isHome) {
                createTabInternal(isHome = true, url = null)
            } else {
                createTabInternal(
                    isHome = false,
                    url = savedTab.url,
                    restoredBack = savedTab.backStack,
                    restoredForward = savedTab.forwardStack,
                    restoredWebState = savedTab.webState
                )
            }
        }
        val idx = currentIndex.coerceIn(0, tabs.size - 1)
        switchToTab(idx)
    }

    private fun persistTabs() {
        val saved = tabs.map { tab ->
            SavedTab(
                tab.isHome,
                if (tab.isHome) "" else (tab.webView.url ?: ""),
                tab.title,
                tab.backStack.toList(),
                tab.forwardStack.toList(),
                if (tab.isHome) null else serializeWebViewState(tab.webView)
            )
        }
        TabPersistence.save(this, saved, currentTabIndex)
    }

    override fun onPause() {
        super.onPause()
        persistTabs()
        // Keep login/session cookies, but persist pending cookie changes when the app backgrounds.
        // Third-party cookies remain disabled per WebView below; first-party cookies stay enabled.
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // Cookie persistence failure must not crash the browser.
        }
    }

    // ---------------- إدارة التبويبات ----------------

    private fun createHomeTab() {
        createTabInternal(isHome = true, url = null)
        switchToTab(tabs.size - 1)
        persistTabs()
    }

    private fun createBrowsingTab(url: String) {
        createTabInternal(isHome = false, url = url)
        switchToTab(tabs.size - 1)
        persistTabs()
    }

    private fun createTabInternal(
        isHome: Boolean,
        url: String?,
        restoredBack: List<String> = emptyList(),
        restoredForward: List<String> = emptyList(),
        restoredWebState: String? = null
    ) {
        val webView = WebView(this)
        setupWebView(webView)
        val cleanedUA = buildChromeLikeUserAgent(webView.settings.userAgentString)
        webView.settings.userAgentString = cleanedUA
        if (mobileUA.isEmpty()) mobileUA = cleanedUA

        val tab = Tab(id = System.currentTimeMillis(), webView = webView, isHome = isHome)
        tab.backStack.addAll(restoredBack)
        tab.forwardStack.addAll(restoredForward)
        if (!isHome && !url.isNullOrBlank()) {
            tab.title = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
        }
        tabs.add(tab)

        if (!isHome) {
            // لا نعيد تحميل URL إذا كان لدينا WebView state محفوظ؛
            // restoreState يعيد سجل الرجوع/التقدم نفسه.
            val restored = restoreSerializedWebViewState(webView, restoredWebState)
            if (!restored && !url.isNullOrBlank()) {
                webView.loadUrl(url)
            }
        }
    }

    /** الرجوع والتقدم يعتمدان على سجل WebView الحقيقي مع استعادة موضع التمرير. */
    private fun goBackInTab(tab: Tab) {
        navigateHistory(tab, -1)
    }

    private fun goForwardInTab(tab: Tab) {
        navigateHistory(tab, 1)
    }

    // ---------------- حفظ واستعادة موضع التمرير (Scroll Position Restoration) ----------------

    private val blobBridge by lazy { BlobDownloadBridge(this) }

    private val SCROLL_RESTORE_INTERVAL_MS = 150L
    private val SCROLL_RESTORE_MAX_TRIES = 60          // ≈ 9 ثوانٍ كحد أقصى
    private val MAX_SAVED_SCROLL_ENTRIES = 100

    private fun rememberScroll(tab: Tab, url: String, y: Int) {
        if (url.isBlank() || url.startsWith("about:")) return
        tab.scrollPositions.remove(url)          // ليصبح الأحدث في آخر القائمة
        tab.scrollPositions[url] = y
        while (tab.scrollPositions.size > MAX_SAVED_SCROLL_ENTRIES) {
            val oldest = tab.scrollPositions.keys.firstOrNull() ?: break
            tab.scrollPositions.remove(oldest)
        }
    }

    /**
     * ينتقل في سجل التبويب خطوة للخلف (steps = -1) أو للأمام (steps = 1)،
     * ويعيد المستخدم إلى نفس مكان التمرير الذي كان فيه في تلك الصفحة.
     * يرجع false إذا لا توجد صفحة سابقة/تالية.
     */
    private fun navigateHistory(tab: Tab, steps: Int): Boolean {
        val wv = tab.webView
        val list = wv.copyBackForwardList()
        val targetIndex = list.currentIndex + steps
        if (targetIndex < 0 || targetIndex >= list.size) return false
        val targetUrl = list.getItemAtIndex(targetIndex)?.url

        // احفظ موضع الصفحة التي نغادرها (احتياطًا، المستمع يحفظه أصلًا أثناء التمرير)
        val leavingUrl = wv.url
        if (leavingUrl != null && !tab.pageLoading && tab.pendingRestoreY < 0) {
            rememberScroll(tab, leavingUrl, wv.scrollY)
        }

        cancelScrollRestore(tab)
        val savedY = targetUrl?.let { tab.scrollPositions[it] } ?: 0

        // عند الرجوع نفضّل النسخة المخزّنة مؤقتًا لتظهر نفس نتائج البحث التي رآها المستخدم
        wv.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        wv.goBackOrForward(steps)

        if (savedY > 0) startScrollRestore(tab, targetIndex, savedY)
        return true
    }

    private fun cancelScrollRestore(tab: Tab) {
        tab.restoreGeneration++
        tab.pendingRestoreY = -1
    }

    private fun finishScrollRestore(tab: Tab) {
        tab.pendingRestoreY = -1
        try { tab.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT } catch (e: Exception) { }
    }

    /**
     * يحاول كل 150ms تمرير الصفحة إلى الموضع المحفوظ حتى ينجح.
     * إذا كانت الصفحة أقصر من الموضع المطلوب (المحتوى لم يكتمل أو يُحمَّل بالتمرير اللانهائي مثل جوجل)،
     * يبقى التمرير عند آخر الصفحة المتاحة فيُحمَّل المزيد من المحتوى، ثم يكمل المحاولة.
     * تتوقف المحاولات إذا لمس المستخدم الشاشة.
     */
    private fun startScrollRestore(tab: Tab, targetIndex: Int, targetY: Int) {
        val generation = ++tab.restoreGeneration
        tab.pendingRestoreY = targetY
        var tries = 0
        val step = object : Runnable {
            override fun run() {
                if (tab.restoreGeneration != generation) return       // أُلغيت أو استُبدلت باستعادة أحدث
                if (!tabs.contains(tab)) return                        // التبويب أُغلق
                val wv = tab.webView
                tries++
                // لا نحرّك الصفحة القديمة: ننتظر حتى يصبح موضع السجل هو الصفحة الهدف
                val onTarget = try { wv.copyBackForwardList().currentIndex == targetIndex } catch (e: Exception) { false }
                if (onTarget) {
                    wv.scrollTo(wv.scrollX, targetY)
                    if (abs(wv.scrollY - targetY) <= 2 && !tab.pageLoading) {
                        finishScrollRestore(tab)
                        return
                    }
                }
                if (tries >= SCROLL_RESTORE_MAX_TRIES) {
                    finishScrollRestore(tab)
                    return
                }
                mainHandler.postDelayed(this, SCROLL_RESTORE_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(step, SCROLL_RESTORE_INTERVAL_MS)
    }

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        webViewContainer.removeAllViews()
        currentTabIndex = index
        val tab = tabs[index]

        if (tab.isHome) {
            editUrl.setText("")
            progressBar.visibility = View.GONE
            webViewContainer.addView(buildSpeedDialView())
        } else {
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            webViewContainer.addView(
                tab.webView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            editUrl.setText(tab.webView.url ?: "")
        }
        updateTabCountUI()
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs[index]
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            createHomeTab()
        } else {
            val newIndex = if (currentTabIndex >= tabs.size) tabs.size - 1 else currentTabIndex
            switchToTab(newIndex)
        }
        persistTabs()
    }

    private fun closeAllTabs() {
        webViewContainer.removeAllViews()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        createHomeTab()
    }

    private fun updateTabCountUI() {
        btnTabs.text = tabs.size.toString()
    }

    private fun currentTab(): Tab = tabs[currentTabIndex]
    private fun currentWebView(): WebView = tabs[currentTabIndex].webView

    /** يزيل علامة "wv" (WebView) من هوية المتصفح الافتراضية لتقليل رفض بعض المواقع/إظهار تحقق زائد */
    private fun buildChromeLikeUserAgent(defaultUA: String): String {
        return defaultUA
            .replace("; wv)", ")")
            .replace(" wv;", "")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
        editUrl.clearFocus()
    }

    // ---------------- صفحة البداية (Speed Dial) ----------------

    private fun buildSpeedDialView(): View {
        val root = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }

        val columns = 4
        val horizontalPadding = 16
        val screenWidth = resources.displayMetrics.widthPixels
        val cellWidth = (screenWidth - horizontalPadding * 2) / columns
        val iconSize = (cellWidth * 0.68f).toInt()

        val grid = GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(horizontalPadding, 48, horizontalPadding, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val items = SpeedDialStorage.getItems(this)
        items.forEach { item ->
            grid.addView(buildSpeedDialTile(title = item.title, item = item, cellWidth = cellWidth, iconSize = iconSize) {
                createBrowsingTab(item.url)
            })
        }

        // زر + إضافة
        val addTile = buildAddTile(cellWidth = cellWidth, iconSize = iconSize) { showAddSpeedDialDialog() }
        grid.addView(addTile)

        root.addView(grid)
        return root
    }

    private fun buildAddTile(cellWidth: Int, iconSize: Int, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellWidth
                setMargins(4, 12, 4, 12)
            }
            setOnClickListener { onClick() }
        }
        val plusBox = TextView(this).apply {
            text = "+"
            setTextColor(Color.WHITE)
            textSize = (iconSize / 22f).coerceAtLeast(20f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setBackgroundColor(Color.parseColor("#222222"))
        }
        val label = TextView(this).apply {
            text = "إضافة"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        container.addView(plusBox)
        container.addView(label)
        return container
    }

    private fun buildSpeedDialTile(title: String, item: SpeedDialItem, cellWidth: Int, iconSize: Int, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellWidth
                setMargins(4, 12, 4, 12)
            }
            setOnClickListener { onClick() }
            setOnLongClickListener {
                showSpeedDialItemMenu(item)
                true
            }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            tag = item.url
        }
        loadFaviconInto(icon, item.url)

        val label = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#808080"))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        container.addView(icon)
        container.addView(label)
        return container
    }

    private fun faviconCacheFile(host: String): File {
        val safe = Base64.encodeToString(
            host.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val dir = File(filesDir, "speeddial_favicons")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, safe + ".png")
    }

    private fun loadFaviconInto(imageView: ImageView, pageUrl: String) {
        val host = try { Uri.parse(pageUrl).host?.lowercase(Locale.US) } catch (e: Exception) { null } ?: return
        val cacheFile = faviconCacheFile(host)

        // استخدم الصورة المحفوظة أولاً حتى لا تتحول مربعات المواقع إلى أسود عند كل تشغيل.
        bgExecutor.execute {
            var bitmap: Bitmap? = null
            if (cacheFile.exists()) {
                bitmap = runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
            }

            if (bitmap == null) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("https://www.google.com/s2/favicons?sz=128&domain=$host")
                    connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    bitmap = BitmapFactory.decodeStream(connection.inputStream)

                    bitmap?.let { downloaded ->
                        runCatching {
                            cacheFile.outputStream().use { out ->
                                downloaded.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                        }
                    }
                } catch (e: Exception) {
                    bitmap = null
                } finally {
                    connection?.disconnect()
                }
            }

            val result = bitmap
            mainHandler.post {
                if (result != null && imageView.tag == pageUrl) {
                    imageView.setImageBitmap(result)
                }
            }
        }
    }
    private fun showAddSpeedDialDialog() {
        val input = EditText(this).apply {
            hint = "الصق الرابط هنا"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("إضافة موقع")
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                val host = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
                SpeedDialStorage.addItem(this, host, url)
                switchToTab(currentTabIndex)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSpeedDialItemMenu(item: SpeedDialItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("إعادة تسمية", "حذف")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(item)
                    1 -> {
                        SpeedDialStorage.removeItem(this, item.id)
                        switchToTab(currentTabIndex)
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(item: SpeedDialItem) {
        val input = EditText(this).apply {
            setText(item.title)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle("إعادة تسمية")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                SpeedDialStorage.renameItem(this, item.id, input.text.toString().trim())
                switchToTab(currentTabIndex)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------- إعداد WebView ----------------

    private fun setupWebView(webView: WebView) {
        registerForContextMenu(webView)

        // تتبّع آخر نقطة لمس لاكتشاف الصور المصغّرة وملصقات الفيديو والصور
        // المعروضة بخلفية CSS، حتى لو لم يتعرف عليها HitTestResult كصورة.
        var lastTouchX = 0f
        var lastTouchY = 0f

        // اعتراض كل ضغطة مطوّلة داخل الصفحة؛ لا نسمح لـ WebView بتنفيذ
        // النسخ الافتراضي للصورة. إذا لم يتعرف عليها النظام، نفحص عنصر الصفحة عبر JavaScript.
        webView.setOnLongClickListener {
            val hitTest = webView.hitTestResult
            val knownImageType =
                hitTest.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTest.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE

            val knownUrl = hitTest.extra
            if (knownImageType && !knownUrl.isNullOrBlank() &&
                (knownUrl.startsWith("http://") || knownUrl.startsWith("https://"))) {
                showImageContextMenu(knownUrl, hitTest.type)
            } else {
                val density = resources.displayMetrics.density
                val x = lastTouchX / density
                val y = lastTouchY / density
                val script = """
                    (function() {
                      var e = document.elementFromPoint($x, $y);
                      if (!e) return '';
                      var n = e;
                      for (var i = 0; n && i < 6; i++, n = n.parentElement) {
                        if (n.tagName === 'IMG' && (n.currentSrc || n.src)) return n.currentSrc || n.src;
                        if (n.tagName === 'VIDEO' && n.poster) return n.poster;
                        if (n.tagName === 'SOURCE' && n.src) return n.src;
                        var bg = getComputedStyle(n).backgroundImage;
                        if (bg && bg !== 'none') {
                          var m = bg.match(/url\(["']?(.*?)["']?\)/);
                          if (m && m[1]) return m[1];
                        }
                      }
                      var v = e.closest ? e.closest('video') : null;
                      if (v && v.poster) return v.poster;
                      var a = e.closest ? e.closest('a') : null;
                      if (a) {
                        var img = a.querySelector('img');
                        if (img) return img.currentSrc || img.src;
                      }
                      return '';
                    })();
                """.trimIndent()
                webView.evaluateJavascript(script) { result ->
                    val imageUrl = try {
                        org.json.JSONTokener(result ?: "\"\"").nextValue() as? String
                    } catch (_: Exception) { null }
                    if (!imageUrl.isNullOrBlank() &&
                        (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
                        showImageContextMenu(imageUrl, WebView.HitTestResult.IMAGE_TYPE)
                    }
                }
            }
            // مهم: true دائمًا لمنع قائمة WebView الافتراضية ونسخ الصورة تلقائيًا.
            true
        }

        if (nightMode) webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val settings = webView.settings
        settings.textZoom = savedFontZoom()
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // النوافذ الجديدة تصل إلى onCreateWindow (مع نافذة التأكيد) فقط عند ضغط المستخدم؛
        // ما تفتحه الصفحة من نفسها بدون ضغط يبقى محجوبًا.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        // Require an explicit user gesture before a site can autoplay media.
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Never allow file-origin pages to reach other local files or network origins.
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
        // Prevent remote websites from silently receiving third-party tracking cookies.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        // Keep WebView remote debugging disabled in user-facing builds.
        WebView.setWebContentsDebuggingEnabled(false)
        settings.blockNetworkImage = hideImages
        settings.loadsImagesAutomatically = !hideImages

        // Third-party cookies are disabled above; first-party login cookies remain supported.

        // تسجيل موضع التمرير أثناء التصفح لكل رابط (يُستخدم عند الرجوع)
        webView.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val wv = v as? WebView ?: return@setOnScrollChangeListener
            val tab = tabs.find { it.webView == wv } ?: return@setOnScrollChangeListener
            if (tab.pageLoading || tab.pendingRestoreY >= 0) return@setOnScrollChangeListener
            val url = wv.url ?: return@setOnScrollChangeListener
            rememberScroll(tab, url, scrollY)
        }

        // إذا لمس المستخدم الشاشة أثناء الاستعادة نوقفها حتى لا نتحكم بتمريره
        webView.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_MOVE) {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                tabs.find { it.webView == v }?.let { t ->
                    if (t.pendingRestoreY >= 0) {
                        cancelScrollRestore(t)
                        try { t.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT } catch (e: Exception) { }
                    }
                }
            }
            false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {
                // Never allow a page with an invalid/expired certificate to continue.
                handler.cancel()
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            override fun onSafeBrowsingHit(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                threatType: Int,
                callback: android.webkit.SafeBrowsingResponse
            ) {
                // Never allow a URL identified by Android Safe Browsing to load.
                callback.backToSafety(true)
                Toast.makeText(
                    this@BrowserActivity,
                    "تم حظر هذا الرابط لأن التصفح الآمن صنّفه خطرًا.",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                AdBlocker.maybeBlock(this@BrowserActivity, request)?.let { return it }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // تجاهل صفحة التحويل download.htm فقط؛ لا تعرضها كأنها ملف قابل للتنزيل.
                // تبقى نافذة التأكيد كما هي عند اكتشاف رابط ملف حقيقي.
                if (request.isForMainFrame) {
                    val path = request.url.path?.lowercase(Locale.US).orEmpty()
                    val lastSegment = path.substringAfterLast('/')
                    if (lastSegment == "download.htm" || lastSegment == "download.html") {
                        return false
                    }
                }
                // لا تعترض روابط صفحات الويب لمجرد احتوائها على كلمة download أو
                // امتداد/مسار ملتبس؛ اترك التنقل الطبيعي يعمل. التنزيلات الحقيقية
                // تظل تمر عبر setDownloadListener وتعرض نافذة اسم الملف والحجم والامتداد.
                if (request.isForMainFrame) {
                    val known = DownloadsRepository.downloadList.find { it.url == url }
                    if (known != null) {
                        // رابط تنزيل معروف: كان يُتجاهل بصمت، الآن تظهر رسالة «الملف موجود: الاسم» أو يُستأنف التنزيل
                        confirmDownload(
                            url,
                            suggestedFileName = known.fileName,
                            userAgent = view.settings.userAgentString,
                            referer = view.url
                        )
                        return true
                    }
                }
                if (request.isForMainFrame) {
                    navigateWithSafetyCheck(view, url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tabs.find { it.webView == view }?.pageLoading = true
                // سجّل الموقع فور بدء فتحه، لا تنتظر onPageFinished؛
                // بهذا تبقى نتيجة البحث بنفسجية حتى لو تأخر الموقع أو لم يكتمل تحميله.
                recordVisitedNavigation(url)
                // نحفظ الحالة فور بدء التنقل أيضًا حتى لا نفقد آخر خطوة إذا أُغلق التطبيق
                // قبل onPageFinished.
                persistTabs()
                if (nightMode) { applyNightModeJs(view); applySearxngNightModeJs(view) }
                if (isActiveTab(view)) editUrl.setText(url)
            }

            // أول ظهور لمحتوى الصفحة الجديدة: أسرع وقت لتطبيق الوضع الليلي
            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                if (nightMode) {
                    applyNightModeJs(view)
                    applySearxngNightModeJs(view)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (ensureGoogleSearchPageSize(view)) return
                if (isGoogleSearchResultsPage(url)) {
                    injectGooglePaginationJs(view)
                    mainHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && isGoogleSearchResultsPage(view.url)) {
                            injectGooglePaginationJs(view)
                        }
                    }, 900L)
                }
                tabs.find { it.webView == view }?.let { t ->
                    t.pageLoading = false
                    // صفحة جديدة عادية (بدون استعادة جارية): سجّل موضعها الحالي
                    if (t.pendingRestoreY < 0) {
                        view.url?.let { u -> rememberScroll(t, u, view.scrollY) }
                        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    }
                }
                if (url != null) {
                    recordVisitedNavigation(url)
                    BrowserStorage.addHistory(this@BrowserActivity, view.title ?: url, url)
                    applyVisitedSearchResultColors(view)
                }
                val resolvedTitle = view.title?.takeIf { it.isNotBlank() }
                    ?: url?.takeIf { it.isNotBlank() }
                    ?: "صفحة جديدة"
                tabs.find { it.webView == view }?.title = resolvedTitle
                if (nightMode) {
                    applyNightModeJs(view)
                    applySearxngNightModeJs(view)
                    mainHandler.postDelayed({ if (nightMode) { applyNightModeJs(view); applySearxngNightModeJs(view) } }, 500)
                    // نحفظ حكم الصفحة (داكنة/فاتحة) لتُعالج أسرع في الزيارات القادمة
                    mainHandler.postDelayed({ if (nightMode) recordNightResult(view) }, 2200)
                }
                val tabForView = tabs.find { it.webView == view }
                if (tabForView?.desktopMode == true) applyDesktopViewportJs(view)
                if (isActiveTab(view)) {
                    if (hideImages || hideVideos) applyHideMediaJs(view)
                }

                if (isSearchResultsPage(url)) {
                    // الحالة مبنية على سجل دائم، لذلك تبقى الألوان بعد الرجوع وإعادة فتح التطبيق.
                    applyVisitedSearchResultColors(view)
                    filterSearxngMicrosoftResults(view)
                    mainHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && isSearchResultsPage(view.url)) {
                            applyVisitedSearchResultColors(view)
                            filterSearxngMicrosoftResults(view)
                        }
                    }, 700L)
                }

                // Keep translating every new page opened inside this translated tab.
                if (tabForView?.translationEnabled == true) {
                    mainHandler.postDelayed({
                        translateTabPageIfNeeded(view, tabForView)
                    }, 500L)
                }

                persistTabs()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (nightMode && newProgress in 15..95 && System.currentTimeMillis() - lastNightInject > 250) {
                    applyNightModeJs(view)
                    applySearxngNightModeJs(view)
                }
                if (isActiveTab(view)) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                // نرد على الصفحة فورًا بـ WebView مؤقت يلتقط رابط النافذة فقط.
                // الاحتفاظ برسالة النافذة حتى يضغط المستخدم «سماح» كان يوقف التطبيق
                // (المتصفح يُلغي النافذة المعلّقة إذا طلبت الصفحة نافذة أخرى أو طال الانتظار).
                val temp = WebView(this@BrowserActivity)
                // This temporary WebView only observes popup URLs; it must never run
                // page JavaScript or access local files/content while capturing them.
                temp.settings.apply {
                    javaScriptEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(temp, false)
                var finished = false

                fun dispose() {
                    if (finished) return
                    finished = true
                    // لا نهدم الـ WebView داخل ردّه نفسه: ننفّذ ذلك بعد انتهاء الاستدعاء الحالي
                    mainHandler.post {
                        try { temp.stopLoading() } catch (e: Exception) { }
                        try { temp.destroy() } catch (e: Exception) { }
                    }
                }

                fun capture(rawUrl: String?) {
                    if (finished) return
                    val u = rawUrl?.trim().orEmpty()
                    val lower = u.lowercase()
                    if (u.isEmpty() || lower.startsWith("about:") || lower.startsWith("javascript:") ||
                        lower.startsWith("data:") || lower.startsWith("blob:")) return
                    dispose()
                    showPopupConfirmDialog(u)
                }

                temp.webViewClient = object : WebViewClient() {
                    // لا نحمّل شيئًا من الشبكة داخل الـ WebView المؤقت
                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        capture(request.url.toString())
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        capture(url)
                    }
                }

                transport.webView = temp
                resultMsg.sendToTarget()

                // إن لم يصل أي رابط (نافذة فارغة) نتخلص من الـ WebView المؤقت
                mainHandler.postDelayed({ dispose() }, 8000)
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // Use Android's system document picker: grant access only to the file
                // the user selects, instead of requesting broad access to all storage.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_CODE)
                } catch (e: Exception) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Camera/microphone access is never granted to an insecure HTTP origin.
                if (!request.origin.scheme.equals("https", ignoreCase = true) ||
                    request.origin.host.isNullOrBlank()) {
                    request.deny()
                    return
                }
                // نمنح فقط ما هو مسموح في إعدادات المتصفح، ونرفض الباقي
                val granted = request.resources.filter { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> cameraAllowed()
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> micAllowed()
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true   // مطلوب لتشغيل فيديوهات DRM
                        else -> false
                    }
                }
                val deniedByUser = request.resources.any { res ->
                    (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE && !cameraAllowed()) ||
                        (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE && !micAllowed())
                }
                if (granted.isEmpty() || deniedByUser) {
                    request.deny()
                    return
                }

                val androidPerms = mutableListOf<String>()
                if (granted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) androidPerms.add(Manifest.permission.CAMERA)
                if (granted.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) androidPerms.add(Manifest.permission.RECORD_AUDIO)

                runOnUiThread {
                    ensureAndroidPermissions(androidPerms.toTypedArray()) { ok ->
                        if (ok) request.grant(granted.toTypedArray()) else request.deny()
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                val originUri = try { Uri.parse(origin) } catch (_: Exception) { Uri.EMPTY }
                if (!locationAllowed() ||
                    !originUri.scheme.equals("https", ignoreCase = true) ||
                    originUri.host.isNullOrBlank()) {
                    callback.invoke(origin, false, false)
                    return
                }
                ensureAndroidPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    anyOf = true
                ) { ok -> callback.invoke(origin, ok, false) }
            }
        }

        // The blob-download bridge is attached only during an active blob transfer.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val nameFromHeader = DownloadsRepository.fileNameFromContentDispositionOrNull(contentDisposition)
            val parsedUrl = try { Uri.parse(url) } catch (_: Exception) { Uri.EMPTY }
            val path = parsedUrl.path.orEmpty().lowercase(Locale.US)
            val extension = path.substringAfterLast('/', "").substringAfterLast('.', "")
            val responseMime = mimeType?.substringBefore(';')?.trim().orEmpty()
            val isHtmlPage = extension in setOf("html", "htm") ||
                responseMime.equals("text/html", ignoreCase = true)
            // تجاهل تنزيل صفحات HTML غير المرغوبة مثل download.htm حتى لو أرسل الموقع
            // اسم ملف في Content-Disposition؛ مع الإبقاء على حوار تنزيل الملفات الحقيقية.
            // ملفات blob تبقى ضمن مسار التنزيل المعتاد.
            if (isHtmlPage && !url.startsWith("blob:", ignoreCase = true)) {
                return@setDownloadListener
            }
            if (url.startsWith("blob:", ignoreCase = true)) {
                blobBridge.start(webView, url, nameFromHeader, mimeType)
            } else {
                confirmDownload(
                    url,
                    suggestedFileName = nameFromHeader,
                    userAgent = userAgent,
                    referer = webView.url,
                    mimeType = mimeType
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_CODE) {
            val results: Array<Uri>? = if (resultCode == Activity.RESULT_OK && data != null) {
                val selected = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) selected.add(clip.getItemAt(index).uri)
                }
                data.data?.let { if (it !in selected) selected.add(it) }
                selected.distinct().takeIf { it.isNotEmpty() }?.toTypedArray()
            } else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }

        if (requestCode == BookmarksFileManager.EXPORT_REQUEST_CODE) {
            val content = pendingBookmarkExportContent
            pendingBookmarkExportContent = null
            if (resultCode == Activity.RESULT_OK && data?.data != null && content != null) {
                val result = BookmarksFileManager.writeExport(this, data.data!!, content)
                Toast.makeText(this, if (result.first) "تم تصدير العلامات المرجعية" else "فشل التصدير: ${result.second}", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (requestCode == BookmarksFileManager.IMPORT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val result = BookmarksFileManager.readImport(this, data.data!!)
                val html = result.first
                if (html == null) {
                    Toast.makeText(this, "فشل الاستيراد: ${result.second}", Toast.LENGTH_LONG).show()
                } else {
                    val parsed = BrowserStorage.parseBookmarksHtml(html)
                    if (parsed.isEmpty()) {
                        Toast.makeText(this, "لم يتم العثور على علامات مرجعية في الملف", Toast.LENGTH_LONG).show()
                    } else {
                        val added = BrowserStorage.importBookmarks(this, parsed)
                        Toast.makeText(this, "تم استيراد $added علامة مرجعية جديدة", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun isActiveTab(view: WebView): Boolean =
        tabs.isNotEmpty() && currentTabIndex < tabs.size && tabs[currentTabIndex].webView == view

    // ---------------- التنقل + فحص الأمان ----------------

    /** يسجّل الرابط كمزار. روابط جوجل الوسيطة (/url?q=...) تُفك ليُسجَّل الموقع الحقيقي. */
    private fun isVisitedSite(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
        return VisitedSites.isVisited(this, host)
    }

    private fun visitedSiteColor(url: String?, unvisitedColor: Int = Color.CYAN): Int {
        return if (isVisitedSite(url)) Color.parseColor("#808080") else unvisitedColor
    }

    /**
     * يجبر صفحة SearXNG المستخدمة لبحث الويب والفيديو على ألوان داكنة واضحة.
     * بعض نسخ SearXNG لا تتبع تفضيل الوضع الليلي في WebView، لذلك لا نعتمد على
     * خوارزمية اكتشاف ألوان الصفحة وحدها.
     */
    /**
     * يفرض الألوان الداكنة على نتائج بحث Google فقط.
     * لا يغيّر SearXNG أو بحث الفيديو أو الوضع الليلي داخل المواقع.
     */
    private fun applySearxngNightModeJs(webView: WebView) {
        if (!nightMode || !isGoogleSearchResultsPage(webView.url)) return
        val js = """
            (function() {
              try {
                if (!(location.hostname.toLowerCase() === 'google.com' ||
                      location.hostname.toLowerCase().startsWith('google.') ||
                      location.hostname.toLowerCase().endsWith('.google.com') ||
                      location.hostname.toLowerCase().endsWith('.googleusercontent.com'))) return;
                var id = '__downls10_google_search_dark__';
                var css = [
                  'html,html body,body{background:#000!important;background-color:#000!important;color:#fff!important;color-scheme:dark!important;}',
                  'body,body div,body section,body main,body article,body aside,body header,body footer,body nav,body form,body fieldset,body table,body tr,body td,body th,body ul,body ol,body li,body p,body span,body small,body label,body time,body cite,body em,body strong,body b,body h1,body h2,body h3,body h4,body h5,body h6,body [class],body [id]{color:#fff!important;background-color:#000!important;border-color:#444!important;}',
                  'a,a:visited,a *,a:visited *,h1 a,h2 a,h3 a,h4 a,[role="link"]{color:#fff!important;}',
                  'input,textarea,select,button,[role="button"],[role="combobox"]{background:#111!important;background-color:#111!important;color:#fff!important;border-color:#666!important;caret-color:#fff!important;}',
                  'input::placeholder,textarea::placeholder{color:#ccc!important;opacity:1!important;}',
                  '#searchform,#tsf,#APjFqb,#gb,#hdtb,#hdtbMenus,#appbar,#rso,#search,#center_col,#res,#main,#content,main,.main-content,.search-results,.search-results-container,.results-container,.results-list{background:#000!important;background-color:#000!important;color:#fff!important;}',
                  '.MjjYud,.g,.tF2Cxc,.Gx5Zad,.xpd,.SoaBEf{background:#000!important;background-color:#000!important;color:#fff!important;border-color:#444!important;box-shadow:none!important;}',
                  '#rso h3,#search h3,#rso .LC20lb,#rso .DKV0Md,#rso .VwiC3b,#rso .IsZvec,#rso cite,#rso .qLRx3b{color:#fff!important;}',
                  'hr,[role="separator"],body *{border-color:#444!important;scrollbar-color:#555 #000!important;}',
                  'img,video,svg,canvas,iframe{filter:none!important;}',
                  '::placeholder{color:#ccc!important;}::selection{background:#444!important;color:#fff!important;}',
                  '::-webkit-scrollbar{width:8px;height:8px;background:#000!important;}::-webkit-scrollbar-thumb{background:#555!important;border-radius:4px;}::-webkit-scrollbar-track{background:#000!important;}'
                ].join('');
                function apply() {
                  if (!location.hostname.toLowerCase().includes('google.')) return;
                  var style = document.getElementById(id);
                  if (!style) { style = document.createElement('style'); style.id = id; (document.head || document.documentElement).appendChild(style); }
                  if (style.textContent !== css) style.textContent = css;
                  document.documentElement.style.setProperty('background-color', '#000', 'important');
                  document.documentElement.style.setProperty('color-scheme', 'dark', 'important');
                  if (document.body) {
                    document.body.style.setProperty('background-color', '#000', 'important');
                    document.body.style.setProperty('color', '#fff', 'important');
                  }
                }
                apply();
                if (!window.__downls10GoogleDarkObserver && document.documentElement) {
                  window.__downls10GoogleDarkObserver = new MutationObserver(function(){ apply(); });
                  window.__downls10GoogleDarkObserver.observe(document.documentElement, {childList:true,subtree:true,attributes:true,attributeFilter:['class','style','data-theme']});
                }
                if (!window.__downls10GoogleDarkTimer) {
                  window.__downls10GoogleDarkTimer = setInterval(apply, 900);
                }
              } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun isGoogleSearchResultsPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase(Locale.US) ?: return false
        val googleHost = host == "google.com" || host.startsWith("google.") ||
            host.endsWith(".google.com") || host.endsWith(".googleusercontent.com")
        return googleHost && (uri.getQueryParameter("q") != null || uri.path?.contains("/search") == true)
    }

    /**
     * اطلب 20 نتيجة في صفحة Google. لا يغيّر أي محرك بحث أو صفحة موقع أخرى.
     */
    private fun ensureGoogleSearchPageSize(webView: WebView): Boolean {
        val current = webView.url ?: return false
        if (!isGoogleSearchResultsPage(current)) return false
        return try {
            val uri = Uri.parse(current)
            if (uri.getQueryParameter("num") == "20") return false
            val builder = uri.buildUpon().clearQuery()
            for (name in uri.queryParameterNames) {
                if (name != "num") {
                    for (value in uri.getQueryParameters(name)) {
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
            builder.appendQueryParameter("num", "20")
            val target = builder.build().toString()
            if (target != current) {
                webView.loadUrl(target)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * يستبدل زر "المزيد من نتائج البحث" في Google بزر صفحة تالية،
     * وينتقل إلى start + 20 مع إبقاء عبارة البحث وبقية خياراتها.
     */
    private fun injectGooglePaginationJs(webView: WebView) {
        if (!nightMode && !isGoogleSearchResultsPage(webView.url)) {
            // الترقيم ميزة لنتائج Google فقط، مستقلة عن الوضع الليلي.
        }
        val js = """
            (function() {
              try {
                if (!(/(^|\\.)google\\./i.test(location.hostname) || location.hostname.toLowerCase().endsWith('.google.com'))) return;
                var params = new URLSearchParams(location.search);
                if (!params.has('q')) return;
                var currentStart = parseInt(params.get('start') || '0', 10);
                if (!isFinite(currentStart) || currentStart < 0) currentStart = 0;
                var nextUrl = new URL(location.href);
                nextUrl.searchParams.set('num', '20');
                nextUrl.searchParams.set('start', String(currentStart + 20));

                var labels = ['المزيد من نتائج البحث', 'More results', 'عرض المزيد من النتائج', 'المزيد'];
                document.querySelectorAll('a,button,[role="button"]').forEach(function(el) {
                  var label = (el.innerText || el.getAttribute('aria-label') || '').trim();
                  if (labels.some(function(x) { return label.toLowerCase() === x.toLowerCase(); })) {
                    el.style.setProperty('display', 'none', 'important');
                  }
                });

                var old = document.getElementById('__downls10_google_next_page');
                if (!old) {
                  old = document.createElement('a');
                  old.id = '__downls10_google_next_page';
                  old.textContent = 'الصفحة التالية';
                  old.style.cssText = 'display:block;box-sizing:border-box;margin:28px auto 36px;padding:15px 26px;width:calc(100% - 24px);max-width:320px;text-align:center;border:1px solid #555;border-radius:14px;background:#202124;color:#fff!important;font-size:17px;font-weight:600;text-decoration:none;direction:rtl;box-shadow:0 3px 10px rgba(0,0,0,.28);';
                }
                // انقل الزر إلى ما بعد عمود نتائج البحث بالكامل، حتى يظهر أسفل آخر نتيجة.
                // لا نضعه بعد #rso وحده لأن Google قد يستخدمه لحاوية جزئية أعلى النتائج.
                var resultsBottom = document.querySelector('#center_col') ||
                                    document.querySelector('#search') ||
                                    document.querySelector('#rso');
                if (resultsBottom && resultsBottom.parentNode) {
                  resultsBottom.insertAdjacentElement('afterend', old);
                } else {
                  var bottom = document.querySelector('#botstuff');
                  (bottom || document.body).appendChild(old);
                }
                old.href = nextUrl.toString();
                old.onclick = function(e) {
                  e.preventDefault();
                  location.href = nextUrl.toString();
                };
              } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun isSearchResultsPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase(Locale.US) ?: return false
        val path = uri.path?.lowercase(Locale.US) ?: ""

        // محركات البحث المعروفة، بما فيها نطاقات Google المحلية مثل google.iq وgoogle.ae.
        val knownSearchHost =
            host == "google.com" || host.startsWith("google.") || host.endsWith(".google.com") ||
            host == "bing.com" || host.endsWith(".bing.com") ||
            host == "search.yahoo.com" ||
            host == "duckduckgo.com" || host.endsWith(".duckduckgo.com") ||
            host == "search.brave.com" ||
            host.endsWith(".yandex.com") || host.endsWith(".yandex.ru") ||
            host == "search.aol.com" ||
            host == "search.naver.com" ||
            host == "www.ecosia.org" ||
            host == "search.qwant.com"

        if (knownSearchHost) {
            return path.contains("search") ||
                uri.getQueryParameter("q") != null ||
                uri.getQueryParameter("query") != null ||
                uri.getQueryParameter("p") != null ||
                uri.getQueryParameter("text") != null
        }

        // دعم محركات البحث الأخرى التي تستخدم صفحة /search أو معاملات البحث الشائعة.
        return path == "/search" || path.startsWith("/search/") ||
            uri.getQueryParameter("q") != null ||
            uri.getQueryParameter("query") != null ||
            uri.getQueryParameter("search_query") != null
    }

    private fun filterSearxngMicrosoftResults(webView: WebView) {
        val host = try {
            Uri.parse(webView.url ?: "").host?.lowercase(Locale.US)
        } catch (_: Exception) {
            null
        }
        if (host != "searx.ononoki.org") return

        val js = """
            (function() {
                try {
                    var badHosts = ["bing.com", "microsoft.com"];

                    function isBadHost(host) {
                        host = String(host || "").toLowerCase().replace(/^www\./, "");
                        return badHosts.some(function(b) {
                            return host === b || host.endsWith("." + b);
                        });
                    }

                    document.querySelectorAll('a[href]').forEach(function(a) {
                        try {
                            var u = new URL(a.href, location.href);
                            if (!isBadHost(u.hostname)) return;

                            var card = a.closest("article.result, .result, li.result");
                            if (card) card.remove();
                        } catch (e) {}
                    });
                } catch (e) {}
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    private fun applyVisitedSearchResultColors(webView: WebView) {
        // Apply visited-link coloring on every website, not only recognized search engines.
        val url = webView.url ?: return

        val visitedUrlsJson = VisitedSites.allVisitedUrlsJson(this)
        val visitedHostsJson = VisitedSites.asJsonForInjection(this)
        val quotedUrlsJson = JSONObject.quote(visitedUrlsJson)
        val quotedHostsJson = JSONObject.quote(visitedHostsJson)

        val js = """
            (function(visitedUrlsRaw, visitedHostsRaw) {
                try {
                    var visitedUrls = JSON.parse(visitedUrlsRaw || '{}');
                    var visitedHosts = JSON.parse(visitedHostsRaw || '{}');
                    // لا نفرض لونًا على الروابط التي زارها المستخدم؛ اترك لون الموقع الأصلي.

                    function normalizeHost(host) {
                        return String(host || '')
                            .toLowerCase()
                            .replace(/^www\./, '')
                            .replace(/\.$/, '');
                    }

                    function normalize(u) {
                        try {
                            var x = new URL(u, location.href);
                            if (x.protocol !== 'http:' && x.protocol !== 'https:') return '';
                            x.hash = '';
                            var s = x.toString();
                            return s.endsWith('/') ? s.slice(0, -1) : s;
                        } catch (e) {
                            return '';
                        }
                    }

                    // Resolve search-engine redirect links before comparing hosts.
                    // Google result anchors often point to google.com/url, so comparing
                    // their host first incorrectly treats them as links to the search page.
                    function unwrapRedirect(u) {
                        try {
                            var x = new URL(u, location.href);
                            var h = normalizeHost(x.hostname);
                            var path = x.pathname.toLowerCase();
                            var isGoogle = h === 'google.com' || h.endsWith('.google.com') || /^google\.[a-z.]+$/.test(h);
                            var isBing = h === 'bing.com' || h.endsWith('.bing.com');
                            var isDuckDuckGo = h === 'duckduckgo.com' || h.endsWith('.duckduckgo.com');
                            var target = null;

                            if (isGoogle && path === '/url') {
                                target = x.searchParams.get('q') || x.searchParams.get('url') ||
                                         x.searchParams.get('u') || x.searchParams.get('adurl');
                            } else if (isBing && (path === '/ck/a' || path === '/aclick')) {
                                target = x.searchParams.get('u') || x.searchParams.get('url');
                                if (target && target.charAt(0) === 'a') {
                                    try { target = atob(target.substring(1).replace(/-/g, '+').replace(/_/g, '/')); } catch (e) {}
                                }
                            } else if (isDuckDuckGo && path === '/l/') {
                                target = x.searchParams.get('uddg');
                            }
                            return target ? new URL(target, location.href).href : u;
                        } catch (e) { return u; }
                    }

                    function isVisited(u) {
                        var unwrapped = unwrapRedirect(u);
                        var n = normalize(unwrapped);
                        if (!n) return false;

                        try {
                            var host = normalizeHost(new URL(unwrapped, location.href).hostname);
                            if (host) {
                                // تطابق النطاق نفسه، وكذلك النطاقات الفرعية للموقع.
                                if (visitedHosts[host]) return true;
                                var keys = Object.keys(visitedHosts);
                                for (var i = 0; i < keys.length; i++) {
                                    var visitedHost = normalizeHost(keys[i]);
                                    if (visitedHost &&
                                        (host === visitedHost ||
                                         host.endsWith('.' + visitedHost) ||
                                         visitedHost.endsWith('.' + host))) {
                                        return true;
                                    }
                                }
                            }
                        } catch (e) {}

                        if (visitedUrls[n]) return true;
                        var alt = n.endsWith('/') ? n.slice(0, -1) : n + '/';
                        return !!visitedUrls[alt];
                    }

                    function paintVisited(element) {
                        // تعطيل تلوين الروابط المزارة بالبنفسجي، مع الحفاظ على تنسيق الموقع.
                    }

                    function styleResults() {
                        var links = document.querySelectorAll('a[href]');
                        for (var i = 0; i < links.length; i++) {
                            var a = links[i];
                            var href = a.href || a.getAttribute('href') || '';
                            if (!/^https?:/i.test(href)) continue;

                            var destination = unwrapRedirect(href);
                            var linkUrl;
                            try { linkUrl = new URL(destination, location.href); } catch (e) { continue; }

                            var currentHost = normalizeHost(location.hostname);
                            var resultHost = normalizeHost(linkUrl.hostname);
                            if (!resultHost || resultHost === currentHost) continue;

                            if (!String(a.innerText || a.textContent || '').trim()) continue;

                            if (isVisited(destination) || isVisited(href)) {
                                paintVisited(a);
                            }
                            // غير المزور: لا نضع أي لون من التطبيق، حتى يبقى
                            // اللون الأصلي الذي اختاره محرك البحث.
                        }
                    }

                    function scheduleStyle() {
                        if (window.__downls10VisitedColorTimer) {
                            clearTimeout(window.__downls10VisitedColorTimer);
                        }
                        window.__downls10VisitedColorTimer = setTimeout(function() {
                            window.__downls10VisitedColorTimer = null;
                            styleResults();
                        }, 80);
                    }

                    styleResults();

                    // محركات البحث الحديثة تغيّر class/style للنتائج بعد تحميلها.
                    // نراقب إضافة النتائج وتغييرات style/class حتى لا يعود الرابط المزور
                    // إلى اللون الأصلي بعد أن نلوّنه.
                    if (!window.__downls10VisitedColorObserver) {
                        window.__downls10VisitedColorObserver = new MutationObserver(function() {
                            scheduleStyle();
                        });

                        window.__downls10VisitedColorObserver.observe(document.documentElement, {
                            childList: true,
                            attributes: true,
                            attributeFilter: ['class', 'style'],
                            subtree: true
                        });

                        window.addEventListener('pageshow', styleResults);

                        // عدة محاولات قصيرة لأن بعض محركات البحث ترسم النتائج على مراحل.
                        [150, 400, 800, 1500, 3000].forEach(function(delay) {
                            setTimeout(styleResults, delay);
                        });
                    }
                } catch (e) {}
            })($quotedUrlsJson, $quotedHostsJson);
        """
        webView.evaluateJavascript(js, null)
    }

    private fun recordVisitedNavigation(url: String?) {
        if (url.isNullOrBlank()) return
        val uri = try { Uri.parse(url) } catch (e: Exception) { return }
        if (uri.scheme != "http" && uri.scheme != "https") return
        var realUrl = url
        val host = uri.host ?: return
        if (host.contains("google.") && uri.path == "/url") {
            val target = uri.getQueryParameter("q") ?: uri.getQueryParameter("url")
            if (!target.isNullOrBlank()) realUrl = target
        }
        val realHost = try { Uri.parse(realUrl).host } catch (e: Exception) { null }
        VisitedSites.recordVisit(this, realHost)
        VisitedSites.recordUrl(this, realUrl)
    }

    private fun loadAndRecord(webView: WebView, url: String) {
        recordVisitedNavigation(url)
        webView.loadUrl(url)
    }

    /** يلتقط روابط الملفات المباشرة ذات الامتداد المعروف فقط، وليس صفحات البحث أو صفحات الويب. */
    private fun looksLikeDirectFileUrl(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http" && scheme != "https") return false

        val path = uri.path?.lowercase(Locale.US).orEmpty()
        val fileName = path.substringAfterLast('/')
        // لا تعتبر المسارات بلا نقطة/امتداد ملفات؛ مثل /search أو /download-page.
        if (fileName.isBlank() || '.' !in fileName) return false
        val ext = fileName.substringAfterLast('.', "").substringBefore(';')
        if (ext.isBlank() || ext.length > 10 || !ext.all { it.isLetterOrDigit() }) return false

        val webPageExtensions = setOf(
            "html", "htm", "php", "asp", "aspx", "jsp", "cgi", "do",
            "action", "ashx", "axd", "pl"
        )
        val knownFileExtensions = setOf(
            "apk", "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            "pdf", "txt", "csv", "json", "xml", "doc", "docx", "xls", "xlsx",
            "ppt", "pptx", "mp3", "m4a", "aac", "wav", "ogg", "flac",
            "mp4", "m4v", "mkv", "avi", "mov", "webm", "jpg", "jpeg", "png",
            "gif", "webp", "bmp", "svg", "iso", "img", "exe", "msi", "dmg",
            "deb", "rpm", "torrent", "epub", "mobi"
        )
        return ext !in webPageExtensions && ext in knownFileExtensions
    }

    /** يتحقق مما إذا كان الرابط مسار تنزيل واضحًا؛ لا يصنّف روابط البحث العادية كتنزيل. */
    private fun mightBeDownloadUrl(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http" && scheme != "https") return false

        if (looksLikeDirectFileUrl(rawUrl)) return true

        // افحص قيمة معاملات التنزيل نفسها بدل البحث عن كلمات قد تظهر داخل نص البحث.
        val downloadParams = setOf("download", "dl", "attachment", "file", "export")
        for (name in uri.queryParameterNames) {
            if (name.lowercase(Locale.US) in downloadParams) {
                val value = uri.getQueryParameter(name)?.lowercase(Locale.US).orEmpty()
                if (value in setOf("1", "true", "yes", "download", "attachment")) return true
            }
        }

        val path = uri.path?.lowercase(Locale.US).orEmpty().trimEnd('/')
        val downloadPaths = setOf("/download", "/dl", "/file", "/export", "/attachment")
        return path in downloadPaths
    }

    /** يعرض حوار تأكيد التنزيل مع اسم الملف وحجمه وامتداده، ثم ينفذ الإجراء المختار من المستخدم. */
    private fun confirmDownload(
        url: String,
        suggestedFileName: String?,
        userAgent: String?,
        referer: String?,
        mimeType: String? = null,
        showToast: Boolean = true
    ) {
        var dialogShown = false

        DownloadsRepository.probeAndConfirm(
            this@BrowserActivity,
            url,
            suggestedFileName,
            userAgent,
            referer,
            mimeType
        ) { preview ->
            if (!dialogShown) {
                dialogShown = true
                showDownloadDialog(preview, url, userAgent, referer, mimeType, showToast)
            }
        }

        // خطة بديلة: إذا استغرق الاستكشاف أكثر من 5 ثوانٍ، عرض الحوار بمعلومات أساسية
        Handler(Looper.getMainLooper()).postDelayed({
            if (!dialogShown) {
                dialogShown = true
                // إنشاء معاينة أساسية من المعلومات المتاحة
                val fileName = suggestedFileName?.takeIf { it.isNotBlank() } 
                    ?: url.substringAfterLast('/', "").takeIf { it.isNotBlank() } 
                    ?: "download"
                val ext = fileName.substringAfterLast(".", "bin")
                val basicPreview = DownloadsRepository.DownloadPreview(fileName, 0L, ext)
                showDownloadDialog(basicPreview, url, userAgent, referer, mimeType, showToast)
            }
        }, 5000)
    }

    /** يعرض حوار التنزيل مع المعلومات المتاحة */
    private fun showDownloadDialog(
        preview: DownloadsRepository.DownloadPreview,
        url: String,
        userAgent: String?,
        referer: String?,
        mimeType: String?,
        showToast: Boolean
    ) {
        val sizeText = if (preview.totalSize >= 0L) {
            val bytes = preview.totalSize
            if (bytes < 1024L) "$bytes B"
            else {
                val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
                var amount = bytes.toDouble() / 1024.0
                var unit = 0
                while (amount >= 1024.0 && unit < units.lastIndex) {
                    amount /= 1024.0
                    unit++
                }
                String.format(java.util.Locale.US, if (amount < 10.0) "%.2f %s" else "%.1f %s", amount, units[unit])
            }
        } else "حجم غير معروف"

        val dialogView = layoutInflater.inflate(R.layout.dialog_download_confirm, null)
        dialogView.findViewById<android.widget.TextView>(R.id.tvFileName).text = preview.fileName
        dialogView.findViewById<android.widget.TextView>(R.id.tvFileSize).text = sizeText
        dialogView.findViewById<android.widget.TextView>(R.id.tvFileExt).text = preview.extension.ifBlank { "-" }

        val dialog = android.app.AlertDialog.Builder(this@BrowserActivity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.btnAdd).setOnClickListener {
            DownloadsRepository.addDownloadItemOnly(
                this@BrowserActivity, url, preview.fileName, userAgent, referer,
                preview.totalSize
            )
            dialog.dismiss()
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnDownload).setOnClickListener {
            val startDownload = {
                DownloadsRepository.startNewDownload(
                    this@BrowserActivity, url,
                    showToast = showToast,
                    suggestedFileName = preview.fileName,
                    userAgent = userAgent,
                    referer = referer,
                    mimeType = mimeType
                )
            }
            // Android 9 and older need the legacy write permission for the public download folder.
            // Android 10+ uses MediaStore and must not be asked for broad storage access.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                ensureAndroidPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
                    if (granted) startDownload()
                    else Toast.makeText(
                        this@BrowserActivity,
                        "يحتاج التطبيق إذن التخزين لحفظ الملف. يمكنك اختيار ملف من مدير الملفات عند الحاجة.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                startDownload()
            }
            dialog.dismiss()
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun navigateWithSafetyCheck(webView: WebView, rawUrl: String) {
        val trimmedUrl = rawUrl.trim()
        val lowerUrl = trimmedUrl.lowercase(Locale.US)

        // لا تسمح لرابط intent:// بتجاوز فحص التصيّد/البرمجيات الخبيثة عبر فتح تطبيق خارجي مباشرة.
        // يُسمح فقط بالرابط الاحتياطي HTTPS، ويُمرّر إلى فحص الأمان المعتاد؛ ولا ننفّذ Intent الأصلي.
        if (lowerUrl.startsWith("intent:")) {
            val fallbackUrl = runCatching {
                Intent.parseUri(trimmedUrl, Intent.URI_INTENT_SCHEME)
                    .getStringExtra("browser_fallback_url")
                    ?.trim()
            }.getOrNull()
            val fallbackUri = runCatching { Uri.parse(fallbackUrl.orEmpty()) }.getOrNull()
            if (!fallbackUrl.isNullOrBlank() &&
                fallbackUri?.scheme.equals("https", ignoreCase = true) &&
                !fallbackUri?.host.isNullOrBlank() &&
                fallbackUri?.userInfo == null
            ) {
                Toast.makeText(
                    this,
                    "تم منع فتح التطبيق مباشرةً. يجري فحص رابط HTTPS الاحتياطي أولًا.",
                    Toast.LENGTH_LONG
                ).show()
                navigateWithSafetyCheck(webView, fallbackUrl)
            } else {
                Toast.makeText(
                    this,
                    "تم حظر رابط intent:// لعدم وجود رابط HTTPS احتياطي آمن للفحص.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        // روابط magnet وftp لا يفتحها المتصفح: تذهب لمدير التنزيل
        if (lowerUrl.startsWith("magnet:") || lowerUrl.startsWith("ftp://")) {
            DownloadsRepository.startNewDownload(this, rawUrl.trim(), showToast = true, referer = webView.url)
            return
        }
        // روابط ملفات Mega: صفحة Mega لا تستطيع التنزيل داخل WebView، فيتولاها مدير التنزيل مباشرة
        if (MegaSupport.isMegaFileLink(rawUrl.trim())) {
            DownloadsRepository.startNewDownload(this, rawUrl.trim(), showToast = true, referer = webView.url)
            return
        }
        // أي تنقل جديد (ضغط رابط/شريط العنوان) يستخدم الكاش الافتراضي وليس وضع الرجوع
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        tabs.find { it.webView == webView }?.let { cancelScrollRestore(it) }
        if (rawUrl == lastConfirmedSafetyUrl) {
            lastConfirmedSafetyUrl = null
            loadAndRecord(webView, rawUrl)
            return
        }

        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { null }

        // رابط مخصص (غير http/https): افتح التطبيق المثبّت إذا قدر يتعامل معه، وإلا تجاهله بهدوء
        if (uri?.scheme != null && uri.scheme != "http" && uri.scheme != "https") {
            openExternalAppLink(webView, rawUrl, uri)
            return
        }

        val host = uri?.host

        if (host != null) {
            val match = AdBlocker.matchSafetyCategory(this, host)

            if (match?.category == ListCategory.MALWARE ||
                match?.category == ListCategory.PHISHING ||
                match?.category == ListCategory.RISK
            ) {
                val reason = when (match?.category) {
                    ListCategory.MALWARE -> "الموقع موجود في قائمة المواقع المصنفة ببرمجيات خبيثة."
                    ListCategory.PHISHING -> "الموقع موجود في قائمة مواقع التصيد الاحتيالي."
                    else -> "الموقع موجود في قائمة المواقع المشبوهة."
                }
                AlertDialog.Builder(this)
                    .setTitle("تم حظر الموقع")
                    .setMessage("⛔ $reason\\n\\nلن يتم فتحه لحماية كلمات المرور وبياناتك.")
                    .setPositiveButton("حسنًا", null)
                    .show()
                return
            }

            if (match != null) {
                val warningParts = mutableListOf<String>()

                if (match.category == ListCategory.PORN) {
                    warningParts.add("⚠️ هذا الموقع موجود في قائمة المحتوى الإباحي.")
                }

                AlertDialog.Builder(this)
                    .setTitle("تحذير قبل فتح الموقع")
                    .setMessage(
                        warningParts.joinToString("\\n\\n") +
                            "\\n\\nهل تريد فتح هذا الموقع؟"
                    )
                    .setPositiveButton("متابعة") { _, _ ->
                        lastConfirmedSafetyUrl = rawUrl
                        loadAndRecord(webView, rawUrl)
                    }
                    .setNegativeButton("رفض", null)
                    .show()
                return
            }
        }

        if (uri?.scheme == "http") {
            Toast.makeText(this, "⚠️ هذا الموقع غير آمن (اتصال HTTP غير مشفّر)", Toast.LENGTH_SHORT).show()
        }

        loadAndRecord(webView, rawUrl)
    }

    // يفتح روابط التطبيقات الخارجية (Google Play, تيليجرام, واتساب, ...).
    // روابط "intent://" (مثل زر "الفتح في تطبيق Play") لها تنسيق خاص يجب فكّه
    // بواسطة Intent.parseUri وليس ببناء Intent يدويًا من الـ Uri مباشرة.
    private fun openExternalAppLink(webView: WebView, rawUrl: String, uri: Uri) {
        try {
            val intent: Intent
            var fallbackUrl: String? = null

            if (uri.scheme == "intent") {
                intent = Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
                fallbackUrl = intent.getStringExtra("browser_fallback_url")
                // نتأكد إنه ما يرجع لنفس المتصفح إذا التطبيق موجود أصلاً
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
            } else {
                intent = Intent(Intent.ACTION_VIEW, uri)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else if (!fallbackUrl.isNullOrBlank()) {
                // التطبيق غير مثبّت: افتح الرابط الاحتياطي (عادة صفحة متجر التطبيق نفسه)
                loadAndRecord(webView, fallbackUrl)
            }
            // ما فيه تطبيق ولا رابط احتياطي: تجاهل بهدوء
        } catch (e: Exception) {
            // تجاهل بهدوء عند فشل تحليل أو فتح الرابط
        }
    }

    private fun showBlockedInterstitial(webView: WebView, message: String) {
        val html = """
            <html dir="rtl"><body style="background:#000;color:#fff;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
            <h2>🚫 $message</h2></body></html>
        """
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun setupGoogleSuggestions() {
        editUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                val generation = ++suggestionGeneration
                if (query.length < 2 || (query.contains(".") && !query.contains(" "))) {
                    suggestionsPopup?.dismiss()
                    return
                }
                bgExecutor.execute {
                    val result = try {
                        val url = URL("https://suggestqueries.google.com/complete/search?client=firefox&hl=ar&q=" + Uri.encode(query))
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                        try {
                            val json = connection.inputStream.bufferedReader().use { it.readText() }
                            val arr = org.json.JSONArray(json)
                            val values = arr.optJSONArray(1) ?: org.json.JSONArray()
                            (0 until values.length()).mapNotNull { values.optString(it).takeIf { v -> v.isNotBlank() } }.distinct().take(8)
                        } finally { connection.disconnect() }
                    } catch (_: Exception) { emptyList<String>() }
                    mainHandler.post {
                        if (generation == suggestionGeneration && editUrl.hasFocus() && editUrl.text.toString().trim() == query) {
                            showSuggestionRows(result)
                        }
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        editUrl.setOnFocusChangeListener { _, focused -> if (!focused) suggestionsPopup?.dismiss() }
    }

    private fun showSuggestionRows(items: List<String>) {
        suggestionsPopup?.dismiss()
        if (items.isEmpty()) return
        suggestionItems = items
        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.DKGRAY)
            dividerHeight = 1
            adapter = object : BaseAdapter() {
                override fun getCount() = suggestionItems.size
                override fun getItem(position: Int) = suggestionItems[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = LinearLayout(this@BrowserActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(8, 2, 8, 2)
                        setBackgroundColor(0xFF202020.toInt())
                    }
                    val label = TextView(this@BrowserActivity).apply {
                        text = getItem(position)
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        // سطر أفقي واحد؛ تتحرك الكتابة الطويلة لإظهار كامل الاقتراح.
                        maxLines = 1
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                        marqueeRepeatLimit = -1
                        isSelected = true
                        setPadding(8, 6, 8, 6)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    label.setOnClickListener {
                        editUrl.setText(getItem(position)); editUrl.setSelection(editUrl.text.length)
                        suggestionsPopup?.dismiss(); loadFromAddressBar(); hideKeyboard()
                    }
                    val insert = Button(this@BrowserActivity).apply {
                        text = "➜"
                        setTextColor(Color.LTGRAY)
                        textSize = 14f
                        minWidth = 0
                        minimumWidth = 0
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(8, 0, 8, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            (48 * resources.displayMetrics.density).toInt(),
                            (40 * resources.displayMetrics.density).toInt()
                        )
                        setOnClickListener {
                            editUrl.setText(getItem(position)); editUrl.setSelection(editUrl.text.length)
                            suggestionsPopup?.dismiss()
                        }
                    }
                    row.addView(label); row.addView(insert)
                    return row
                }
            }
        }
        // إظهار خمسة اقتراحات كحد أقصى؛ عند وجود المزيد يمكن تمرير القائمة.
        val rowHeight = (52 * resources.displayMetrics.density).toInt()
        suggestionsPopup = PopupWindow(
            list,
            editUrl.width.coerceAtLeast(250),
            (items.size.coerceAtMost(5) * rowHeight),
            false
        ).apply {
            isOutsideTouchable = true
            isFocusable = false
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF202020.toInt()))
            showAsDropDown(editUrl)
        }
    }

    private fun buildSearchUrl(query: String): String {
        val encoded = Uri.encode(query)
        val isNight = nightMode
        return when (selectedSearchProvider()) {
            SEARCH_SEARXNG -> {
                var url = SEARXNG_SEARCH_BASE + Uri.encode(SEARXNG_ENGINE_PREFIX + query)
                // إضافة معلمة الوضع الليلي لمحركات SearXNG التي تدعمها
                if (isNight) url += "&theme=dark"
                url
            }
            // بحث فيديو: اطلب نتائج من 22 محرك/مصدر فيديو عبر أوامر SearXNG.
            // تظل قائمة المحركات مخفية، ولا يتغير مسار بحث SearXNG العادي أو Google.
            SEARCH_VIDEO -> {
                val videoEngineBangs = listOf(
                    "!biv", "!brvid", "!ddv", "!gov", "!qwv", "!dm", "!od",
                    "!ptb", "!ppd", "!ru", "!sep", "!vm", "!wcv", "!yt",
                    "!mvw", "!sevid", "!in", "!nico", "!nvrv", "!acf", "!iq", "!sogouv"
                ).joinToString(" ")
                var url = "https://searx.ononoki.org/search?q=" +
                    Uri.encode("$videoEngineBangs $query")
                // إضافة معلمة الوضع الليلي لبحث الفيديو أيضاً
                if (isNight) url += "&theme=dark"
                url
            }
            else -> "https://www.google.com/search?q=" + encoded
        }
    }

    private fun loadFromAddressBar() {
        var input = editUrl.text.toString().trim()
        if (input.isEmpty()) return

        input = when {
            Patterns.WEB_URL.matcher(input).matches() && !input.startsWith("http") -> "https://$input"
            input.contains(" ") || !input.contains(".") -> {
                val searchUrl = buildSearchUrl(input)
                searchUrl
            }
            !input.startsWith("http://") && !input.startsWith("https://") -> "https://$input"
            else -> input
        }

        if (currentTab().isHome) {
            currentTab().isHome = false
            webViewContainer.removeAllViews()
            webViewContainer.addView(
                currentWebView(),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        navigateWithSafetyCheck(currentWebView(), input)
    }

    // ---------------- قائمة ☰ ----------------

    private fun showBrowserMenu() {
        val options = arrayOf(
            "إخفاء الوسائط",
            "العلامات المرجعية",
            if (currentTab().desktopMode) "عرض الجوال" else "عرض سطح المكتب",
            "مانع الإعلانات وحماية التصفح",
            "تكبير/تصغير الخط",
            "السجل",
            "ترجمة إلى العربية",
            "الأذونات",
            "البحث",
            if (nightMode) "إيقاف الوضع الليلي" else "تفعيل الوضع الليلي"
        )

        AlertDialog.Builder(this)
            .setTitle("خيارات المتصفح")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showHideMediaDialog()
                    1 -> showBookmarksDialog()
                    2 -> toggleDesktopMode()
                    3 -> showAdBlockSettingsDialog()
                    4 -> showFontZoomDialog()
                    5 -> showHistoryDialog()
                    6 -> runTranslate()
                    7 -> showPermissionsDialog()
                    8 -> showSearchProviderDialog()
                    9 -> toggleNightMode()
                }
            }
            .show()
    }

    private fun showSearchProviderDialog() {
        val providers = arrayOf(
            SEARCH_SEARXNG to "بحث SearXNG",
            SEARCH_GOOGLE to "Google",
            SEARCH_VIDEO to "بحث فيديو"
        )
        val selected = selectedSearchProvider()
        val items = providers.map { (id, label) ->
            if (id == selected) "$label    ✅" else label
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("البحث")
            .setItems(items) { dialog, which ->
                val provider = providers[which].first
                saveSearchProvider(provider)
                dialog.dismiss()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }


    private fun showPermissionsDialog() {
        fun rows() = listOf(
            permissionStateText("الكاميرا (الأمامية والخلفية)", cameraAllowed()),
            permissionStateText("الميكروفون", micAllowed()),
            permissionStateText("الموقع الجغرافي", locationAllowed()),
            "تحديث الخدمات — فحص إصدار WebView"
        )

        val items = ArrayList(rows())
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.select_dialog_item, items)
        val dialog = AlertDialog.Builder(this)
            .setTitle("الأذونات")
            .setAdapter(adapter, null)
            .setNegativeButton("إغلاق", null)
            .create()
        dialog.show()

        // نحدّد المستمع بعد show() حتى تبقى القائمة مفتوحة وتتحدّث الحالة أمام المستخدم
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> togglePermission("camera", !cameraAllowed(), arrayOf(Manifest.permission.CAMERA), false)
                1 -> togglePermission("mic", !micAllowed(), arrayOf(Manifest.permission.RECORD_AUDIO), false)
                2 -> togglePermission(
                    "location", !locationAllowed(),
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), true
                )
                3 -> {
                    showUpdateServicesDialog()
                    return@setOnItemClickListener
                }
            }
            items.clear()
            items.addAll(rows())
            adapter.notifyDataSetChanged()
        }
    }

    /** يعرض فقط خدمة نظام فعلية يمكن معرفة إصدارها وتوجيه المستخدم لتحديثها رسمياً. */
    private fun showUpdateServicesDialog() {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        } else null
        val message = if (packageInfo != null) {
            "مكوّن WebView: ${packageInfo.packageName}\nالإصدار: ${packageInfo.versionName ?: "غير معروف"}\n\nيتم تحديث WebView من متجر Google Play أو من تحديثات النظام، بحسب الجهاز."
        } else {
            "تعذّر قراءة إصدار WebView من النظام على هذا الجهاز. افتح تحديثات النظام أو متجر التطبيقات للتحقق من وجود تحديث."
        }
        AlertDialog.Builder(this)
            .setTitle("تحديث الخدمات")
            .setMessage(message)
            .setPositiveButton("فتح صفحة WebView الرسمية") { _, _ ->
                val packageName = packageInfo?.packageName ?: "com.google.android.webview"
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                try {
                    startActivity(marketIntent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                    } catch (_: Exception) {
                        Toast.makeText(this, "تعذّر فتح متجر التطبيقات", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun togglePermission(key: String, newValue: Boolean, androidPerms: Array<String>, anyOf: Boolean) {
        settingsPrefs().edit().putBoolean(key, newValue).apply()
        // عند السماح نطلب صلاحية أندرويد مسبقًا حتى تعمل مباشرة عندما يطلبها الموقع
        if (newValue) ensureAndroidPermissions(androidPerms, anyOf) { }
    }

    /** نافذة تأكيد فتح نافذة منبثقة؛ عند «سماح» يُفتح الرابط في تبويب جديد. */
    private var popupDialogShowing = false

    private fun showPopupConfirmDialog(url: String) {
        if (popupDialogShowing || isFinishing || isDestroyed) return   // نافذة واحدة في كل مرة
        popupDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("نافذة منبثقة")
            .setMessage("هل تريد فتح نافذة منبثقة؟")
            .setPositiveButton("سماح") { _, _ -> openPopupInNewTab(url) }
            .setNegativeButton("رفض", null)
            .setOnDismissListener { popupDialogShowing = false }
            .show()
    }

    private fun openPopupInNewTab(rawUrl: String) {
        val cleanUrl = rawUrl.trim()
        val lower = cleanUrl.lowercase()
        val isWebUrl = lower.startsWith("http://") || lower.startsWith("https://")

        // إذا كانت النافذة المنبثقة تحتوي رابط ملف مباشر، فزر «سماح» يبدأ
        // التنزيل مباشرة بدل تحميل الرابط داخل WebView ثم فقدان حدث التنزيل.
        if (isWebUrl && looksLikeDirectFileUrl(cleanUrl)) {
            confirmDownload(
                cleanUrl,
                suggestedFileName = null,
                userAgent = currentWebView().settings.userAgentString,
                referer = currentWebView().url
            )
            return
        }

        if (!isWebUrl || MegaSupport.isMegaFileLink(cleanUrl)) {
            // magnet/ftp/Mega/تطبيق خارجي: يتولاها مسار التنقل الحالي بدون تبويب فارغ
            navigateWithSafetyCheck(currentWebView(), cleanUrl)
            return
        }

        createTabInternal(isHome = false, url = null)
        switchToTab(tabs.size - 1)
        navigateWithSafetyCheck(tabs[tabs.size - 1].webView, cleanUrl)
        persistTabs()
    }
    private fun toggleSetting(key: String, value: Boolean, onEnabled: (Boolean) -> Unit) {
        settingsPrefs().edit().putBoolean(key, value).apply()
        onEnabled(value)
        Toast.makeText(this, if (value) "تم التفعيل" else "تم الإيقاف", Toast.LENGTH_SHORT).show()
    }

    private fun requestAndroidPermIfNeeded(permission: String) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), ANDROID_PERM_CODE)
        }
    }

    // فتح مدير التنزيلات بإنشاء شاشة سليمة بدل إعادة ترتيب نسخة قديمة من النشاط.
    private fun openDownLS10() {
        try {
            startActivity(Intent(this, DownLS10MainActivity::class.java))
        } catch (e: Exception) {
            android.util.Log.e("DownLS10", "Failed to open download manager", e)
            Toast.makeText(this, "تعذر فتح مدير التنزيلات", Toast.LENGTH_LONG).show()
        }
    }

    // إعدادات إخفاء الصور والفيديو بشكل مستقل.
    private fun showHideMediaDialog() {
        val options = arrayOf(
            if (hideImages) "إظهار الصور" else "إخفاء الصور",
            if (hideVideos) "إظهار الفيديو" else "إخفاء الفيديو"
        )
        AlertDialog.Builder(this)
            .setTitle("إخفاء الوسائط بالكامل")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setHideImages(!hideImages)
                    1 -> setHideVideos(!hideVideos)
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun setHideImages(enabled: Boolean) {
        hideImages = enabled
        hideMedia = hideImages
        settingsPrefs().edit()
            .putBoolean("hideImages", hideImages)
            .putBoolean("hideMedia", hideMedia)
            .apply()
        tabs.forEach { tab ->
            tab.webView.settings.blockNetworkImage = hideImages
            tab.webView.settings.loadsImagesAutomatically = !hideImages
            applyHideMediaJs(tab.webView)
        }
        Toast.makeText(this, if (enabled) "تم إخفاء الصور" else "تم إظهار الصور", Toast.LENGTH_SHORT).show()
    }

    private fun setHideVideos(enabled: Boolean) {
        hideVideos = enabled
        settingsPrefs().edit().putBoolean("hideVideos", hideVideos).apply()
        tabs.forEach { tab -> applyHideMediaJs(tab.webView) }
        Toast.makeText(
            this,
            if (enabled) "تم إخفاء صورة الفيديو مع استمرار تشغيله" else "تم إظهار الفيديو",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyHideMediaJs(webView: WebView) {
        val hideImagesCss = if (hideImages) """
            img, picture, svg, canvas, [role="img"], input[type="image"] {
                visibility: hidden !important;
            }
            * { background-image: none !important; }
        """ else """
            img, picture, svg, canvas, [role="img"], input[type="image"] {
                visibility: visible !important;
            }
        """
        // opacity يخفي صورة الفيديو دون إزالة عنصر الفيديو أو إيقاف تشغيله.
        val hideVideosCss = if (hideVideos) """
            video { opacity: 0 !important; }
            video::-webkit-media-controls { opacity: 0 !important; }
        """ else """
            video { opacity: 1 !important; }
            video::-webkit-media-controls { opacity: 1 !important; }
        """
        val cssJson = org.json.JSONObject.quote(hideImagesCss + "\n" + hideVideosCss)
        val js = """
            (function(){
                var css = $cssJson;
                var style = document.getElementById('__downls10_hide_media__');
                if (!style) {
                    style = document.createElement('style');
                    style.id = '__downls10_hide_media__';
                    (document.head || document.documentElement).appendChild(style);
                }
                style.textContent = css;
                function apply() {
                    var s = document.getElementById('__downls10_hide_media__');
                    if (s && !s.parentNode && (document.head || document.documentElement)) {
                        (document.head || document.documentElement).appendChild(s);
                    }
                }
                apply();
                if (!window.__downls10MediaObserver && document.documentElement) {
                    window.__downls10MediaObserver = new MutationObserver(apply);
                    window.__downls10MediaObserver.observe(document.documentElement, {childList:true,subtree:true});
                }
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // ⭐ إضافة الصفحة الحالية مباشرة
    private fun addCurrentPageToBookmarks() {
        if (currentTab().isHome) {
            Toast.makeText(this, "لا توجد صفحة لإضافتها", Toast.LENGTH_SHORT).show()
            return
        }
        val url = currentWebView().url
        val title = currentWebView().title
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "لا توجد صفحة لإضافتها", Toast.LENGTH_SHORT).show()
            return
        }
        if (BrowserStorage.isBookmarked(this, url)) {
            Toast.makeText(this, "الصفحة مضافة مسبقاً للعلامات", Toast.LENGTH_SHORT).show()
            return
        }
        BrowserStorage.addBookmark(this, title ?: url, url)
        Toast.makeText(this, "تمت الإضافة للعلامات المرجعية", Toast.LENGTH_SHORT).show()
    }

    // 2) العلامات المرجعية (عرض + حذف + استيراد/تصدير)
    private fun showBookmarksDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 6, 12, 6)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(container)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()

        fun refresh() {
            container.removeAllViews()
            val bookmarks = BrowserStorage.getBookmarks(this)
            // إظهار ست علامات مرجعية كحد أقصى؛ وما زاد يظهر بالتمرير داخل القائمة.
            val rowHeight = (52 * resources.displayMetrics.density).toInt()
            val visibleRows = bookmarks.size.coerceAtMost(6)
            val listHeight = (rowHeight * visibleRows).coerceAtLeast(rowHeight)
            scroll.layoutParams = scroll.layoutParams?.apply {
                height = listHeight
            } ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeight
            )
            if (bookmarks.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "لا توجد علامات مرجعية بعد"
                    setTextColor(Color.LTGRAY)
                    setPadding(0, 6, 0, 6)
                })
            } else {
                bookmarks.forEach { bookmark ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        // إزالة الفراغات الرأسية بين العلامات مع إبقاء الصف قابلًا للمس.
                        setPadding(0, 0, 0, 0)
                        minimumHeight = (52 * resources.displayMetrics.density).toInt()
                    }
                    val titleText = TextView(this).apply {
                        text = bookmark.title
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            createBrowsingTab(bookmark.url)
                            dialog.dismiss()
                        }
                    }
                    val deleteBtn = Button(this).apply {
                        text = "✕"
                        setTextColor(Color.WHITE)
                        minHeight = (52 * resources.displayMetrics.density).toInt()
                        setPadding(16, 0, 16, 0)
                        setOnClickListener {
                            BrowserStorage.removeBookmark(this@BrowserActivity, bookmark.url)
                            refresh()
                        }
                    }
                    row.addView(titleText)
                    row.addView(deleteBtn)
                    container.addView(row)

                    container.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(Color.parseColor("#333333"))
                    })
                }
            }
        }

        val titleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 8)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 4, 0, 4)
        }
        titleRow.addView(TextView(this).apply {
            text = "العلامات المرجعية"
            textSize = 20f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(Button(this).apply {
            text = "X"
            setTextColor(Color.WHITE)
            setOnClickListener {
                AlertDialog.Builder(this@BrowserActivity)
                    .setMessage("هل تريد حذف جميع العلامات المرجعية؟")
                    .setPositiveButton("موافق") { _, _ ->
                        BrowserStorage.getBookmarks(this@BrowserActivity).forEach { BrowserStorage.removeBookmark(this@BrowserActivity, it.url) }
                        refresh()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        })
        titleHeader.addView(titleRow)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
        }
        actionsRow.addView(Button(this).apply {
            text = "تصدير"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { exportBookmarks { refresh() } }
        })
        actionsRow.addView(Button(this).apply {
            text = "استيراد"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { importBookmarks { refresh() } }
        })
        titleHeader.addView(actionsRow)

        dialog.setCustomTitle(titleHeader)
        refresh()
        dialog.show()
    }

    private fun exportBookmarks(onFinished: () -> Unit) {
        pendingBookmarkExportContent = BrowserStorage.buildBookmarksHtml(this)
        try {
            BookmarksFileManager.startExport(this, pendingBookmarkExportContent!!)
        } catch (e: Exception) {
            pendingBookmarkExportContent = null
            Toast.makeText(this, "فشل فتح حفظ العلامات: ${e.message ?: "غير معروف"}", Toast.LENGTH_LONG).show()
        }
        onFinished()
    }

    private fun importBookmarks(onFinished: () -> Unit) {
        try {
            BookmarksFileManager.startImport(this)
        } catch (e: Exception) {
            Toast.makeText(this, "فشل فتح اختيار ملف العلامات: ${e.message ?: "غير معروف"}", Toast.LENGTH_LONG).show()
        }
        onFinished()
    }

    // 3) عرض سطح المكتب (خاص بالتبويب الحالي فقط)
    private fun toggleDesktopMode() {
        val tab = currentTab()
        tab.desktopMode = !tab.desktopMode
        currentWebView().settings.userAgentString = if (tab.desktopMode) desktopUA else mobileUA
        currentWebView().reload()
        Toast.makeText(this, if (tab.desktopMode) "عرض سطح المكتب مفعّل لهذا التبويب" else "عرض الجوال مفعّل لهذا التبويب", Toast.LENGTH_SHORT).show()
    }

    private fun applyDesktopViewportJs(webView: WebView) {
        val js = """
            (function(){
                var meta = document.querySelector('meta[name=viewport]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.setAttribute('name', 'viewport');
                    document.head.appendChild(meta);
                }
                meta.setAttribute('content', 'width=1024, initial-scale=1, user-scalable=yes');
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // الوضع الليلي: يقلب ألوان أي موقع فاتح إلى أسود، ويترك الموقع الداكن كما هو.
    // الحالة محفوظة دائماً (تبقى بعد إغلاق التطبيق)، ويُطبَّق مباشرة على كل التبويبات عند التشغيل.
    private fun toggleNightMode() {
        nightMode = !nightMode
        settingsPrefs().edit().putBoolean("nightMode", nightMode).apply()
        tabs.forEach { tab ->
            tab.webView.setBackgroundColor(if (nightMode) Color.BLACK else Color.WHITE)
            if (!tab.isHome) {
                if (nightMode) {
                    applyNightModeJs(tab.webView)
                    applySearxngNightModeJs(tab.webView)
                } else {
                    removeNightModeJs(tab.webView)
                }
            }
        }
        Toast.makeText(this, if (nightMode) "الوضع الليلي مفعّل (ثابت دائماً)" else "تم إيقاف الوضع الليلي", Toast.LENGTH_SHORT).show()
    }

    private var lastNightInject = 0L

    /** آخر حكم محفوظ لهذا الموقع: true = داكن أصلاً، false = فاتح، null = غير معروف. */
    private fun nightHostState(host: String?): Boolean? {
        if (host.isNullOrBlank()) return null
        val raw = settingsPrefs().getString("nightHosts", null) ?: return null
        return try {
            val obj = org.json.JSONObject(raw)
            if (obj.has(host)) obj.getBoolean(host) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun rememberNightHost(host: String?, dark: Boolean) {
        if (host.isNullOrBlank()) return
        try {
            val raw = settingsPrefs().getString("nightHosts", null)
            var obj = if (raw != null) org.json.JSONObject(raw) else org.json.JSONObject()
            if (obj.length() > 400) obj = org.json.JSONObject()
            if (obj.has(host) && obj.getBoolean(host) == dark) return
            obj.put(host, dark)
            settingsPrefs().edit().putString("nightHosts", obj.toString()).apply()
        } catch (e: Exception) { }
    }

    /** يقرأ ما حكمت به الصفحة (داكنة أم فاتحة) ويحفظه لهذا الموقع ليُطبَّق أسرع في الزيارة القادمة. */
    private fun recordNightResult(webView: WebView) {
        val host = try { Uri.parse(webView.url ?: "").host } catch (e: Exception) { null } ?: return
        webView.evaluateJavascript(
            "(window.__downls10NightDark === true ? '1' : (window.__downls10NightDark === false ? '0' : ''))"
        ) { result ->
            val value = result?.trim('"')
            if (value == "1") rememberNightHost(host, true)
            else if (value == "0") rememberNightHost(host, false)
        }
    }

    /**
     * يطبّق الوضع الليلي على الصفحة. آمن للاستدعاء المتكرر (يُستدعى عند بدء الصفحة، وظهورها، وتقدّم التحميل، واكتمالها).
     * الصفحة الفاتحة تُقلب إلى أسود، والصفحة الداكنة أصلاً تبقى كما هي (يُفحص لون الخلفية الفعلي في عدة نقاط من الشاشة).
     */
    private fun applyNightModeJs(webView: WebView) {
        lastNightInject = System.currentTimeMillis()
        val host = try { Uri.parse(webView.url ?: "").host } catch (e: Exception) { null }
        val hostDark = when (nightHostState(host)) {
            true -> "true"
            false -> "false"
            null -> "null"
        }
        val js = """
            (function(hostDark) {
                try {
                    window.__downls10NightOff = false;
                    var ID = '__downls10_night_mode__';
                    // اقلب ألوان الصفحة فقط للوضع الليلي، ثم اعكس الفلتر على الوسائط
                    // حتى تبقى ألوان الصور والفيديوهات والرسومات كما هي.
                    var CSS_ON = 'html{filter:invert(1) hue-rotate(180deg) !important;background:#fff !important;}' +
                        'img,video,iframe,picture,svg,canvas,[style*="background-image"],[role="img"]{filter:invert(1) hue-rotate(180deg) !important;}' +
                        'video::-webkit-media-controls,video::-webkit-media-controls-panel{filter:none !important;}';

                    function styleEl() {
                        var s = document.getElementById(ID);
                        if (!s) {
                            s = document.createElement('style');
                            s.id = ID;
                            (document.head || document.documentElement).appendChild(s);
                        }
                        return s;
                    }
                    function setInverted(on) {
                        var s = styleEl();
                        var css = on ? CSS_ON : '';
                        if (s.textContent !== css) s.textContent = css;
                        window.__downls10NightInverted = on;
                    }
                    function parseColor(str) {
                        var m = String(str).match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?/);
                        if (!m) return null;
                        return { r: +m[1], g: +m[2], b: +m[3], a: (m[4] === undefined ? 1 : parseFloat(m[4])) };
                    }
                    // لون الخلفية الفعلي عند نقطة من الشاشة (أول عنصر له خلفية صريحة)
                    function backgroundAt(x, y) {
                        var els = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
                        for (var i = 0; i < els.length; i++) {
                            var cs = getComputedStyle(els[i]);
                            var c = parseColor(cs.backgroundColor);
                            if (c && c.a > 0.6) return c;
                            if (cs.backgroundImage && cs.backgroundImage !== 'none') return null;
                        }
                        return 'canvas';
                    }
                    function detectDark() {
                        if (!document.body) return null;
                        var w = window.innerWidth, h = window.innerHeight;
                        if (!w || !h) return null;
                        var scheme = String(getComputedStyle(document.documentElement).colorScheme || '').trim();
                        var canvasLum = (scheme === 'dark') ? 0 : 255;
                        var pts = [0.1, 0.3, 0.5, 0.7, 0.9];
                        var dark = 0, light = 0;
                        for (var i = 0; i < pts.length; i++) {
                            for (var j = 0; j < pts.length; j++) {
                                var bg = backgroundAt(Math.floor(w * pts[i]), Math.floor(h * pts[j]));
                                if (bg === null) continue;
                                var lum = (bg === 'canvas') ? canvasLum : (0.299 * bg.r + 0.587 * bg.g + 0.114 * bg.b);
                                if (lum < 110) dark++; else light++;
                            }
                        }
                        if (dark + light < 6) return null;
                        return (dark / (dark + light)) >= 0.55;
                    }
                    function run() {
                        if (window.__downls10NightOff) return;

                        // نتائج البحث فقط: لا نقلب الصفحة بالكامل حتى لا تتأثر صفحات المواقع العادية.
                        // نرفع تباين النصوص والفواصل الضعيفة مثل الخط أسفل تاريخ النتيجة.
                        var host = String(location.hostname || '').toLowerCase();
                        var path = String(location.pathname || '').toLowerCase();
                        var query = new URLSearchParams(location.search || '');
                        var searchHost =
                            host === 'google.com' || /^google\./.test(host) || host.endsWith('.google.com') ||
                            host === 'bing.com' || host.endsWith('.bing.com') ||
                            host === 'search.yahoo.com' || host === 'duckduckgo.com' || host.endsWith('.duckduckgo.com') ||
                            host === 'search.brave.com' || host.endsWith('.yandex.com') || host.endsWith('.yandex.ru') ||
                            host === 'search.aol.com' || host === 'search.naver.com' ||
                            host === 'www.ecosia.org' || host === 'search.qwant.com';
                        var isSearch = searchHost && (
                            path.indexOf('search') >= 0 || query.has('q') || query.has('query') ||
                            query.has('p') || query.has('text')
                        );
                        if (isSearch) {
                            var searchCss =
                                'html,body{background:#000!important;color:#f1f1f1!important;color-scheme:dark!important;}' +
                                'body *{border-color:#666!important;}' +
                                'hr,[role="separator"],.MjjYud,.g,.tF2Cxc,.Gx5Zad,.xpd,.MjjYud>div{border-color:#777!important;}' +
                                'a h3,a h2,h3,h2,[role="heading"]{color:#fff!important;}' +
                                'cite,.VuuXrf,.qLRx3b,.tjvcx,.MUxGbd,.LEwnzc,.s3v9rd,.VwiC3b,.IsZvec,.yDYNvb,.lyLwlc{color:#c5c5c5!important;}' +
                                'input,textarea,button{color:#f1f1f1!important;border-color:#777!important;}';
                            var ss = styleEl();
                            if (ss.textContent !== searchCss) ss.textContent = searchCss;
                            window.__downls10NightInverted = false;
                            window.__downls10NightDark = true;
                            return;
                        }

                        // افحص الخلفية الفعلية: إذا كانت الصفحة داكنة أصلًا، اتركها كما هي.
                        // إذا كانت فاتحة أو تعذّر تحديد لونها، فعّل قلب الألوان للوضع الليلي.
                        var detectedDark = detectDark();
                        if (detectedDark === true) {
                            window.__downls10NightDark = true;
                            setInverted(false);
                        } else {
                            window.__downls10NightDark = false;
                            setInverted(true);
                        }
                    }
                    run();

                    if (!window.__downls10NightHooked) {
                        window.__downls10NightHooked = true;
                        var timer = null;
                        var later = function() { clearTimeout(timer); timer = setTimeout(run, 250); };
                        document.addEventListener('DOMContentLoaded', run);
                        window.addEventListener('load', function() { run(); setTimeout(run, 800); setTimeout(run, 2000); });
                        setTimeout(run, 300);
                        setTimeout(run, 1000);
                        // إذا بدّل الموقع نفسه بين فاتح وداكن نعيد الحكم
                        var opts = { attributes: true, attributeFilter: ['class', 'style', 'data-theme', 'data-bs-theme'] };
                        if (document.documentElement) new MutationObserver(later).observe(document.documentElement, opts);
                        var bodyWatch = setInterval(function() {
                            if (document.body) {
                                new MutationObserver(later).observe(document.body, opts);
                                clearInterval(bodyWatch);
                            }
                        }, 200);
                        setTimeout(function() { clearInterval(bodyWatch); }, 5000);
                    }
                } catch (e) { }
            })($hostDark);
        """
        webView.evaluateJavascript(js, null)
    }

    private fun removeNightModeJs(webView: WebView) {
        val js = """
            (function(){
                window.__downls10NightOff = true;
                window.__downls10NightInverted = undefined;
                var style = document.getElementById('__downls10_night_mode__');
                if (style) style.textContent = '';
                // إزالة نمط SearXNG الليلي أيضاً
                var searxStyle = document.getElementById('__downls10_searx_dark__');
                if (searxStyle) searxStyle.textContent = '';
                // إيقاف المراقبين
                if (window.__downls10SearxDarkObserver) {
                    window.__downls10SearxDarkObserver.disconnect();
                    window.__downls10SearxDarkObserver = null;
                }
                if (window.__downls10SearxDarkTimer) {
                    clearInterval(window.__downls10SearxDarkTimer);
                    window.__downls10SearxDarkTimer = null;
                }
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // 4) مانع الإعلانات وحماية التصفح (كل القوائم)
    private fun showAdBlockSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 4, 16, 4)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        fun refresh() {
            container.removeAllViews()
            val globalRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 6)
            }
            globalRow.addView(TextView(this).apply {
                text = "مانع الإعلانات"
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            globalRow.addView(Button(this).apply {
                text = if (AdBlocker.isGloballyEnabled(this@BrowserActivity)) "مفعّل" else "معطّل"
                setOnClickListener {
                    AdBlocker.setGloballyEnabled(this@BrowserActivity, !AdBlocker.isGloballyEnabled(this@BrowserActivity))
                    tabs.forEach { tab -> if (!tab.isHome) tab.webView.reload() }
                    refresh()
                }
            })
            container.addView(globalRow)
            AdBlocker.LISTS.forEach { def ->
                val block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 5, 0, 5)
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val nameText = TextView(this).apply {
                    text = def.displayName
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val toggleBtn = Button(this).apply {
                    val enabled = AdBlocker.isEnabled(this@BrowserActivity, def.id)
                    text = if (enabled) "مفعّل" else "متوقف"
                    setOnClickListener {
                        val newVal = !AdBlocker.isEnabled(this@BrowserActivity, def.id)
                        AdBlocker.setEnabled(this@BrowserActivity, def.id, newVal)
                        tabs.forEach { tab -> if (!tab.isHome) tab.webView.reload() }
                        refresh()
                    }
                }
                row.addView(nameText)
                row.addView(toggleBtn)

                val statusRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val statusText = TextView(this).apply {
                    text = "${AdBlocker.ruleCount(this@BrowserActivity, def.id)} قاعدة"
                    setTextColor(Color.LTGRAY)
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val updateBtn = Button(this).apply {
                    text = "تحديث"
                    setOnClickListener {
                        AdBlocker.updateList(this@BrowserActivity, def.id) { success, count, error ->
                            if (!success) {
                                Toast.makeText(this@BrowserActivity, "${def.displayName}: فشل - $error", Toast.LENGTH_LONG).show()
                            }
                            refresh()
                        }
                    }
                }
                statusRow.addView(statusText)
                statusRow.addView(updateBtn)

                block.addView(row)
                block.addView(statusRow)
                container.addView(block)

                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#333333"))
                })
            }
        }

        refresh()

        AlertDialog.Builder(this)
            .setTitle("مانع الإعلانات وحماية التصفح")
            .setView(scroll)
            .setPositiveButton("إغلاق", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    val metrics = resources.displayMetrics
                    dialog.window?.setLayout(
                        (metrics.widthPixels * 0.90f).toInt(),
                        (metrics.heightPixels * 0.72f).toInt()
                    )
                }
                dialog.show()
            }
    }

    // 5) تكبير/تصغير الخط
    private fun showFontZoomDialog() {
        val current = savedFontZoom()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 8, 24, 8)
        }

        val sizeText = TextView(this).apply {
            text = "حجم الخط: ${current}%"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val decrease = Button(this).apply {
            text = "تصغير -"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val increase = Button(this).apply {
            text = "تكبير +"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        controls.addView(decrease)
        controls.addView(increase)

        val close = Button(this).apply {
            text = "إغلاق"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }

        root.addView(sizeText)
        root.addView(controls)
        root.addView(close)

        val dialog = AlertDialog.Builder(this)
            .setTitle("تكبير/تصغير الخط")
            .setView(root)
            .create()

        fun applyZoom(value: Int) {
            val zoom = value.coerceIn(50, 200)
            currentWebView().settings.textZoom = zoom
            saveFontZoom(zoom)
            sizeText.text = "حجم الخط: ${zoom}%"
        }

        decrease.setOnClickListener {
            val currentValue = currentWebView().settings.textZoom.coerceIn(50, 200)
            applyZoom(currentValue - 10)
        }

        increase.setOnClickListener {
            val currentValue = currentWebView().settings.textZoom.coerceIn(50, 200)
            applyZoom(currentValue + 10)
        }

        close.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // 6) السجل
    private fun showHistoryDialog() {
        val history = BrowserStorage.getHistory(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "السجل فارغ", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        history.forEach { entry ->
            val displayText = entry.title.ifBlank { entry.url }
            val row = TextView(this).apply {
                text = displayText
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 20, 0, 20)
                setOnClickListener { createBrowsingTab(entry.url) }
            }
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#333333"))
            })
        }

        AlertDialog.Builder(this)
            .setTitle("سجل التصفح")
            .setView(scroll)
            .setPositiveButton("مسح السجل") { _, _ -> BrowserStorage.clearHistory(this) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    // 7) ترجمة إلى العربية
    private fun runTranslate() {
        val tab = currentTab()
        if (tab.isHome) {
            Toast.makeText(this, "لا توجد صفحة لترجمتها", Toast.LENGTH_SHORT).show()
            return
        }

        // Translation remains enabled for this tab; every new page is translated automatically.
        tab.translationEnabled = true
        tab.lastTranslatedUrl = ""
        tab.translationInProgress = false
        Toast.makeText(this, "جاري ترجمة الموقع إلى العربية...", Toast.LENGTH_SHORT).show()
        translateTabPageIfNeeded(tab.webView, tab)
    }

    private fun translateTabPageIfNeeded(webView: WebView, tab: Tab) {
        if (!tab.translationEnabled || tab.translationInProgress) return
        val url = webView.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        if (url == tab.lastTranslatedUrl) return

        tab.translationInProgress = true
        PageTranslator.translatePage(webView, "ar") { success, message ->
            tab.translationInProgress = false
            if (success) tab.lastTranslatedUrl = url
            if (isActiveTab(webView) && !isFinishing) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- قائمة التبويبات ----------------

    private fun showTabsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("الصفحات المفتوحة")
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()

        fun refresh() {
            container.removeAllViews()
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 20)
            }
            val addBtn = Button(this).apply {
                text = "إضافة صفحة جديدة"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    createHomeTab()
                    dialog.dismiss()
                }
            }
            val closeAllBtn = Button(this).apply {
                text = "إغلاق الكل"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    // إغلاق جميع الصفحات مباشرةً دون نافذة تأكيد.
                    closeAllTabs()
                    dialog.dismiss()
                }
            }
            actionsRow.addView(addBtn)
            actionsRow.addView(closeAllBtn)
            container.addView(actionsRow)

            // ستة تبويبات ظاهرة كحد أقصى؛ أي تبويب إضافي يظهر بالتمرير داخل القائمة.
            val density = resources.displayMetrics.density
            val rowHeight = (48 * density).toInt()
            val visibleRows = tabs.size.coerceAtMost(6)
            val fixedContentHeight = (104 * density).toInt()
            scroll.layoutParams = scroll.layoutParams?.apply {
                height = rowHeight * visibleRows + fixedContentHeight
            } ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rowHeight * visibleRows + fixedContentHeight
            )

            tabs.forEachIndexed { index, tab ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    // تقليل الفراغات بين الصفحات مع الحفاظ على مساحة مناسبة للضغط.
                    setPadding(0, 0, 0, 0)
                    minimumHeight = rowHeight
                }
                val label = if (tab.isHome) "الصفحة الرئيسية" else tab.title
                val tabUrl = if (tab.isHome) null else tab.webView.url
                val titleText = TextView(this).apply {
                    text = if (index == currentTabIndex) "● $label" else label
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        switchToTab(index)
                        dialog.dismiss()
                    }
                }
                val closeBtn = Button(this).apply {
                    text = "✕"
                    setTextColor(Color.WHITE)
                    minHeight = rowHeight
                    setPadding(20, 0, 20, 0)
                    setOnClickListener {
                        closeTab(index)
                        refresh()
                    }
                }
                row.addView(titleText)
                row.addView(closeBtn)
                container.addView(row)

                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#333333"))
                })
            }

        }

        refresh()
        dialog.show()
    }

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu,
        view: View,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?
    ) {
        if (view is WebView) {
            // لا تسمح بقائمة WebView الافتراضية؛ حتى لا تظهر خيارات نسخ/سحب/نقل الصورة.
            // قائمة الصور الوحيدة المسموح بها تُعرض من setOnLongClickListener أعلاه.
            val hitTest = view.getHitTestResult()
            if (hitTest.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTest.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val imageUrl = hitTest.extra
                if (!imageUrl.isNullOrBlank() &&
                    (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
                    showImageContextMenu(imageUrl, hitTest.type)
                }
            }
            return
        }
        super.onCreateContextMenu(menu, view, menuInfo)
    }

    // ============= معالج الصور المخصص =============

    private fun showImageContextMenu(imageUrl: String, hitType: Int) {
        val isLinkedImage = hitType == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        
        val dialog = AlertDialog.Builder(this@BrowserActivity)
            .setTitle("خيارات الصورة")
            .setItems(arrayOf("فتح الصورة", "تنزيل الصورة")) { _, which ->
                when (which) {
                    0 -> openImageFullScreen(imageUrl)
                    1 -> downloadImage(imageUrl)
                }
            }
            .create()
        dialog.show()
    }

    private fun openImageFullScreen(imageUrl: String) {
        if (isFinishing || isDestroyed) return

        // استخدم Dialog عادي بملء الشاشة بدلاً من AlertDialog مخصص؛
        // هذا يمنع تعارض قياسات نافذة AlertDialog مع محتوى الصورة على بعض إصدارات Android.
        val viewer = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val density = resources.displayMetrics.density
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val imageView = TouchImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "الصورة"
        }

        val closeBtn = android.widget.TextView(this).apply {
            text = "×"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                (52 * density).toInt(),
                (52 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, (12 * density).toInt(), (12 * density).toInt(), 0)
            }
            setOnClickListener { viewer.dismiss() }
            contentDescription = "إغلاق الصورة"
            elevation = 10 * density
        }

        container.addView(imageView)
        container.addView(closeBtn)
        viewer.setContentView(container)
        viewer.setOnDismissListener { imageView.setImageDrawable(null) }
        viewer.show()
        viewer.window?.apply {
            setBackgroundDrawableResource(android.R.color.black)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            decorView.setPadding(0, 0, 0, 0)
            setStatusBarColor(Color.BLACK)
            setNavigationBarColor(Color.BLACK)
        }

        loadImageInto(imageView, imageUrl)
    }

    private fun loadImageInto(imageView: ImageView, url: String) {
        // تحميل بسيط باستخدام Thread
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val inputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                runOnUiThread {
                    imageView.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@BrowserActivity, "فشل تحميل الصورة", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun downloadImage(imageUrl: String) {
        // استخدام نفس نظام التنزيل الموجود
        val nameFromUrl = imageUrl.substringAfterLast("/").takeIf { it.contains(".") } ?: "image.jpg"
        confirmDownload(
            imageUrl,
            suggestedFileName = nameFromUrl,
            userAgent = currentTab().webView.settings.userAgentString,
            referer = currentTab().webView.url,
            showToast = true
        )
    }

    // ImageView مع دعم التكبير باللمس (Pinch to zoom)
    class TouchImageView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
    ) : ImageView(context, attrs, defStyle) {

        private var scale = 1f
        private var lastScale = 1f
        private var translateX = 0f
        private var translateY = 0f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        private val minScale = 1f
        private val maxScale = 5f

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleGestureDetector.onTouchEvent(event)
            
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleGestureDetector.isInProgress) return true
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    translateX += dx
                    translateY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // إعادة التمركز عند الإفلات
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.save()
            canvas.translate(translateX, translateY)
            canvas.scale(scale, scale, width / 2f, height / 2f)
            super.onDraw(canvas)
            canvas.restore()
        }

        private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val focusX = detector.focusX
                val focusY = detector.focusY
                
                // تعديل الترجمة للحفاظ على نقطة التركيز
                translateX = (translateX * newScale / scale).coerceIn(-width * (newScale - 1), width * (newScale - 1))
                translateY = (translateY * newScale / scale).coerceIn(-height * (newScale - 1), height * (newScale - 1))
                
                scale = newScale
                invalidate()
                return true
            }
        }
    }

    override fun onBackPressed() {
        val tab = currentTab()
        if (!tab.isHome && navigateHistory(tab, -1)) {
            return
        }
        if (!tab.isHome) {
            tab.isHome = true
            switchToTab(currentTabIndex)
            persistTabs()
            return
        }
        finishAffinity()
    }

    override fun onDestroy() {
        // Preserve existing first-party login/session cookies; do not clear the cookie store.
        // Third-party cookies remain disabled for each WebView.
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // Cookie persistence errors must not prevent activity cleanup.
        }
        tabs.forEach { it.webView.destroy() }
        super.onDestroy()
    }
}
