package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.audit.AuditService
import com.jorisjonkers.personalstack.knowledge.auth.AdminAuthorization
import com.jorisjonkers.personalstack.knowledge.capture.CaptureService
import com.jorisjonkers.personalstack.knowledge.digest.DigestService
import com.jorisjonkers.personalstack.knowledge.discovery.DiscoveryService
import com.jorisjonkers.personalstack.knowledge.discovery.TagClusterService
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.ScopeSummary
import com.jorisjonkers.personalstack.knowledge.domain.SourceSummary
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateCluster
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateMember
import com.jorisjonkers.personalstack.knowledge.domain.TagSummary
import com.jorisjonkers.personalstack.knowledge.domain.TopicStats
import com.jorisjonkers.personalstack.knowledge.domain.TopicSummary
import com.jorisjonkers.personalstack.knowledge.recall.RecallService
import com.jorisjonkers.personalstack.knowledge.repo.AuditRepository
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import com.jorisjonkers.personalstack.knowledge.repo.TopicRepository
import com.jorisjonkers.personalstack.knowledge.review.ReviewService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

class DiscoveryMcpToolsTest {
    private val discoveryService = mockk<DiscoveryService>(relaxed = true)
    private val tagClusterService = mockk<TagClusterService>(relaxed = true)
    private val tools = discoveryTools(discoveryService, tagClusterService)
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val stubNote =
        KbNote(
            id = "01HXYZ00000000000000000000",
            type = KbNoteType.LESSON,
            scope = "personal",
            source = "claude-code",
            capturedAt = Instant.parse("2026-05-13T12:00:00Z"),
            sessionId = null,
            confidence = 0.4,
            title = "title",
            body = "body",
            vaultPath = "personal/lesson/draft.md",
            vaultCommit = null,
        )

    @Test
    fun `list_tag_candidates projects each cluster with its members and canonical`() {
        every { tagClusterService.listTagCandidates(any(), any(), any()) } returns
            listOf(
                TagCandidateCluster(
                    members = listOf(TagCandidateMember("kotlin", 10), TagCandidateMember("Kotlin", 3)),
                    suggestedCanonical = "kotlin",
                    averageSimilarity = 0.97,
                ),
            )

        val out =
            tools.call(
                "knowledge.list_tag_candidates",
                mapper.readTree("""{"min_count":2,"threshold":0.9,"max_tags":50}"""),
            )!!

        val clusters = out["clusters"].stringKeyMapList()
        assertThat(clusters).hasSize(1)
        assertThat(clusters[0]["suggested_canonical"]).isEqualTo("kotlin")
        assertThat(clusters[0]["average_similarity"]).isEqualTo(0.97)

        val members = clusters[0]["members"].stringKeyMapList()
        assertThat(members.map { it["tag"] }).containsExactly("kotlin", "Kotlin")
        assertThat(members.map { it["count"] }).containsExactly(10, 3)
    }

    @Test
    fun `list_topics projects each summary with the bare slug`() {
        every { discoveryService.listTopics(5) } returns
            listOf(
                TopicSummary(
                    slug = "kotlin",
                    noteCount = 12,
                    lastCapturedAt = Instant.parse("2026-05-13T12:00:00Z"),
                ),
                TopicSummary(slug = "postgres", noteCount = 3, lastCapturedAt = null),
            )

        val out = tools.call("knowledge.list_topics", mapper.readTree("""{"limit":5}"""))!!

        val rows = out["topics"].stringKeyMapList()
        assertThat(rows).hasSize(2)
        assertThat(rows[0]["slug"]).isEqualTo("kotlin")
        assertThat(rows[0]["note_count"]).isEqualTo(12)
        assertThat(rows[0]["last_captured_at"]).isEqualTo("2026-05-13T12:00:00Z")
        assertThat(rows[1]["last_captured_at"]).isNull()
    }

    @Test
    fun `list_tags forwards the optional scope filter`() {
        every { discoveryService.listTags("project:personal-stack", 50) } returns
            listOf(
                TagSummary(
                    tag = "kotlin",
                    count = 7,
                    lastUsedAt = Instant.parse("2026-05-13T12:00:00Z"),
                ),
            )
        tools.call(
            "knowledge.list_tags",
            mapper.readTree("""{"scope":"project:personal-stack","limit":50}"""),
        )
        verify(exactly = 1) { discoveryService.listTags("project:personal-stack", 50) }
    }

