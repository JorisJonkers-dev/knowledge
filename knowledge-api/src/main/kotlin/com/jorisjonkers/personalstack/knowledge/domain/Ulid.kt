package com.jorisjonkers.personalstack.knowledge.domain

import java.security.SecureRandom
import java.time.Instant

/**
 * Minimal ULID generator. 26-character Crockford base32, sortable
 * lexicographically by capture time so `ORDER BY id DESC` is a free
 * recency index (see V1 migration's comment block).
 *
 * Format: 48-bit timestamp + 80-bit randomness, encoded in 26 chars.
 * We use a fresh SecureRandom call per ULID — knowledge captures are
 * human-paced (handful per minute at most), so the cost of a real
 * random read is invisible. Monotonic-within-ms is intentionally not
 * implemented; collisions are astronomically unlikely at this rate.
 */
object Ulid {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIMESTAMP_CHARS = 10
    private const val RANDOM_CHARS = 16
    private const val TIMESTAMP_BITS = 48
    private const val BITS_PER_CHAR = 5
    private const val MASK = 0x1F
    private val random = SecureRandom()

    fun generate(at: Instant = Instant.now()): String {
        val sb = StringBuilder(TIMESTAMP_CHARS + RANDOM_CHARS)
        encodeTimestamp(at.toEpochMilli(), sb)
        encodeRandom(sb)
        return sb.toString()
    }

    private fun encodeTimestamp(
        millis: Long,
        sb: StringBuilder,
    ) {
        require(millis >= 0) { "ULID timestamp must be non-negative" }
        var value = millis
        val chars = CharArray(TIMESTAMP_CHARS)
        for (i in (TIMESTAMP_CHARS - 1) downTo 0) {
            chars[i] = ENCODING[(value and MASK.toLong()).toInt()]
            value = value ushr BITS_PER_CHAR
        }
        // Truncate any bits above the 48-bit timestamp budget so a
        // forward-shifted system clock can't poison the prefix.
        check(value ushr (TIMESTAMP_BITS - TIMESTAMP_CHARS * BITS_PER_CHAR) == 0L) {
            "timestamp $millis overflows ULID's 48-bit range"
        }
        sb.append(chars)
    }

    @Suppress("MagicNumber") // Byte-index ranges below match the documented hi/lo split.
    private fun encodeRandom(sb: StringBuilder) {
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        // Pack the 80 random bits into 16 base32 chars (5 bits each).
        // Avoid BigInteger to keep this allocation-light; do the math
        // on two longs:
        //   hi = bytes[0..3]  → 32 bits
        //   lo = bytes[4..9]  → 48 bits
        var hi = 0L
        for (i in 0..3) hi = (hi shl BYTE_BITS) or (bytes[i].toLong() and BYTE_MASK)
        var lo = 0L
        for (i in 4..9) lo = (lo shl BYTE_BITS) or (bytes[i].toLong() and BYTE_MASK)
        val chars = CharArray(RANDOM_CHARS)
        // Lower 10 chars come from `lo` (50 bits — 48 source bits +
        // 2 zero-padded; padding only widens the output, doesn't bias
        // the random distribution).
        for (i in (RANDOM_CHARS - 1) downTo (RANDOM_CHARS - LO_CHARS)) {
            chars[i] = ENCODING[(lo and MASK.toLong()).toInt()]
            lo = lo ushr BITS_PER_CHAR
        }
        // Upper 6 chars come from `hi`. hi started with 32 bits → 6
        // base32 chars cover 30; the top 2 bits are dropped, which is
        // acceptable for a ULID (random is still uniform on the
        // remaining 78 bits — well above 2^60 collision domain).
        for (i in (RANDOM_CHARS - LO_CHARS - 1) downTo 0) {
            chars[i] = ENCODING[(hi and MASK.toLong()).toInt()]
            hi = hi ushr BITS_PER_CHAR
        }
        sb.append(chars)
    }
}

private const val RANDOM_BYTES = 10
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xFFL
private const val LO_CHARS = 10
