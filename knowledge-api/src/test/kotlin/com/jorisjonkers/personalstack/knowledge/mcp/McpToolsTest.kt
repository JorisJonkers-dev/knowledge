@file:Suppress("DEPRECATION") // Jackson 3 deprecated asText().

package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.audit.AuditService
import com.jorisjonkers.personalstack.knowledge.auth.AdminAuthorization
import com.jorisjonkers.personalstack.knowledge.capture.CaptureRequest
import com.jorisjonkers.personalstack.knowledge.capture.CaptureService
import com.jorisjonkers.personalstack.knowledge.digest.DigestService
import com.jorisjonkers.personalstack.knowledge.discovery.DiscoveryService
import com.jorisjonkers.personalstack.knowledge.discovery.TagClusterService
import com.jorisjonkers.personalstack.knowledge.domain.DigestCandidate
import com.jorisjonkers.personalstack.knowledge.domain.KbAuditRow
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.KbRelation
import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.domain.ReviewBucket
import com.jorisjonkers.personalstack.knowledge.domain.ReviewNote
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSuggestion
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSummary
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
import com.jorisjonkers.personalstack.knowledge.review.ReviewSummaryRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

class McpToolsTest {
    private val captureService = mockk<CaptureService>()
    private val recallService = mockk<RecallService>(relaxed = true)
    private val discoveryService = mockk<DiscoveryService>(relaxed = true)
    private val tagClusterService = mockk<TagClusterService>(relaxed = true)
    private val digestService = mockk<DigestService>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val reviewService = mockk<ReviewService>(relaxed = true)
    private val topicRepository = mockk<TopicRepository>(relaxed = true)
    private val noteRepository = mockk<NoteRepository>(relaxed = true)
    private val auditRepository = mockk<AuditRepository>(relaxed = true)
    private val adminAuthorization = mockk<AdminAuthorization>(relaxed = true)

    // Wire real Capture/Read/Discovery/Admin/Digest/AuditMcpTools
    // around the mocked services — that's what Spring does in
    // production and what gives us coverage of the descriptor
    // builders + handler argument parsing.
    private val tools =
        McpTools(
            CaptureMcpTools(captureService),
            ReadMcpTools(recallService),
            DiscoveryMcpTools(discoveryService, tagClusterService),
            AdminMcpTools(topicRepository, noteRepository, auditRepository, adminAuthorization),
            DigestMcpTools(digestService),
            AuditMcpTools(auditService),
            ReviewMcpTools(reviewService),
        )
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

    private fun auditRow(id: String = "01HXAUD0000000000000000000") =
        KbAuditRow(
            id = id,
            actor = "mcp:admin",
            action = "merge_tags",
            targetId = null,
            targetKind = "tag",
            beforeJson = null,
            afterJson = null,
            at = Instant.parse("2026-05-19T13:00:00Z"),
        )

    @Test
    fun `describe advertises capture, read, discovery, admin, digest, and audit tools by name`() {
        val names = tools.describe().map { it["name"] as String }
        assertThat(names).containsExactlyInAnyOrder(
            "knowledge.capture_lesson",
            "knowledge.capture_decision",
            "knowledge.ingest_note",
            "knowledge.capture_question",
            "knowledge.recall",
            "knowledge.get_note",
            "knowledge.list_recent",
            "knowledge.find_conflicts",
            "knowledge.relations",
            "knowledge.list_topics",
            "knowledge.list_tags",
            "knowledge.list_scopes",
            "knowledge.list_sources",
            "knowledge.topic_stats",
            "knowledge.list_inbox",
            "knowledge.suggest_topic",
            "knowledge.find_duplicates",
            "knowledge.list_tag_candidates",
            "knowledge.add_topic",
            "knowledge.update_topic",
            "knowledge.merge_topics",
            "knowledge.rename_tag",
            "knowledge.merge_tags",
            "knowledge.reclassify_note",
            "knowledge.digest_transcript",
            "knowledge.list_audit",
            "knowledge.review_summary",
        )
    }

