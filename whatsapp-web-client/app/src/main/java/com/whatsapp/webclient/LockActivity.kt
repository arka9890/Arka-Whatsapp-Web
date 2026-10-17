package com.whatsapp.webclient

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.whatsapp.webclient.security.PinCheckResult
import com.whatsapp.webclient.security.SecurityStore
import java.util.concurrent.Executor

/** PIN enrollment and app-unlock screen. The protected WebView is not created until this succeeds. */
class LockActivity : AppCompatActivity() {

    private lateinit var securityStore: SecurityStore
    private lateinit var title: TextView
    private lateinit var pinLayout: TextInputLayout
    private lateinit var pinInput: TextInputEditText
    private lateinit var unlockButton: Button
    private lateinit var biometricButton: Button
    private lateinit var errorText: TextView

    private var setupMode = false
    private var firstPin: String? = null
    private var canUseBiometric = false
    private var biometricPrompt: BiometricPrompt? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lockoutTicker: Runnable? = null
    private val executor: Executor by lazy { ContextCompat.getMainExecutor(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        securityStore = SecurityStore()
        setupMode = intent.getBooleanExtra(EXTRA_SETUP, !securityStore.isPinSet())
        title = findViewById(R.id.tvTitle)
        pinLayout = findViewById(R.id.tilPin)
        pinInput = findViewById(R.id.etPin)
        unlockButton = findViewById(R.id.btnUnlock)
        biometricButton = findViewById(R.id.btnBiometric)
        errorText = findViewById(R.id.tvError)

        unlockButton.setOnClickListener { submitPin() }
        biometricButton.setOnClickListener { startBiometricPrompt() }

        canUseBiometric = !setupMode &&
            BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

        if (setupMode) {
            showPinEntry()
        } else if (canUseBiometric && securityStore.lockoutRemainingMillis() == 0L) {
            startBiometricPrompt()
        } else {
            showPinEntry()
        }
        refreshLockoutState()
    }

    private fun showPinEntry() {
        title.text = when {
            setupMode && firstPin == null -> getString(R.string.pin_create_title)
            setupMode -> getString(R.string.pin_confirm_title)
            else -> getString(R.string.pin_unlock_title)
        }
        pinLayout.visibility = View.VISIBLE
        unlockButton.visibility = View.VISIBLE
        biometricButton.visibility = if (canUseBiometric && !setupMode) View.VISIBLE else View.GONE
        errorText.visibility = View.GONE
        pinInput.setText("")
        pinInput.requestFocus()
    }

    private fun submitPin() {
        val pin = pinInput.text?.toString().orEmpty()
        if (pin.length != SecurityStore.PIN_LENGTH || !pin.all { it.isDigit() }) {
            showError(getString(R.string.pin_length_error))
            return
        }

        if (setupMode) {
            val previous = firstPin
            if (previous == null) {
                firstPin = pin
                showPinEntry()
                return
            }
            if (pin != previous) {
                firstPin = null
                showPinEntry()
                showError(getString(R.string.pin_mismatch))
                return
            }
            runCatching { securityStore.setPin(pin) }
                .onSuccess { finishUnlock() }
                .onFailure { showError(getString(R.string.pin_save_error)) }
            return
        }

        when (val result = securityStore.verifyPin(pin)) {
            PinCheckResult.Success -> finishUnlock()
            is PinCheckResult.Invalid -> {
                showError(getString(R.string.pin_incorrect_attempts, result.attemptsRemaining))
                pinInput.setText("")
            }
            is PinCheckResult.LockedOut -> {
                pinInput.setText("")
                showLockout(result.remainingMillis)
            }
        }
    }

    private fun startBiometricPrompt() {
        if (setupMode || !canUseBiometric) {
            showPinEntry()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                finishUnlock()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                // Cancellation and the explicit negative button both fall back to app PIN.
                showPinEntry()
            }
        })
        biometricPrompt?.authenticate(info)
    }

    private fun showLockout(remainingMillis: Long) {
        pinLayout.visibility = View.VISIBLE
        unlockButton.visibility = View.VISIBLE
        biometricButton.visibility = if (canUseBiometric) View.VISIBLE else View.GONE
        unlockButton.isEnabled = false
        errorText.visibility = View.VISIBLE
        lockoutTicker?.let(handler::removeCallbacks)

        val endAt = System.currentTimeMillis() + remainingMillis
        val ticker = object : Runnable {
            override fun run() {
                val remaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
                if (remaining == 0L) {
                    unlockButton.isEnabled = true
                    errorText.visibility = View.GONE
                    lockoutTicker = null
                } else {
                    val seconds = (remaining + 999L) / 1000L
                    errorText.text = getString(R.string.pin_lockout, seconds)
                    lockoutTicker = this
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        lockoutTicker = ticker
        ticker.run()
    }

    private fun refreshLockoutState() {
        val remaining = securityStore.lockoutRemainingMillis()
        if (remaining > 0L) showLockout(remaining)
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun finishUnlock() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(pinInput.windowToken, 0)
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        lockoutTicker?.let(handler::removeCallbacks)
    }

    companion object {
        const val EXTRA_SETUP = "setup_pin"

        fun createIntent(context: Context, setup: Boolean): android.content.Intent =
            android.content.Intent(context, LockActivity::class.java)
                .putExtra(EXTRA_SETUP, setup)
    }
}
