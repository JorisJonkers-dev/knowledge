package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.KbRelation
import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import com.jorisjonkers.personalstack.knowledge.repo.RecallRepository
import org.springframework.stereotype.Service

/**
 * Read-side façade for the MCP read tools: `recall` (FTS),
 * `get_note`, `list_recent`, and `find_conflicts`. Owning the
 * façade keeps the controller / tool registry free of jOOQ
 * imports and lets us bolt on the pgvector ANN leg later without
 * widening the tool surface.
 */
@Service
class RecallService(
    private val noteRepository: NoteRepository,
    private val recallRepository: RecallRepository,
) {
    fun recall(
        query: String,
        scope: String?,
        limit: Int,
    ): List<RecallHit> = recallRepository.recall(query, scope, limit)

    fun getNote(id: String): KbNote? = noteRepository.findById(id)

    fun listRecent(
        scope: String?,
        type: KbNoteType?,
        limit: Int,
    ): List<KbNote> = noteRepository.listRecent(scope, type, limit)

    fun findConflicts(id: String): List<KbRelation> = noteRepository.findConflicts(id)
}
