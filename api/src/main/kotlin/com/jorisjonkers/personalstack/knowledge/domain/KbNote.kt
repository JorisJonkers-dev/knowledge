package com.jorisjonkers.personalstack.knowledge.domain

import java.time.Instant

/**
 * Canonical knowledge-base note as written by knowledge-api on capture.
 * The ingest worker layers chunk/embedding/entity rows on top of this
 * row, but the row itself is the single source of truth for who wrote
 * what, when, under which scope, and with what confidence.
 */
data class KbNote(
    val id: String,
    val type: KbNoteType,
    val scope: String,
    val source: String,
    val capturedAt: Instant,
    val sessionId: String?,
    val confidence: Double,
    val title: String,
    val body: String,
    val vaultPath: String,
    val vaultCommit: String?,
    val tags: Set<String> = emptySet(),
)

enum class KbNoteType(
    val wire: String,
) {
    LESSON("lesson"),
    DECISION("decision"),
    NOTE("note"),
    FACT("fact"),

    /**
     * Question the AI asked itself (or the operator) while classifying
     * or curating a note. Persisted via `knowledge.capture_question`
     * so the operator can answer in the knowledge-ui questions view
     * and the AI can fold the answer back into a topic/tag/relation
     * decision. Filter cleanly by `list_recent(type=question)`.
     */
    QUESTION("question"),
    ;

    companion object {
        fun fromWire(value: String): KbNoteType =
            entries.firstOrNull { it.wire == value }
                ?: error("unknown KbNoteType wire value: $value")
    }
}