    @Test
    fun `list_scopes returns counts ordered by note_count desc`() {
        every { discoveryService.listScopes(any()) } returns
            listOf(
                ScopeSummary(scope = "project:a", noteCount = 5, lastCapturedAt = null),
                ScopeSummary(scope = "personal", noteCount = 1, lastCapturedAt = null),
            )
        val out = tools.call("knowledge.list_scopes", mapper.readTree("""{}"""))!!

        val rows = out["scopes"].stringKeyMapList()
        assertThat(rows.map { it["scope"] }).containsExactly("project:a", "personal")
    }

    @Test
    fun `list_sources projects the source frequency`() {
        every { discoveryService.listSources(any()) } returns
            listOf(SourceSummary(source = "claude-code", count = 42))
        val out = tools.call("knowledge.list_sources", mapper.readTree("""{}"""))!!
        val rows = out["sources"].stringKeyMapList()
        assertThat(rows).hasSize(1)
        assertThat(rows[0]["source"]).isEqualTo("claude-code")
        assertThat(rows[0]["count"]).isEqualTo(42)
    }

    @Test
    fun `topic_stats returns null when the slug has no notes`() {
        every { discoveryService.topicStats("nonexistent", any()) } returns null
        val out = tools.call("knowledge.topic_stats", mapper.readTree("""{"slug":"nonexistent"}"""))!!
        assertThat(out["stats"]).isNull()
    }

    @Test
    fun `topic_stats projects the breakdowns when the slug has notes`() {
        every { discoveryService.topicStats("kotlin", 10) } returns topicStats()

        val out = tools.call("knowledge.topic_stats", mapper.readTree("""{"slug":"kotlin"}"""))!!
        val stats = out["stats"].stringKeyMap()
        assertThat(stats["slug"]).isEqualTo("kotlin")
        assertThat(stats["note_count"]).isEqualTo(12)
        assertThat(stats["type_breakdown"].stringKeyMap()["lesson"]).isEqualTo(8)
        val topTags = stats["top_tags"].stringKeyMapList()
        assertThat(topTags).hasSize(1)
        assertThat(topTags[0]["tag"]).isEqualTo("spring")
    }

    @Test
    fun `list_inbox surfaces vault_path so the operator can find the file`() {
        every { discoveryService.listInbox(20) } returns
            listOf(stubNote.copy(vaultPath = "_inbox/2026-05-13/hello.md", scope = "_inbox"))
        val out = tools.call("knowledge.list_inbox", mapper.readTree("""{}"""))!!
        val notes = out["notes"].stringKeyMapList()
        assertThat(notes).hasSize(1)
        assertThat(notes[0]["vault_path"]).isEqualTo("_inbox/2026-05-13/hello.md")
        assertThat(notes[0]["scope"]).isEqualTo("_inbox")
    }

    private fun topicStats(): TopicStats =
        TopicStats(
            slug = "kotlin",
            noteCount = 12,
            firstCapturedAt = Instant.parse("2026-05-10T12:00:00Z"),
            lastCapturedAt = Instant.parse("2026-05-13T12:00:00Z"),
            typeBreakdown = mapOf("lesson" to 8, "decision" to 4),
            topTags =
                listOf(
                    TagSummary(
                        tag = "spring",
                        count = 5,
                        lastUsedAt = Instant.parse("2026-05-13T12:00:00Z"),
                    ),
                ),
        )

    private fun discoveryTools(
        discoveryService: DiscoveryService,
        tagClusterService: TagClusterService,
    ): McpTools =
        McpTools(
            coreTools =
                CoreMcpToolSet(
                    CaptureMcpTools(mockk<CaptureService>(relaxed = true)),
                    ReadMcpTools(mockk<RecallService>(relaxed = true)),
                ),
            fullTools =
                FullMcpToolSet(
                    DiscoveryMcpTools(discoveryService, tagClusterService),
                    AdminMcpTools(
                        mockk<TopicRepository>(relaxed = true),
                        mockk<NoteRepository>(relaxed = true),
                        mockk<AuditRepository>(relaxed = true),
                        mockk<AdminAuthorization>(relaxed = true),
                    ),
                    DigestMcpTools(mockk<DigestService>(relaxed = true)),
                    AuditMcpTools(mockk<AuditService>(relaxed = true)),
                    ReviewMcpTools(mockk<ReviewService>(relaxed = true)),
                ),
        )
}
