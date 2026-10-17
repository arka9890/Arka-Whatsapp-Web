package com.whatsapp.webclient.accounts

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Runtime-gated WebView profile support. A missing feature never falls back to shared cookies. */
object WebViewProfileManager {
    private const val TAG = "WebViewProfiles"

    fun supportsMultiProfile(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** Must be called immediately after constructing the WebView and before using its settings. */
    fun attachProfile(webView: WebView, profileName: String) {
        if (profileName == Profile.DEFAULT_PROFILE_NAME) return
        check(supportsMultiProfile()) { "Isolated WebView profiles are not supported" }
        WebViewCompat.setProfile(webView, profileName)
    }

    fun cookieManager(webView: WebView): CookieManager {
        return if (supportsMultiProfile()) {
            WebViewCompat.getProfile(webView).cookieManager
        } else {
            CookieManager.getInstance()
        }
    }

    /**
     * Requests deletion of a profile and falls back to clearing its cookies, web storage, and
     * HTTP cache if WebView still has the profile loaded in this process. WebView may finish
     * deleting profile files asynchronously.
     */
    fun deleteProfile(context: Context, profileName: String): Boolean {
        return if (profileName == Profile.DEFAULT_PROFILE_NAME) {
            clearDefaultProfile(context)
        } else {
            clearNamedProfile(context, profileName)
        }
    }

    private fun clearDefaultProfile(context: Context): Boolean {
        return runCatching {
            val cleanupView = WebView(context.applicationContext)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            cleanupView.clearCache(true)
            cleanupView.clearHistory()
            cleanupView.destroy()
            true
        }.onFailure { Log.w(TAG, "Could not completely clear the default WebView profile", it) }
            .getOrDefault(false)
    }

    private fun clearNamedProfile(context: Context, profileName: String): Boolean {
        if (!supportsMultiProfile()) return false
        val store = runCatching { ProfileStore.getInstance() }.getOrElse {
            Log.w(TAG, "Multi-profile store is unavailable", it)
            return false
        }

        // Prefer deleting the profile as a whole. This also removes profile-owned data that the
        // public CookieManager/WebStorage APIs do not expose.
        try {
            store.deleteProfile(profileName)
            return true
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Profile is still loaded; clearing its public storage APIs", e)
        } catch (e: Exception) {
            Log.w(TAG, "Profile deletion failed; trying a best-effort clear", e)
        }

        return runCatching {
            val cleanupView = WebView(context.applicationContext)
            WebViewCompat.setProfile(cleanupView, profileName)
            val profile = WebViewCompat.getProfile(cleanupView)
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
            cleanupView.clearCache(true)
            cleanupView.clearHistory()
            cleanupView.destroy()
            runCatching { store.deleteProfile(profileName) }
            true
        }.onFailure { Log.w(TAG, "Could not completely clear profile $profileName", it) }
            .getOrDefault(false)
    }
}
