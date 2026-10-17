package com.whatsapp.webclient

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.Profile
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.whatsapp.webclient.accounts.AccountLimitReachedException
import com.whatsapp.webclient.accounts.MultiProfileUnsupportedException
import com.whatsapp.webclient.accounts.WhatsAppAccount
import com.whatsapp.webclient.accounts.WhatsAppAccountStore
import com.whatsapp.webclient.accounts.WebViewProfileManager
import com.whatsapp.webclient.security.SecurityStore
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WhatsAppWebClient"
        /** Jeda di latar belakang sebelum aplikasi terkunci lagi. */
        private const val BACKGROUND_LOCK_MILLIS = 30_000L
        private const val MAX_BRIDGE_MESSAGE_LENGTH = 2_048
        private const val MAX_UNREAD_COUNT = 1_000_000

        /** Dipakai service untuk tahu apakah Activity masih ada untuk update badge. */
        @Volatile
        var isAlive: Boolean = false
            private set
    }

    private lateinit var securityStore: SecurityStore
    private lateinit var accountStore: WhatsAppAccountStore
    private lateinit var lockLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    private var currentWebView: WebView? = null
    private var currentAccount: WhatsAppAccount? = null
    private var badgeHookJs: String? = null
    private var authenticated = false
    private var lockScreenLaunched = false
    private var activityResumed = false

    /** Saat aplikasi terakhir meninggalkan layar; 0 kalau masih dipakai atau sudah terkunci. */
    private var leftForegroundAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        isAlive = true

        securityStore = SecurityStore()
        accountStore = WhatsAppAccountStore()
        badgeHookJs = runCatching {
            assets.open("badge_hook.js").bufferedReader().use { it.readText() }
        }.getOrNull()

        lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            lockScreenLaunched = false
            if (result.resultCode == RESULT_OK) {
                authenticated = true
                onAuthenticated()
            } else {
                // Never reveal the underlying WebView after a canceled lock/setup screen.
                authenticated = false
                currentWebView?.visibility = View.INVISIBLE
                finish()
            }
        }

        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) UnreadBadgeManager.onPermissionGranted(this)
            }

        findViewById<Button>(R.id.btnSelectAccount).setOnClickListener { showAccountPicker() }
        findViewById<Button>(R.id.btnRenameAccount).setOnClickListener { promptRenameAccount() }
        findViewById<Button>(R.id.btnAddAccount).setOnClickListener { promptAddAccount() }
        findViewById<Button>(R.id.btnDeleteAccount).setOnClickListener { confirmDeleteActiveAccount() }

        launchLockScreen()
    }

    override fun onStart() {
        super.onStart()
        // Jam kunci hanya berjalan saat aplikasi tidak di layar, jadi dipakai pun tidak
        // pernah menyela dengan PIN.
        if (authenticated && leftForegroundAt != 0L) {
            if (SystemClock.elapsedRealtime() - leftForegroundAt >= BACKGROUND_LOCK_MILLIS) {
                requireUnlock()
            }
            leftForegroundAt = 0L
        }
    }

    override fun onStop() {
        super.onStop()
        if (authenticated) leftForegroundAt = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        UnreadBadgeManager.refresh(this)
        if (!authenticated) launchLockScreen()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlive = false
        BadgeKeepAliveService.stop(this)
        destroyCurrentWebView()
    }

    private fun launchLockScreen() {
        if (lockScreenLaunched || isFinishing) return
        lockScreenLaunched = true
        lockLauncher.launch(LockActivity.createIntent(this, setup = !securityStore.isPinSet()))
    }

    private fun onAuthenticated() {
        findViewById<LinearLayout>(R.id.accountBar).visibility = View.VISIBLE
        runCatching { accountStore.ensureInitialAccount() }
            .onFailure {
                Log.e(TAG, "Could not load encrypted account data", it)
                showStatus(getString(R.string.account_storage_error))
                return
            }

        UnreadBadgeManager.createChannel(this)
        UnreadBadgeManager.requestNotificationPermissionIfNeeded(this, notificationPermissionLauncher)
        BadgeKeepAliveService.start(this)
        renderActiveAccount()
    }

    private fun requireUnlock() {
        if (!authenticated || isFinishing) return
        authenticated = false
        currentWebView?.visibility = View.INVISIBLE
        findViewById<LinearLayout>(R.id.accountBar).visibility = View.GONE
        if (activityResumed) launchLockScreen()
    }

    private fun renderActiveAccount() {
        val account = runCatching {
            accountStore.getActiveAccount() ?: accountStore.ensureInitialAccount()
        }.getOrElse {
            Log.e(TAG, "Could not read account list", it)
            destroyCurrentWebView()
            showStatus(getString(R.string.account_storage_error))
            return
        }
        currentAccount = account
        findViewById<Button>(R.id.btnSelectAccount).text = account.displayName
        UnreadBadgeManager.update(this, 0)
        destroyCurrentWebView()

        if (account.profileName != Profile.DEFAULT_PROFILE_NAME &&
            !WebViewProfileManager.supportsMultiProfile()
        ) {
            showStatus(getString(R.string.profile_isolation_required))
            return
        }

        val webView = WebView(this)
        try {
            // Profile must be assigned before touching settings, clients, or loading a URL.
            WebViewProfileManager.attachProfile(webView, account.profileName)
            configureWebView(webView)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create isolated WebView profile", e)
            runCatching { webView.destroy() }
            showStatus(getString(R.string.profile_open_error))
            return
        }

        val container = findViewById<FrameLayout>(R.id.webViewContainer)
        container.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        currentWebView = webView
        findViewById<TextView>(R.id.tvStatus).visibility = View.GONE
        findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        webView.loadUrl(WhatsAppApp.WHATSAPP_WEB_URL)
    }

    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            // WhatsApp Web menolak UA ponsel (halaman "buka aplikasi", tanpa QR/kode tautan).
            userAgentString = WhatsAppApp.DESKTOP_USER_AGENT
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowUniversalAccessFromFileURLs = false
            allowFileAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }

        webView.webViewClient = SecureWebViewClient(
            onLoadingChanged = { loading ->
                findViewById<ProgressBar>(R.id.progressBar)?.visibility =
                    if (loading) View.VISIBLE else View.GONE
            },
            onPageStarted = { view -> injectBadgeHook(view) },
            onPageFinished = { view -> injectBadgeHook(view) }
        )
        webView.webChromeClient = SecureWebChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            DownloadManager(this).download(url, userAgent, contentDisposition, mimetype)
        }
        installBadgeMessageListener(webView)
    }

    /** Uses an origin- and frame-restricted message listener instead of addJavascriptInterface. */
    private fun installBadgeMessageListener(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "WebMessageListener unsupported; native unread badge is disabled")
            return
        }
        WebViewCompat.addWebMessageListener(
            webView,
            "WhatsAppBridge",
            setOf("https://web.whatsapp.com"),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    if (!isMainFrame || sourceOrigin.scheme != "https" ||
                        sourceOrigin.host != "web.whatsapp.com" ||
                        message.type != WebMessageCompat.TYPE_STRING
                    ) return

                    val raw = message.data ?: return
                    if (raw.length > MAX_BRIDGE_MESSAGE_LENGTH) return
                    val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return
                    when (payload.optString("type")) {
                        "unread" -> {
                            val count = payload.optInt("count", -1)
                            if (count in 0..MAX_UNREAD_COUNT) {
                                UnreadBadgeManager.update(applicationContext, count)
                            }
                        }
                        "debug" -> if (BuildConfig.DEBUG) {
                            Log.d("BadgeHook", payload.optString("message").take(200))
                        }
                    }
                }
            }
        )
    }

    private fun injectBadgeHook(view: WebView) {
        badgeHookJs?.takeIf { it.isNotEmpty() }?.let { view.evaluateJavascript(it, null) }
    }

    private fun showAccountPicker() {
        val accounts = runCatching { accountStore.getAccounts() }.getOrElse {
            showStatus(getString(R.string.account_storage_error))
            return
        }
        if (accounts.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_account)
            .setItems(accounts.map { it.displayName }.toTypedArray()) { _, index ->
                val selected = accounts[index]
                if (selected.id != currentAccount?.id) {
                    runCatching { accountStore.setActiveAccount(selected.id) }
                        .onSuccess { renderActiveAccount() }
                        .onFailure { showStatus(getString(R.string.account_storage_error)) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptAddAccount() {
        val accounts = runCatching { accountStore.getAccounts() }.getOrElse {
            showStatus(getString(R.string.account_storage_error))
            return
        }
        val supportsProfiles = WebViewProfileManager.supportsMultiProfile()
        if (accounts.isNotEmpty() && !supportsProfiles) {
            Toast.makeText(this, R.string.profile_isolation_required, Toast.LENGTH_LONG).show()
            return
        }

        if (accounts.size >= WhatsAppAccountStore.MAX_ACCOUNTS) {
            Toast.makeText(
                this,
                getString(R.string.account_limit_reached, WhatsAppAccountStore.MAX_ACCOUNTS),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val nameInput = layoutInflater.inflate(R.layout.dialog_add_account, null)
        val input = nameInput.findViewById<TextInputEditText>(R.id.etAccountName)
        input.setText(getString(R.string.account_default_name, accounts.size + 1))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_account)
            .setMessage(R.string.add_account_description)
            .setView(nameInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add_account) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.account_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runCatching { accountStore.createAccount(name, supportsProfiles) }
                    .onSuccess { renderActiveAccount() }
                    .onFailure { error ->
                        if (error is MultiProfileUnsupportedException) {
                            Toast.makeText(this, R.string.profile_isolation_required, Toast.LENGTH_LONG).show()
                        } else if (error is AccountLimitReachedException) {
                            Toast.makeText(
                                this,
                                getString(R.string.account_limit_reached, error.limit),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(this, R.string.account_storage_error, Toast.LENGTH_LONG).show()
                        }
                    }
            }
            .show()
    }

    private fun promptRenameAccount() {
        val account = currentAccount ?: return
        val nameInput = layoutInflater.inflate(R.layout.dialog_add_account, null)
        val input = nameInput.findViewById<TextInputEditText>(R.id.etAccountName)
        input.setText(account.displayName)
        input.setSelection(account.displayName.length)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_account)
            .setView(nameInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename_account) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.account_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runCatching { accountStore.renameAccount(account.id, name) }
                    .onSuccess { renamed ->
                        // Only the label changes here: renderActiveAccount() would destroy and
                        // rebuild the WebView, reloading the page the user is looking at.
                        currentAccount = renamed
                        findViewById<Button>(R.id.btnSelectAccount).text = renamed.displayName
                    }
                    .onFailure { showStatus(getString(R.string.account_storage_error)) }
            }
            .show()
    }

    private fun confirmDeleteActiveAccount() {
        val account = currentAccount ?: return
        val count = runCatching { accountStore.getAccounts().size }.getOrDefault(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_account)
            .setMessage(getString(R.string.delete_account_confirmation, account.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_account) { _, _ ->
                deleteAccount(account, count)
            }
            .show()
    }

    private fun deleteAccount(account: WhatsAppAccount, previousCount: Int) {
        // Destroy the active WebView before asking WebView to remove its profile.
        destroyCurrentWebView()
        val wipeRequested = WebViewProfileManager.deleteProfile(this, account.profileName)
        val remaining = runCatching { accountStore.removeAccount(account.id) }.getOrElse {
            Log.e(TAG, "Could not remove encrypted account metadata", it)
            showStatus(getString(R.string.account_storage_error))
            return
        }
        if (remaining.isEmpty()) {
            runCatching { accountStore.createAccount(getString(R.string.account_default_name, 1), false) }
                .onFailure {
                    Log.e(TAG, "Could not create a fresh default account", it)
                    showStatus(getString(R.string.account_storage_error))
                    return
                }
        }
        currentAccount = null
        if (wipeRequested) {
            Toast.makeText(this, R.string.account_removed, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.account_removed_wipe_warning, Toast.LENGTH_LONG).show()
        }
        if (previousCount == 1) {
            findViewById<Button>(R.id.btnSelectAccount).text = getString(R.string.account_default_name, 1)
        }
        renderActiveAccount()
    }

    private fun showStatus(message: String) {
        destroyCurrentWebView()
        findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
        findViewById<TextView>(R.id.tvStatus).apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    private fun destroyCurrentWebView() {
        val webView = currentWebView ?: return
        currentWebView = null
        findViewById<FrameLayout>(R.id.webViewContainer).removeView(webView)
        runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface("WhatsAppBridge")
            webView.webChromeClient = null
            webView.setDownloadListener(null)
            webView.clearHistory()
            webView.destroy()
        }.onFailure { Log.w(TAG, "WebView cleanup failed", it) }
    }

}
