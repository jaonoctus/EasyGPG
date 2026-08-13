package com.ngoline.easygpg

import android.text.Editable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * Buffers for secret input — passphrases, plaintext, decrypted key rings.
 *
 * The rule these helpers exist to enforce: read secret input into an array this app owns, use it,
 * and overwrite it in a `finally` block. Secrets are never turned into a [String], because a String
 * cannot be overwritten and lives until the garbage collector happens to reclaim it. This mirrors
 * OpenKeychain's `Passphrase.removeFromMemory()`.
 *
 * Heap scrubbing on Android is best effort: the garbage collector may have copied a buffer before it
 * was wiped, and Bouncy Castle and the framework keep copies of their own (parsed key rings, the
 * `Editable` behind an `EditText`, the `String` a selectable `TextView` copies its text into,
 * `TextView` layouts, the clipboard) that no app can reach. Wiping the buffers we do own still
 * removes the longest-lived, highest-value copies.
 */

/** Overwrites the characters of this buffer. */
fun CharArray.wipe() {
    fill(Char.MIN_VALUE)
}

/** Overwrites the bytes of this buffer. */
fun ByteArray.wipe() {
    fill(0)
}

/** Runs [block] with this buffer and wipes it afterwards, whatever happens. */
inline fun <T> CharArray.useThenWipe(block: (CharArray) -> T): T =
    try {
        block(this)
    } finally {
        wipe()
    }

/** Runs [block] with this buffer and wipes it afterwards, whatever happens. */
inline fun <T> ByteArray.useThenWipe(block: (ByteArray) -> T): T =
    try {
        block(this)
    } finally {
        wipe()
    }

/** Copies the text out of an input field into a buffer the caller owns and must wipe. */
fun Editable?.copyToCharArray(): CharArray {
    val text = this ?: return CharArray(0)
    val copy = CharArray(text.length)
    text.getChars(0, text.length, copy, 0)
    return copy
}

/** UTF-8 encodes without building a [String]; the caller owns and must wipe the result. */
fun CharArray.toUtf8Bytes(): ByteArray {
    val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(this))
    val bytes = ByteArray(encoded.remaining())
    encoded.get(bytes)
    // The encoder's own buffer holds the same secret; it is ours to wipe, the input is not.
    if (encoded.hasArray()) encoded.array().wipe()
    return bytes
}

/** UTF-8 decodes without building a [String]; the caller owns and must wipe the result. */
fun ByteArray.toUtf8Chars(): CharArray {
    val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(this))
    val chars = CharArray(decoded.remaining())
    decoded.get(chars)
    if (decoded.hasArray()) decoded.array().wipe()
    return chars
}

/**
 * A [ByteArrayOutputStream] whose accumulated bytes can be overwritten once they have been used —
 * the plain stream keeps them in a buffer callers cannot reach.
 *
 * Starts out large enough for a key ring, since a buffer that is discarded while growing can no
 * longer be wiped.
 */
class WipeableByteArrayOutputStream : ByteArrayOutputStream(INITIAL_CAPACITY) {

    fun wipe() {
        buf.wipe()
        count = 0
    }

    private companion object {
        const val INITIAL_CAPACITY = 32 * 1024
    }
}