    @Test
    fun `review_summary forwards bounded request and projects governance buckets`() {
        val request = slot<ReviewSummaryRequest>()
        every { reviewService.summary(capture(request)) } returns
            ReviewSummary(
                generatedAt = Instant.parse("2026-06-04T16:00:00Z"),
                inbox =
                    ReviewBucket(
                        total = 1,
                        items =
                            listOf(
                                ReviewNote(
                                    id = "01HXREVIEW0000000000000000",
                                    type = "lesson",
                                    scope = "_inbox",
                                    source = "assistant-ui:auto-capture:s1",
                                    capturedAt = Instant.parse("2026-06-04T15:00:00Z"),
                                    confidence = 0.52,
                                    title = "pending note",
                                    vaultPath = "_inbox/2026-06-04/pending.md",
                                    tags = setOf("auto-capture"),
                                    recallCount = 4,
                                    lastRecalledAt = Instant.parse("2026-06-04T15:30:00Z"),
                                ),
                            ),
                    ),
                needsReview = ReviewBucket(total = 0, items = emptyList()),
                recentAutoCaptures = ReviewBucket(total = 0, items = emptyList()),
                staleUnusedNotes = ReviewBucket(total = 0, items = emptyList()),
                lowConfidenceHighRecall = ReviewBucket(total = 0, items = emptyList()),
                tagCandidateClusters = ReviewBucket(total = 0, items = emptyList()),
                recentAudit = ReviewBucket(total = 0, items = emptyList()),
                suggestions =
                    listOf(
                        ReviewSuggestion(
                            kind = "needs_review",
                            severity = "high",
                            message = "review pending memory",
                            suggestedTool = "knowledge.reclassify_note",
                            targetId = "01HXREVIEW0000000000000000",
                            targetKind = "note",
                            details = mapOf("total" to 1),
                        ),
                    ),
            )

        val out =
            tools.call(
                "knowledge.review_summary",
                mapper.readTree(
                    """
                    {
                      "limit": 5,
                      "stale_days": 30,
                      "low_confidence_max": 0.5,
                      "high_recall_min": 2,
                      "tag_threshold": 0.9
                    }
                    """.trimIndent(),
                ),
            )!!

        assertThat(request.captured.limit).isEqualTo(5)
        assertThat(request.captured.staleDays).isEqualTo(30)
        assertThat(request.captured.lowConfidenceMax).isEqualTo(0.5)
        assertThat(request.captured.highRecallMin).isEqualTo(2)
        assertThat(request.captured.tagThreshold).isEqualTo(0.9)

        @Suppress("UNCHECKED_CAST")
        val summary = out["summary"] as Map<String, Any?>
        assertThat(summary["generated_at"]).isEqualTo("2026-06-04T16:00:00Z")
        @Suppress("UNCHECKED_CAST")
        val inbox = summary["inbox"] as Map<String, Any?>
        assertThat(inbox["total"]).isEqualTo(1)
        @Suppress("UNCHECKED_CAST")
        val inboxItems = inbox["items"] as List<Map<String, Any?>>
        assertThat(inboxItems[0]["source"]).isEqualTo("assistant-ui:auto-capture:s1")
        assertThat(inboxItems[0]["recall_count"]).isEqualTo(4)
        assertThat(inboxItems[0]["last_recalled_at"]).isEqualTo("2026-06-04T15:30:00Z")
        @Suppress("UNCHECKED_CAST")
        val suggestions = summary["suggestions"] as List<Map<String, Any?>>
        assertThat(suggestions[0]["suggested_tool"]).isEqualTo("knowledge.reclassify_note")
    }

    @Test
    fun `list_audit projects each row including target metadata and timestamps`() {
        every { auditService.list(any(), any(), any(), any(), any()) } returns
            listOf(
                KbAuditRow(
                    id = "01HXAUD0000000000000000000",
                    actor = "kb-renormalise-titles",
                    action = "rename_title",
                    targetId = "01HXNOTE000000000000000000",
                    targetKind = "note",
                    beforeJson = """{"title":"a very long title that nobody can scan"}""",
                    afterJson = """{"title":"scannable claim"}""",
                    at = Instant.parse("2026-05-19T13:00:00Z"),
                ),
            )

        val out =
            tools.call(
                "knowledge.list_audit",
                mapper.readTree("""{"actor":"kb-renormalise-titles","limit":10}"""),
            )!!

        @Suppress("UNCHECKED_CAST")
        val rows = out["rows"] as List<Map<String, Any?>>
        assertThat(rows).hasSize(1)
        assertThat(rows[0]["action"]).isEqualTo("rename_title")
        assertThat(rows[0]["target_kind"]).isEqualTo("note")
        assertThat(rows[0]["after_json"]).isEqualTo("""{"title":"scannable claim"}""")
        assertThat(rows[0]["at"]).isEqualTo("2026-05-19T13:00:00Z")
    }

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

