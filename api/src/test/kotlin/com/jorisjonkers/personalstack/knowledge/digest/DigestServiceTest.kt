package com.jorisjonkers.personalstack.knowledge.digest

import com.jorisjonkers.personalstack.knowledge.domain.TagSummary
import com.jorisjonkers.personalstack.knowledge.domain.Topic
import com.jorisjonkers.personalstack.knowledge.repo.DiscoveryRepository
import com.jorisjonkers.personalstack.knowledge.repo.TopicRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

/**
 * Unit tests for the Reflexion-style session-digest service. We
 * stub the Ollama call so the tests run instantly + offline; the
 * actual Ollama wire format is covered separately by the integration
 * suite once the bigger stack lands.
 */
class DigestServiceTest {
    private val ollama = mockk<OllamaDigestClient>()
    private val topicRepository = mockk<TopicRepository>()
    private val discoveryRepository = mockk<DiscoveryRepository>()
    private val mapper: JsonMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val service = DigestService(ollama, topicRepository, discoveryRepository)

    private fun fixture() {
        every { topicRepository.listActive() } returns
            listOf(
                Topic(
                    slug = "claude-code",
                    description = "",
                    aliases = emptySet(),
                    createdAt = Instant.EPOCH,
                    createdBy = "seed",
                    updatedAt = Instant.EPOCH,
                    isActive = true,
                ),
            )
        every { discoveryRepository.listTags(any(), any()) } returns
            listOf(TagSummary(tag = "hooks", count = 4, lastUsedAt = null))
    }

    private fun stubOllama(jsonBody: String) {
        every { ollama.chatJson(any(), any(), any()) } returns mapper.readTree(jsonBody)
    }

    @Test
    fun `digest returns empty on a blank transcript without calling ollama`() {
        val out = service.digest("   ", 5, 0.5)
        assertThat(out).isEmpty()
    }

    @Test
    fun `digest filters candidates below the confidence floor`() {
        fixture()
        stubOllama(
            """
            {"candidates":[
              {"kind":"lesson","title":"good","body":"long enough body for the policy floor",
               "suggested_topic":"claude-code","suggested_tags":["hooks"],
               "confidence":0.8,"relevant_excerpts":["a"]},
              {"kind":"lesson","title":"meh","body":"long enough body for the policy floor",
               "suggested_topic":null,"suggested_tags":[],
               "confidence":0.2,"relevant_excerpts":[]}
            ]}
            """.trimIndent(),
        )

        val out = service.digest("some transcript", 5, 0.5)

        assertThat(out).hasSize(1)
        assertThat(out[0].title).isEqualTo("good")
        assertThat(out[0].suggestedTopic).isEqualTo("claude-code")
        assertThat(out[0].suggestedTags).containsExactly("hooks")
    }

    @Test
    fun `digest caps to max_candidates after the confidence filter`() {
        fixture()
        val candidates =
            (1..7)
                .joinToString(",") { i ->
                    """
                    {"kind":"lesson","title":"long enough title $i",
                     "body":"body long enough to pass the policy floor",
                     "suggested_topic":null,"suggested_tags":[],
                     "confidence":0.9,"relevant_excerpts":[]}
                    """.trimIndent()
                }
        stubOllama("""{"candidates":[$candidates]}""")

        val out = service.digest("transcript", 3, 0.5)
        assertThat(out).hasSize(3)
    }

    @Test
    fun `digest drops candidates that fail the structural floors`() {
        fixture()
        stubOllama(
            """
            {"candidates":[
              {"kind":"lesson","title":"x","body":"this body is long enough to pass",
               "suggested_topic":null,"suggested_tags":[],
               "confidence":0.9,"relevant_excerpts":[]},
              {"kind":"lesson","title":"ok title","body":"too short",
               "suggested_topic":null,"suggested_tags":[],
               "confidence":0.9,"relevant_excerpts":[]},
              {"kind":"madeup","title":"weird kind","body":"this body is long enough to pass",
               "suggested_topic":null,"suggested_tags":[],
               "confidence":0.9,"relevant_excerpts":[]}
            ]}
            """.trimIndent(),
        )

        val out = service.digest("transcript", 5, 0.0)
        // "x" is below MIN_TITLE_LENGTH, the second is below MIN_BODY_LENGTH,
        // the third has a kind not in {lesson, decision, note, fact}.
        assertThat(out).isEmpty()
    }

    @Test
    fun `digest tolerates an Ollama call failure and returns empty`() {
        fixture()
        every { ollama.chatJson(any(), any(), any()) } throws IllegalStateException("connect timeout")
        val out = service.digest("transcript", 5, 0.0)
        assertThat(out).isEmpty()
    }

    @Test
    fun `digest passes the topic vocabulary into the system prompt`() {
        fixture()
        stubOllama("""{"candidates":[]}""")
        val systemPromptArg = slot<String>()
        every { ollama.chatJson(capture(systemPromptArg), any(), any()) } answers
            { mapper.readTree("""{"candidates":[]}""") }
        service.digest("transcript", 5, 0.5)
        assertThat(systemPromptArg.captured).contains("claude-code")
    }
}
