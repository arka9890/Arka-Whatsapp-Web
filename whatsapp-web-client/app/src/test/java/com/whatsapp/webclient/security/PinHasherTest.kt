package com.whatsapp.webclient.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun samePinAndSaltDeriveSameValue() {
        val salt = ByteArray(PinHasher.SALT_BYTES) { it.toByte() }
        assertArrayEquals(PinHasher.derive("123456", salt), PinHasher.derive("123456", salt))
    }

    @Test
    fun differentSaltProducesDifferentValue() {
        val firstSalt = ByteArray(PinHasher.SALT_BYTES) { it.toByte() }
        val secondSalt = ByteArray(PinHasher.SALT_BYTES) { (it + 1).toByte() }
        assertNotEquals(
            PinHasher.derive("123456", firstSalt).toList(),
            PinHasher.derive("123456", secondSalt).toList()
        )
    }

    @Test
    fun derivationDoesNotReturnThePin() {
        val result = PinHasher.derive("123456", ByteArray(PinHasher.SALT_BYTES) { 7 })
        assertFalse(result.contentEquals("123456".toByteArray()))
        assertTrue(result.size >= 32)
    }
}
