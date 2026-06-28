package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ReciprocalRankFusionTest {
    private fun hit(id: String) =
        RecallHit(
            id = id,
            type = "lesson",
            scope = "personal",
            title = "t-$id",
            snippet = "s",
            score = 0.0,
        )

    @Test
    fun `id appearing in both legs beats id appearing in only one`() {
        val fts = listOf(hit("A"), hit("B"))
        val vector = listOf(hit("C"), hit("A"))

        // A: 1/(60+1) + 1/(60+2) = ~0.0327
        // B: 1/(60+2)            = ~0.0161
        // C: 1/(60+1)            = ~0.0164
        val fused = ReciprocalRankFusion.fuse(listOf(fts, vector), k = 60, limit = 10)

        assertThat(fused.map { it.id }).containsExactly("A", "C", "B")
    }

    @Test
    fun `single-leg call preserves rank order`() {
        val fts = listOf(hit("A"), hit("B"), hit("C"))
        val fused = ReciprocalRankFusion.fuse(listOf(fts, emptyList()), k = 60, limit = 10)
        assertThat(fused.map { it.id }).containsExactly("A", "B", "C")
    }

    @Test
    fun `limit is honoured after fusion`() {
        val fts = listOf(hit("A"), hit("B"), hit("C"), hit("D"))
        val fused = ReciprocalRankFusion.fuse(listOf(fts), k = 60, limit = 2)
        assertThat(fused).hasSize(2)
    }

    @Test
    fun `empty inputs yield empty output without exploding`() {
        val fused = ReciprocalRankFusion.fuse(listOf(emptyList(), emptyList()), k = 60, limit = 10)
        assertThat(fused).isEmpty()
    }

    @Test
    fun `survivor carries the fused score on the projected hit`() {
        val fts = listOf(hit("A"))
        val vector = listOf(hit("A"))
        val fused = ReciprocalRankFusion.fuse(listOf(fts, vector), k = 60, limit = 1)

        assertThat(fused).hasSize(1)
        // Both at rank 1: 2 * 1/(60+1) = 2/61 ≈ 0.0328
        assertThat(fused.first().score).isCloseTo(2.0 / 61.0, within(1e-6))
    }
}
