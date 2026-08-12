package com.ngoline.easygpg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsTest {

    private val samples = listOf(
        "",
        "attack at dawn",
        "acentuação, ñ, ß",
        "emoji 🔐🗝️ and CJK 秘密",
        "line\nbreaks\tand " + Char.MIN_VALUE + " nul",
    )

    @Test
    fun `utf8 round trip preserves the text`() {
        for (sample in samples) {
            val chars = sample.toCharArray()
            val bytes = chars.toUtf8Bytes()
            assertArrayEquals("bytes for '$sample'", sample.toByteArray(Charsets.UTF_8), bytes)
            assertArrayEquals("chars for '$sample'", sample.toCharArray(), bytes.toUtf8Chars())
        }
    }

    @Test
    fun `utf8 encoding matches what a String would have produced`() {
        // The stored format must not change now that plaintext skips String.
        val message = "acentuação 🔐"
        assertArrayEquals(message.toByteArray(Charsets.UTF_8), message.toCharArray().toUtf8Bytes())
        assertEquals(message, String(message.toByteArray(Charsets.UTF_8).toUtf8Chars()))
    }

    @Test
    fun `utf8 conversions leave the input buffer alone`() {
        val chars = "secret".toCharArray()
        chars.toUtf8Bytes()
        assertArrayEquals("secret".toCharArray(), chars)

        val bytes = "secret".toByteArray(Charsets.UTF_8)
        bytes.toUtf8Chars()
        assertArrayEquals("secret".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `wipe overwrites every element`() {
        val chars = "passphrase".toCharArray()
        chars.wipe()
        assertArrayEquals(CharArray(10), chars)

        val bytes = ByteArray(8) { 0x41 }
        bytes.wipe()
        assertArrayEquals(ByteArray(8), bytes)
    }

    @Test
    fun `useThenWipe wipes after the block returns`() {
        val chars = "passphrase".toCharArray()
        val length = chars.useThenWipe { it.size }
        assertEquals(10, length)
        assertArrayEquals(CharArray(10), chars)

        val bytes = "keyring".toByteArray(Charsets.UTF_8)
        val size = bytes.useThenWipe { it.size }
        assertEquals(7, size)
        assertArrayEquals(ByteArray(7), bytes)
    }

    @Test
    fun `useThenWipe wipes when the block throws`() {
        val chars = "passphrase".toCharArray()
        var thrown = false
        try {
            chars.useThenWipe { error("boom") }
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
        assertArrayEquals(CharArray(10), chars)
    }

    @Test
    fun `wipeable stream overwrites what it accumulated`() {
        val secret = "-----BEGIN PGP PRIVATE KEY BLOCK-----".toByteArray(Charsets.UTF_8)
        val stream = WipeableByteArrayOutputStream()
        stream.write(secret)
        assertArrayEquals(secret, stream.toByteArray())

        val exposed = stream.toByteArray()
        assertNotEquals(0, exposed[0])
        stream.wipe()
        assertEquals(0, stream.size())
        assertArrayEquals(ByteArray(0), stream.toByteArray())
    }
}
