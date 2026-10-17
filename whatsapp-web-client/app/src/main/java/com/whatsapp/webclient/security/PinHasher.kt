package com.whatsapp.webclient.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Slow, salted PIN derivation. The result and salt are stored in encrypted preferences. */
object PinHasher {
    const val PIN_LENGTH = 6
    const val SALT_BYTES = 16
    const val ITERATIONS = 120_000
    const val KEY_BITS = 256

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SALT_BYTES).also(random::nextBytes)

    fun derive(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(pin.length == PIN_LENGTH && pin.all { it.isDigit() }) { "PIN must contain exactly 6 digits" }
        require(salt.size >= SALT_BYTES) { "PIN salt is too short" }
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }
}
