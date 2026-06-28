package com.jorisjonkers.personalstack.knowledge.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class UlidTest {
    @Test
    fun `generate returns a 26-char Crockford base32 string`() {
        val id = Ulid.generate()
        assertThat(id).hasSize(26)
        assertThat(id).matches("[0-9A-HJKMNP-TV-Z]{26}")
    }

    @Test
    fun `generate uses the supplied instant as the lexicographic prefix`() {
        val earlier = Instant.parse("2024-01-01T00:00:00Z")
        val later = Instant.parse("2026-05-13T00:00:00Z")

        val a = Ulid.generate(earlier)
        val b = Ulid.generate(later)

        // First 10 chars encode the 48-bit timestamp. `earlier` < `later`
        // means the prefix of `a` must sort before the prefix of `b`.
        assertThat(a.substring(0, 10)).isLessThan(b.substring(0, 10))
    }

    @Test
    fun `two ULIDs at the same instant differ in the random suffix`() {
        val at = Instant.parse("2026-05-13T12:00:00Z")
        val ids = (1..16).map { Ulid.generate(at) }

        // All 16 share a timestamp prefix
        val prefix = ids.first().substring(0, 10)
        assertThat(ids).allMatch { it.startsWith(prefix) }
        // …but the random suffixes are unique (collision probability is
        // astronomically small; flake-resilient).
        assertThat(ids.map { it.substring(10) }.toSet()).hasSize(ids.size)
    }

    @Test
    fun `generate rejects a negative timestamp`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy { Ulid.generate(Instant.ofEpochMilli(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
