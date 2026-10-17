package com.whatsapp.webclient

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Application-wide encrypted preferences backed by Android Keystore. */
class WhatsAppApp : Application() {

    companion object {
        private const val PREFS_NAME = "wa_webclient_secure"

        const val KEY_PIN_HASH = "app_pin_hash_v2"
        const val KEY_PIN_SALT = "app_pin_salt_v2"
        const val KEY_PIN_FAILURES = "app_pin_failures"
        const val KEY_PIN_LOCKOUT_UNTIL = "app_pin_lockout_until"
        const val KEY_ACCOUNT_LIST = "accounts_json_v1"
        const val KEY_ACTIVE_ACCOUNT = "active_account_id_v1"

        const val WHATSAPP_WEB_URL = "https://web.whatsapp.com"

        /**
         * WhatsApp Web menolak UA ponsel: yang muncul halaman "buka aplikasi WhatsApp", bukan
         * QR/kode tautan. Jadi WebView memakai UA Chrome desktop. Nilai ini dipakai untuk header
         * HTTP sekaligus `navigator.userAgent`, jadi deteksi di sisi skrip pun lolos.
         */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        @Volatile
        private var instance: WhatsAppApp? = null

        @Volatile
        private var cachedSecurePrefs: SharedPreferences? = null

        fun appContext(): Context = requireNotNull(instance) {
            "WhatsAppApp has not been initialized"
        }.applicationContext

        fun securePrefs(): SharedPreferences {
            cachedSecurePrefs?.let { return it }
            return synchronized(this) {
                cachedSecurePrefs ?: run {
                    val context = appContext()
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    ).also { cachedSecurePrefs = it }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
