package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.ReviewBucket
import com.jorisjonkers.personalstack.knowledge.domain.ReviewNote
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNoteTags.KB_NOTE_TAGS
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNotes.KB_NOTES
import com.jorisjonkers.personalstack.knowledge.jooq.tables.records.KbNotesRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.SortField
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset

@Repository
class ReviewRepository(
    private val dsl: DSLContext,
) {
    fun listInbox(limit: Int): ReviewBucket<ReviewNote> =
        listReviewNotes(
            dsl = dsl,
            condition =
                KB_NOTES.SCOPE
                    .eq(INBOX_SCOPE)
                    .and(needsReviewPathCondition().not()),
            limit = limit,
            orderBy = listOf(KB_NOTES.ID.desc()),
        )

    fun listNeedsReview(limit: Int): ReviewBucket<ReviewNote> =
        listReviewNotes(
            dsl = dsl,
            condition =
                KB_NOTES.SCOPE
                    .eq(INBOX_SCOPE)
                    .and(needsReviewPathCondition()),
            limit = limit,
            orderBy = listOf(KB_NOTES.ID.desc()),
        )

    fun listRecentAutoCaptures(limit: Int): ReviewBucket<ReviewNote> =
        listReviewNotes(
            dsl = dsl,
            condition = autoCaptureCondition(),
            limit = limit,
            orderBy = listOf(KB_NOTES.ID.desc()),
        )

    fun listStaleUnusedNotes(
        olderThan: Instant,
        limit: Int,
    ): ReviewBucket<ReviewNote> =
        listReviewNotes(
            dsl = dsl,
            condition =
                curatedCondition()
                    .and(KB_NOTES.RECALL_COUNT.eq(0))
                    .and(KB_NOTES.LAST_RECALLED_AT.isNull)
                    .and(KB_NOTES.CAPTURED_AT.lt(olderThan.atOffset(ZoneOffset.UTC).toLocalDateTime())),
            limit = limit,
            orderBy = listOf(KB_NOTES.CAPTURED_AT.asc(), KB_NOTES.ID.asc()),
        )

    fun listLowConfidenceHighRecall(
        maxConfidence: Double,
        minRecallCount: Int,
        limit: Int,
    ): ReviewBucket<ReviewNote> =
        listReviewNotes(
            dsl = dsl,
            condition =
                curatedCondition()
                    .and(KB_NOTES.CONFIDENCE.le(maxConfidence.toFloat()))
                    .and(KB_NOTES.RECALL_COUNT.ge(minRecallCount)),
            limit = limit,
            orderBy = listOf(KB_NOTES.RECALL_COUNT.desc(), KB_NOTES.ID.desc()),
        )
}

private fun listReviewNotes(
    dsl: DSLContext,
    condition: Condition,
    limit: Int,
    orderBy: List<SortField<*>>,
): ReviewBucket<ReviewNote> {
    val total =
        dsl
            .selectCount()
            .from(KB_NOTES)
            .where(condition)
            .fetchOne(0, Int::class.java) ?: 0
    val records =
        dsl
            .selectFrom(KB_NOTES)
            .where(condition)
            .orderBy(orderBy)
            .limit(limit)
            .fetch()
    val ids = records.mapNotNull { it.id }.filter { it.isNotBlank() }
    val tagMap = bulkReviewTags(dsl, ids)
    return ReviewBucket(total = total, items = records.map { it.toReviewNote(tagMap[it.id].orEmpty()) })
}

private fun bulkReviewTags(
    dsl: DSLContext,
    ids: List<String>,
): Map<String, Set<String>> {
    if (ids.isEmpty()) return emptyMap()
    return dsl
        .select(KB_NOTE_TAGS.NOTE_ID, KB_NOTE_TAGS.TAG)
        .from(KB_NOTE_TAGS)
        .where(KB_NOTE_TAGS.NOTE_ID.`in`(ids))
        .fetch()
        .groupBy({ it.value1() ?: "" }, { it.value2() ?: "" })
        .mapValues { (_, tags) -> tags.filter { it.isNotBlank() }.toSet() }
}

private fun autoCaptureCondition(): Condition {
    val autoTag =
        DSL
            .selectOne()
            .from(KB_NOTE_TAGS)
            .where(KB_NOTE_TAGS.NOTE_ID.eq(KB_NOTES.ID))
            .and(KB_NOTE_TAGS.TAG.`in`(AUTO_CAPTURE_TAGS))
    return KB_NOTES.SOURCE
        .like("%auto%")
        .or(DSL.exists(autoTag))
}

private fun curatedCondition(): Condition =
    KB_NOTES.SCOPE
        .ne(INBOX_SCOPE)
        .and(KB_NOTES.SCOPE.ne(NEEDS_REVIEW_SCOPE))

private fun needsReviewPathCondition(): Condition =
    KB_NOTES
        .VAULT_PATH
        .like("${likeLiteral(NEEDS_REVIEW_PREFIX)}%", LIKE_ESCAPE)

private fun likeLiteral(value: String): String =
    value
        .replace(LIKE_ESCAPE.toString(), "$LIKE_ESCAPE$LIKE_ESCAPE")
        .replace("%", "$LIKE_ESCAPE%")
        .replace("_", "${LIKE_ESCAPE}_")

private fun KbNotesRecord.toReviewNote(tags: Set<String>): ReviewNote =
    ReviewNote(
        id = id ?: error("kb_notes row missing id"),
        type = type ?: "",
        scope = scope ?: "",
        source = source ?: "",
        capturedAt = capturedAt?.toInstant(ZoneOffset.UTC) ?: Instant.EPOCH,
        confidence = confidence?.toDouble() ?: 0.0,
        title = title ?: "",
        vaultPath = vaultPath ?: "",
        tags = tags,
        recallCount = recallCount ?: 0,
        lastRecalledAt = lastRecalledAt?.toInstant(ZoneOffset.UTC),
    )

private const val INBOX_SCOPE = "_inbox"
private const val NEEDS_REVIEW_SCOPE = "_needs-review"
private const val NEEDS_REVIEW_PREFIX = "_inbox/_needs-review/"
private const val LIKE_ESCAPE = '!'
private val AUTO_CAPTURE_TAGS = listOf("auto-capture", "auto-memory", "auto-digest")