        @Suppress("UNCHECKED_CAST")
        val clusters = out["clusters"] as List<Map<String, Any?>>
        assertThat(clusters).hasSize(1)
        assertThat(clusters[0]["suggested_canonical"]).isEqualTo("kotlin")
        assertThat(clusters[0]["average_similarity"]).isEqualTo(0.97)

        @Suppress("UNCHECKED_CAST")
        val members = clusters[0]["members"] as List<Map<String, Any?>>
        assertThat(members.map { it["tag"] }).containsExactly("kotlin", "Kotlin")
        assertThat(members.map { it["count"] }).containsExactly(10, 3)
    }

    @Test
    fun `merge_tags forwards source tags and projects idempotent merge counts`() {
        every { adminAuthorization.requireAdmin() } returns "mcp:admin"
        every { topicRepository.mergeTags(listOf("kt", "kts"), "kotlin") } returns
            TopicRepository.MergeTagsResult(rowsRenamed = 2, rowsDeletedAsDupes = 1)
        every { auditRepository.record(any(), any(), any(), any(), any(), any(), any()) } returns
            auditRow(id = "01HXAUDMERGETAGS0000000000")

        val out =
            tools.call(
                "knowledge.merge_tags",
                mapper.readTree("""{"from":["kt","kts"],"into":"kotlin"}"""),
            )!!

        assertThat(out["from"]).isEqualTo(listOf("kt", "kts"))
        assertThat(out["into"]).isEqualTo("kotlin")
        assertThat(out["rows_renamed"]).isEqualTo(2)
        assertThat(out["rows_dropped_as_dupes"]).isEqualTo(1)
        assertThat(out["actor"]).isEqualTo("mcp:admin")
        assertThat(out["audit_id"]).isEqualTo("01HXAUDMERGETAGS0000000000")
        verify(exactly = 1) { topicRepository.mergeTags(listOf("kt", "kts"), "kotlin") }
        verify(exactly = 1) {
            auditRepository.record(
                actor = "mcp:admin",
                action = "merge_tags",
                targetId = null,
                targetKind = "tag",
                beforeJson = """{"from":["kt","kts"]}""",
                afterJson = """{"into":"kotlin","rows_renamed":2,"rows_dropped_as_dupes":1}""",
                now = any(),
            )
        }
    }

    @Test
    fun `merge_tags skips audit rows when the merge is an idempotent no-op`() {
        every { adminAuthorization.requireAdmin() } returns "mcp:admin"
        every { topicRepository.mergeTags(listOf("kt"), "kotlin") } returns
            TopicRepository.MergeTagsResult(rowsRenamed = 0, rowsDeletedAsDupes = 0)

        val out =
            tools.call(
                "knowledge.merge_tags",
                mapper.readTree("""{"from":["kt"],"into":"kotlin"}"""),
            )!!

        assertThat(out).doesNotContainKey("audit_id")
        verify(exactly = 0) { auditRepository.record(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rename_tag writes an audit row when rows are touched`() {
        every { adminAuthorization.requireAdmin() } returns "mcp:admin"
        every { topicRepository.renameTag("kt", "kotlin") } returns 2
        every { auditRepository.record(any(), any(), any(), any(), any(), any(), any()) } returns
            auditRow(id = "01HXAUDRENAMETAG000000000")

        val out =
            tools.call(
                "knowledge.rename_tag",
                mapper.readTree("""{"from":"kt","to":"kotlin"}"""),
            )!!

        assertThat(out["rows_touched"]).isEqualTo(2)
        assertThat(out["audit_id"]).isEqualTo("01HXAUDRENAMETAG000000000")
        verify(exactly = 1) {
            auditRepository.record(
                actor = "mcp:admin",
                action = "rename_tag",
                targetId = null,
                targetKind = "tag",
                beforeJson = """{"tag":"kt"}""",
                afterJson = """{"tag":"kotlin","rows_touched":2}""",
                now = any(),
            )
        }
    }

    @Test
    fun `digest_transcript projects the service's candidates verbatim`() {
        every { digestService.digest(any(), any(), any()) } returns
            listOf(
                DigestCandidate(
                    kind = "lesson",
                    title = "auto-mcp hooks need a panic switch",
                    body =
                        "When auto-capture runs in a runaway loop, an env var that short-circuits " +
                            "every hook is the only sane recovery path.",
                    suggestedTopic = "claude-code",
                    suggestedTags = listOf("hooks", "safety"),
                    confidence = 0.82,
                    relevantExcerpts = listOf("KB_AUTO_MCP_DISABLED=1 short-circuits the hook"),
                ),
            )

        val out =
            tools.call(
                "knowledge.digest_transcript",
                mapper.readTree("""{"transcript":"... session text ...","max_candidates":3,"min_confidence":0.5}"""),
            )!!

        @Suppress("UNCHECKED_CAST")
        val candidates = out["candidates"] as List<Map<String, Any?>>
        assertThat(candidates).hasSize(1)
        assertThat(candidates[0]["kind"]).isEqualTo("lesson")
        assertThat(candidates[0]["confidence"]).isEqualTo(0.82)
        assertThat(candidates[0]["suggested_topic"]).isEqualTo("claude-code")
    }

    @Test
    fun `capture tool descriptors are valid JSON Schema with the shared required and properties`() {
        val captureNames =
            setOf("knowledge.capture_lesson", "knowledge.capture_decision", "knowledge.ingest_note")
        tools.describe().filter { it["name"] in captureNames }.forEach { descriptor ->
            val schema = descriptor["inputSchema"] as Map<*, *>
            assertThat(schema["type"]).isEqualTo("object")
            @Suppress("UNCHECKED_CAST")
            val required = schema["required"] as List<String>
            // `scope` is optional now: omitted captures default to
            // `_inbox` and the curator agent assigns the final scope
            // during the classify-and-promote pass.
            assertThat(required).containsExactlyInAnyOrder("title", "body")
            @Suppress("UNCHECKED_CAST")
            val properties = schema["properties"] as Map<String, Any>
            assertThat(properties.keys)
                .contains(
                    "scope",
                    "title",
                    "body",
                    "source",
                    "session_id",
                    "confidence",
                    "vault_path",
                )
        }
    }

    @Test
    fun `read tool descriptors expose their own required and properties shape`() {
        val recall = tools.describe().single { it["name"] == "knowledge.recall" }

        @Suppress("UNCHECKED_CAST")
        val recallSchema = recall["inputSchema"] as Map<String, Any>
        assertThat(recallSchema["required"] as List<*>).containsExactly("query")
        assertThat((recallSchema["properties"] as Map<*, *>).keys).contains("query", "scope", "limit")

        val findConflicts = tools.describe().single { it["name"] == "knowledge.find_conflicts" }

        @Suppress("UNCHECKED_CAST")
        val fcSchema = findConflicts["inputSchema"] as Map<String, Any>
        assertThat(fcSchema["required"] as List<*>).containsExactly("id")
    }

    @Test
    fun `recall forwards parsed args and returns hits projection`() {
        every { recallService.recall("rockets", "personal", 5) } returns
            listOf(
                RecallHit(
                    id = "01HXY",
                    type = "lesson",
                    scope = "personal",
                    title = "t",
                    snippet = "s",
                    score = 0.42,
                ),
            )

        val out =
            tools.call(
                "knowledge.recall",
                mapper.readTree("""{"query":"rockets","scope":"personal","limit":5}"""),
            )!!

        @Suppress("UNCHECKED_CAST")
        val hits = out["hits"] as List<Map<String, Any?>>
        assertThat(hits).hasSize(1)
        assertThat(hits[0]["id"]).isEqualTo("01HXY")
        assertThat(hits[0]["score"]).isEqualTo(0.42)
    }

    @Test
    fun `get_note returns the wrapped note or a null marker`() {
        every { recallService.getNote("01HXY") } returns null
        val out = tools.call("knowledge.get_note", mapper.readTree("""{"id":"01HXY"}"""))!!
        assertThat(out["note"]).isNull()
    }

    @Test
    fun `list_recent parses optional type filter as a KbNoteType`() {
        every { recallService.listRecent(null, KbNoteType.DECISION, 7) } returns emptyList()
        tools.call(
            "knowledge.list_recent",
            mapper.readTree("""{"type":"decision","limit":7}"""),
        )
        verify(exactly = 1) { recallService.listRecent(null, KbNoteType.DECISION, 7) }
    }

    @Test
    fun `find_conflicts projects each relation to a flat map`() {
        every { recallService.findConflicts("01HXY") } returns
            listOf(
                KbRelation(
                    subjectId = "01HXY",
                    predicate = "supersedes",
                    objectId = "01ABC",
                    props = mapOf("note" to "rotation"),
                    createdAt = Instant.parse("2026-05-13T12:00:00Z"),
                ),
            )
        val out = tools.call("knowledge.find_conflicts", mapper.readTree("""{"id":"01HXY"}"""))!!

        @Suppress("UNCHECKED_CAST")
        val rels = out["relations"] as List<Map<String, Any?>>
        assertThat(rels).hasSize(1)
        assertThat(rels[0]["predicate"]).isEqualTo("supersedes")
        assertThat(rels[0]["object_id"]).isEqualTo("01ABC")
    }

    @Test
    fun `relations forwards depth to the service and projects each edge`() {
        every { recallService.walkRelations("01HXY", 2) } returns
            listOf(
                KbRelation(
                    subjectId = "01HXY",
                    predicate = "see_also",
                    objectId = "01DEF",
                    props = emptyMap(),
                    createdAt = Instant.parse("2026-05-13T12:00:00Z"),
                ),
                KbRelation(
                    subjectId = "01DEF",
                    predicate = "supersedes",
                    objectId = "01OLD",
                    props = emptyMap(),
                    createdAt = Instant.parse("2026-05-13T12:01:00Z"),
                ),
            )
        val out =
            tools.call("knowledge.relations", mapper.readTree("""{"id":"01HXY","depth":2}"""))!!

        @Suppress("UNCHECKED_CAST")
        val rels = out["relations"] as List<Map<String, Any?>>
        assertThat(rels).hasSize(2)
        assertThat(rels.map { it["object_id"] }).containsExactly("01DEF", "01OLD")
        verify(exactly = 1) { recallService.walkRelations("01HXY", 2) }
    }

    @Test
    fun `relations defaults depth to one when omitted`() {
        every { recallService.walkRelations("01HXY", 1) } returns emptyList()
        tools.call("knowledge.relations", mapper.readTree("""{"id":"01HXY"}"""))
        verify(exactly = 1) { recallService.walkRelations("01HXY", 1) }
    }

    @Test
    fun `unknown tool name returns null so the controller can emit method_not_found`() {
        val args = mapper.createObjectNode()
        assertThat(tools.call("knowledge.no_such_tool", args)).isNull()
    }

    @Test
    fun `capture_lesson forwards parsed args to the service`() {
        val captured = slot<CaptureRequest>()
        every { captureService.captureLesson(capture(captured)) } returns stubNote
        val args =
            mapper.readTree(
                """
                {
                  "scope":"personal",
                  "title":"lesson title",
                  "body":"lesson body",
                  "tags":["kotlin","mcp"]
                }
                """.trimIndent(),
            )

        val result = tools.call("knowledge.capture_lesson", args)!!

        assertThat(captured.captured.scope).isEqualTo("personal")
        assertThat(captured.captured.title).isEqualTo("lesson title")
        assertThat(captured.captured.body).isEqualTo("lesson body")
        assertThat(captured.captured.tags).containsExactlyInAnyOrder("kotlin", "mcp")
        assertThat(captured.captured.source).isEqualTo("claude-code")
        assertThat(result["id"]).isEqualTo(stubNote.id)
        assertThat(result["type"]).isEqualTo("lesson")
    }

    @Test
    fun `capture_decision forwards via the decision branch of the service`() {
        every { captureService.captureDecision(any()) } returns stubNote.copy(type = KbNoteType.DECISION)
        val args =
            mapper.readTree(
                """{"scope":"work","title":"t","body":"b"}""",
            )

        tools.call("knowledge.capture_decision", args)

        verify(exactly = 1) { captureService.captureDecision(any()) }
    }

    @Test
    fun `ingest_note honours explicit type parameter`() {
        val captured = slot<CaptureRequest>()
        every { captureService.captureGenericNote(capture(captured)) } returns stubNote.copy(type = KbNoteType.FACT)

        tools.call(
            "knowledge.ingest_note",
            mapper.readTree("""{"scope":"agent:claude","title":"u","body":"v","type":"fact"}"""),
        )

        assertThat(captured.captured.type).isEqualTo(KbNoteType.FACT)
    }

    @Test
    fun `ingest_note defaults to NOTE when type is omitted`() {
        val captured = slot<CaptureRequest>()
        every { captureService.captureGenericNote(capture(captured)) } returns stubNote.copy(type = KbNoteType.NOTE)

        tools.call(
            "knowledge.ingest_note",
            mapper.readTree("""{"scope":"agent:claude","title":"u","body":"v"}"""),
        )

        assertThat(captured.captured.type).isEqualTo(KbNoteType.NOTE)
    }

    @Test
    fun `missing required field surfaces an error from the parser`() {
        // `title` is required even after the scope relaxation; only
        // `scope` was moved out of the required list.
        val args = mapper.readTree("""{"body":"only body, no title"}""")

        org.assertj.core.api.Assertions
            .assertThatThrownBy { tools.call("knowledge.capture_lesson", args) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("missing required field")
    }

    @Test
    fun `omitted scope defaults to _inbox so the curator can classify after capture`() {
        val captured = slot<CaptureRequest>()
        every { captureService.captureLesson(capture(captured)) } returns stubNote

        tools.call(
            "knowledge.capture_lesson",
            mapper.readTree("""{"title":"t","body":"b"}"""),
        )

        assertThat(captured.captured.scope).isEqualTo("_inbox")
    }

    @Test
    fun `optional confidence is parsed when present`() {
        val captured = slot<CaptureRequest>()
        every { captureService.captureLesson(capture(captured)) } returns stubNote
        tools.call(
            "knowledge.capture_lesson",
            mapper.readTree("""{"scope":"p","title":"t","body":"b","confidence":0.85}"""),
        )
        assertThat(captured.captured.confidence).isEqualTo(0.85)
    }

    // -------- discovery tools --------

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

        @Suppress("UNCHECKED_CAST")
        val rows = out["topics"] as List<Map<String, Any?>>
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

        @Suppress("UNCHECKED_CAST")
        val rows = out["scopes"] as List<Map<String, Any?>>
        assertThat(rows.map { it["scope"] }).containsExactly("project:a", "personal")
    }

    @Test
    fun `list_sources projects the source frequency`() {
        every { discoveryService.listSources(any()) } returns
            listOf(SourceSummary(source = "claude-code", count = 42))
        val out = tools.call("knowledge.list_sources", mapper.readTree("""{}"""))!!

        @Suppress("UNCHECKED_CAST")
        val rows = out["sources"] as List<Map<String, Any?>>
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
        every { discoveryService.topicStats("kotlin", 10) } returns
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

        val out = tools.call("knowledge.topic_stats", mapper.readTree("""{"slug":"kotlin"}"""))!!

        @Suppress("UNCHECKED_CAST")
        val stats = out["stats"] as Map<String, Any?>
        assertThat(stats["slug"]).isEqualTo("kotlin")
        assertThat(stats["note_count"]).isEqualTo(12)
        assertThat((stats["type_breakdown"] as Map<*, *>)["lesson"]).isEqualTo(8)

        @Suppress("UNCHECKED_CAST")
        val topTags = stats["top_tags"] as List<Map<String, Any?>>
        assertThat(topTags).hasSize(1)
        assertThat(topTags[0]["tag"]).isEqualTo("spring")
    }

    @Test
    fun `list_inbox surfaces vault_path so the operator can find the file`() {
        every { discoveryService.listInbox(20) } returns
            listOf(stubNote.copy(vaultPath = "_inbox/2026-05-13/hello.md", scope = "_inbox"))
        val out = tools.call("knowledge.list_inbox", mapper.readTree("""{}"""))!!

        @Suppress("UNCHECKED_CAST")
        val notes = out["notes"] as List<Map<String, Any?>>
        assertThat(notes).hasSize(1)
        assertThat(notes[0]["vault_path"]).isEqualTo("_inbox/2026-05-13/hello.md")
        assertThat(notes[0]["scope"]).isEqualTo("_inbox")
    }
}
