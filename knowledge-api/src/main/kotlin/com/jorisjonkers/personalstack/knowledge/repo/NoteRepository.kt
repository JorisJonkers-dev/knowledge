package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNoteTags.KB_NOTE_TAGS
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNotes.KB_NOTES
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.ZoneOffset

/**
 * Write-path repo for the canonical kb_notes + kb_note_tags rows. The
 * read side (recall / get / list_recent / find_conflicts) lands in a
 * follow-up PR alongside the hybrid pgvector+tsvector query.
 */
@Repository
class NoteRepository(
    private val dsl: DSLContext,
) {
    fun create(note: KbNote): KbNote {
        val capturedAt = note.capturedAt.atOffset(ZoneOffset.UTC).toLocalDateTime()
        dsl
            .insertInto(KB_NOTES)
            .set(KB_NOTES.ID, note.id)
            .set(KB_NOTES.TYPE, note.type.wire)
            .set(KB_NOTES.SCOPE, note.scope)
            .set(KB_NOTES.SOURCE, note.source)
            .set(KB_NOTES.CAPTURED_AT, capturedAt)
            .set(KB_NOTES.SESSION_ID, note.sessionId)
            .set(KB_NOTES.CONFIDENCE, note.confidence.toFloat())
            .set(KB_NOTES.TITLE, note.title)
            .set(KB_NOTES.BODY, note.body)
            .set(KB_NOTES.VAULT_PATH, note.vaultPath)
            .set(KB_NOTES.VAULT_COMMIT, note.vaultCommit)
            .set(KB_NOTES.CREATED_AT, capturedAt)
            .set(KB_NOTES.UPDATED_AT, capturedAt)
            .execute()

        if (note.tags.isNotEmpty()) {
            note.tags.forEach { tag ->
                dsl
                    .insertInto(KB_NOTE_TAGS)
                    .set(KB_NOTE_TAGS.NOTE_ID, note.id)
                    .set(KB_NOTE_TAGS.TAG, tag)
                    .execute()
            }
        }
        return note
    }

    fun tagsOf(noteId: String): Set<String> =
        dsl
            .select(KB_NOTE_TAGS.TAG)
            .from(KB_NOTE_TAGS)
            .where(KB_NOTE_TAGS.NOTE_ID.eq(noteId))
            .fetchSet { it.value1() ?: "" }
            .filter { it.isNotBlank() }
            .toSet()

    fun rowCount(): Int = dsl.fetchCount(KB_NOTES)

    @Suppress("ReturnCount")
    fun findByIdRaw(id: String): Map<String, Any?>? {
        val record =
            dsl
                .selectFrom(KB_NOTES)
                .where(KB_NOTES.ID.eq(id))
                .fetchOne() ?: return null
        return mapOf(
            "id" to record.id,
            "type" to record.type,
            "scope" to record.scope,
            "source" to record.source,
            "captured_at" to record.capturedAt?.toInstant(ZoneOffset.UTC)?.toString(),
            "session_id" to record.sessionId,
            "confidence" to record.confidence?.toDouble(),
            "title" to record.title,
            "body" to record.body,
            "vault_path" to record.vaultPath,
            "vault_commit" to record.vaultCommit,
            "created_at" to record.createdAt?.toInstant(ZoneOffset.UTC)?.toString(),
            "updated_at" to record.updatedAt?.toInstant(ZoneOffset.UTC)?.toString(),
        )
    }
}
