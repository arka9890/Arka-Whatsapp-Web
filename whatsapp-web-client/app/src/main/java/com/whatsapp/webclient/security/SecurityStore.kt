package com.whatsapp.webclient.security


import android.util.Base64
import com.whatsapp.webclient.WhatsAppApp
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class PinCheckResult {
    object Success : PinCheckResult()
    data class Invalid(val attemptsRemaining: Int) : PinCheckResult()
    data class LockedOut(val remainingMillis: Long) : PinCheckResult()
}

/** App-lock state. PIN material and lockout state are kept in Keystore-backed preferences. */
class SecurityStore {
    private val prefs get() = WhatsAppApp.securePrefs()

    fun isPinSet(): Boolean = prefs.contains(WhatsAppApp.KEY_PIN_HASH) &&
        prefs.contains(WhatsAppApp.KEY_PIN_SALT)

    fun setPin(pin: String) {
        require(pin.length == PinHasher.PIN_LENGTH && pin.all { it.isDigit() }) {
            "PIN must contain exactly 6 digits"
        }
        val salt = PinHasher.newSalt()
        val hash = PinHasher.derive(pin, salt)
        val saved = prefs.edit()
            .putString(WhatsAppApp.KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(WhatsAppApp.KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove(WhatsAppApp.KEY_PIN_FAILURES)
            .remove(WhatsAppApp.KEY_PIN_LOCKOUT_UNTIL)
            // Remove the legacy raw-PIN field from earlier prototype builds.
            .remove("pin_hash")
            .commit()
        check(saved) { "Could not save the app PIN" }
    }

    fun verifyPin(pin: String): PinCheckResult {
        val remaining = lockoutRemainingMillis()
        if (remaining > 0L) return PinCheckResult.LockedOut(remaining)
        if (pin.length != PinHasher.PIN_LENGTH || !pin.all { it.isDigit() }) {
            return PinCheckResult.Invalid(attemptsRemaining())
        }

        val saltText = prefs.getString(WhatsAppApp.KEY_PIN_SALT, null)
            ?: return PinCheckResult.Invalid(attemptsRemaining())
        val hashText = prefs.getString(WhatsAppApp.KEY_PIN_HASH, null)
            ?: return PinCheckResult.Invalid(attemptsRemaining())

        val expected = runCatching { Base64.decode(hashText, Base64.NO_WRAP) }.getOrNull()
            ?: return PinCheckResult.Invalid(attemptsRemaining())
        val salt = runCatching { Base64.decode(saltText, Base64.NO_WRAP) }.getOrNull()
            ?: return PinCheckResult.Invalid(attemptsRemaining())
        val supplied = runCatching { PinHasher.derive(pin, salt) }.getOrNull()
            ?: return PinCheckResult.Invalid(attemptsRemaining())

        if (MessageDigest.isEqual(expected, supplied)) {
            prefs.edit()
                .remove(WhatsAppApp.KEY_PIN_FAILURES)
                .remove(WhatsAppApp.KEY_PIN_LOCKOUT_UNTIL)
                .apply()
            return PinCheckResult.Success
        }

        val failures = prefs.getInt(WhatsAppApp.KEY_PIN_FAILURES, 0) + 1
        if (failures >= MAX_FAILURES) {
            val until = System.currentTimeMillis() + LOCKOUT_MILLIS
            check(
                prefs.edit()
                    .putInt(WhatsAppApp.KEY_PIN_FAILURES, MAX_FAILURES)
                    .putLong(WhatsAppApp.KEY_PIN_LOCKOUT_UNTIL, until)
                    .commit()
            ) { "Could not persist PIN lockout" }
            return PinCheckResult.LockedOut(LOCKOUT_MILLIS)
        }

        check(prefs.edit().putInt(WhatsAppApp.KEY_PIN_FAILURES, failures).commit()) {
            "Could not persist PIN failure count"
        }
        return PinCheckResult.Invalid(MAX_FAILURES - failures)
    }

    fun lockoutRemainingMillis(): Long {
        val until = prefs.getLong(WhatsAppApp.KEY_PIN_LOCKOUT_UNTIL, 0L)
        val remaining = (until - System.currentTimeMillis()).coerceAtLeast(0L)
        if (until != 0L && remaining == 0L) {
            prefs.edit()
                .remove(WhatsAppApp.KEY_PIN_LOCKOUT_UNTIL)
                .remove(WhatsAppApp.KEY_PIN_FAILURES)
                .apply()
        }
        return remaining
    }

    private fun attemptsRemaining(): Int =
        (MAX_FAILURES - prefs.getInt(WhatsAppApp.KEY_PIN_FAILURES, 0)).coerceAtLeast(0)

    companion object {
        const val PIN_LENGTH = PinHasher.PIN_LENGTH
        const val MAX_FAILURES = 5
        val LOCKOUT_MILLIS: Long = TimeUnit.MINUTES.toMillis(5)
    }
}
