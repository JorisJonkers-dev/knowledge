package com.jorisjonkers.personalstack.knowledge.capture

import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.Ulid
import com.jorisjonkers.personalstack.knowledge.queue.IngestPublisher
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Capture-side write path shared by the `capture_lesson`,
 * `capture_decision`, and `ingest_note` MCP tools.
 *
 * Each capture:
 *   1. mints a ULID,
 *   2. inserts the canonical row in `kb_notes` (plus join rows in
 *      `kb_note_tags`),
 *   3. publishes a worker job on the `knowledge` topic exchange so the
 *      Python ingest worker (Phase 5) can chunk / embed / extract on
 *      its own clock.
 *
 * The publish runs after the DB write returns so a downstream worker
 * that loads the row by id never races the insert; jOOQ's default
 * auto-commit on `spring-boot-starter-jooq` makes each `execute()`
 * stand on its own without a wrapping `@Transactional`.
 */
@Service
class CaptureService(
    private val noteRepository: NoteRepository,
    private val ingestPublisher: IngestPublisher,
) {
    fun captureLesson(request: CaptureRequest): KbNote {
        val note = persist(request.copy(type = KbNoteType.LESSON))
        ingestPublisher.publishCapturedNote(note)
        return note
    }

    fun captureDecision(request: CaptureRequest): KbNote {
        val note = persist(request.copy(type = KbNoteType.DECISION))
        ingestPublisher.publishCapturedNote(note)
        return note
    }

    fun captureGenericNote(request: CaptureRequest): KbNote {
        val note = persist(request)
        ingestPublisher.publishCapturedNote(note)
        return note
    }

    private fun persist(request: CaptureRequest): KbNote {
        val now = Instant.now()
        val note =
            KbNote(
                id = Ulid.generate(now),
                type = request.type,
                scope = request.scope,
                source = request.source,
                capturedAt = now,
                sessionId = request.sessionId,
                confidence = request.confidence,
                title = request.title,
                body = request.body,
                vaultPath = request.vaultPath ?: "${request.scope}/${request.type.wire}/draft.md",
                vaultCommit = null,
                tags = request.tags,
            )
        return noteRepository.create(note)
    }
}

data class CaptureRequest(
    val type: KbNoteType,
    val scope: String,
    val source: String,
    val title: String,
    val body: String,
    val sessionId: String? = null,
    val confidence: Double = DEFAULT_CONFIDENCE,
    val vaultPath: String? = null,
    val tags: Set<String> = emptySet(),
) {
    companion object {
        // Matches the V1 migration default — fresh captures start at
        // 0.4 and rise on consolidation (worker bumps to 0.8, manual
        // edits to 0.95).
        const val DEFAULT_CONFIDENCE = 0.4
    }
}
