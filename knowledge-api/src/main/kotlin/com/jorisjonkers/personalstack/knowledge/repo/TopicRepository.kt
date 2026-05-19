package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.Topic
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNoteTags.KB_NOTE_TAGS
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNotes.KB_NOTES
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbTopicAliases.KB_TOPIC_ALIASES
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbTopics.KB_TOPICS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset

/**
 * Read + write access to the topic-vocabulary tables (`kb_topics`,
 * `kb_topic_aliases`). Kept narrow: list / find / insert / update.
 * Bulk-rename of `kb_notes.scope` on `merge_topics` lives in its own
 * follow-up rather than here — keeps this repository's responsibility
 * to the vocabulary tables alone.
 */
@Repository
class TopicRepository(
    private val dsl: DSLContext,
) {
    fun listActive(): List<Topic> {
        val topicRows =
            dsl
                .selectFrom(KB_TOPICS)
                .where(KB_TOPICS.IS_ACTIVE.isTrue)
                .orderBy(KB_TOPICS.SLUG.asc())
                .fetch()
        if (topicRows.isEmpty()) return emptyList()
        val aliasesBySlug = aliasesFor(topicRows.mapNotNull { it.slug })
        return topicRows.map { row ->
            Topic(
                slug = row.slug ?: "",
                description = row.description ?: "",
                aliases = aliasesBySlug[row.slug].orEmpty(),
                createdAt = row.createdAt?.toInstant(ZoneOffset.UTC) ?: Instant.EPOCH,
                createdBy = row.createdBy ?: "",
                updatedAt = row.updatedAt?.toInstant(ZoneOffset.UTC) ?: Instant.EPOCH,
                isActive = row.isActive ?: true,
            )
        }
    }

    fun findBySlug(slug: String): Topic? {
        val row =
            dsl
                .selectFrom(KB_TOPICS)
                .where(KB_TOPICS.SLUG.eq(slug))
                .fetchOne() ?: return null
        return Topic(
            slug = row.slug ?: "",
            description = row.description ?: "",
            aliases = aliasesFor(listOf(slug))[slug].orEmpty(),
            createdAt = row.createdAt?.toInstant(ZoneOffset.UTC) ?: Instant.EPOCH,
            createdBy = row.createdBy ?: "",
            updatedAt = row.updatedAt?.toInstant(ZoneOffset.UTC) ?: Instant.EPOCH,
            isActive = row.isActive ?: true,
        )
    }

    /**
     * Insert a new topic with its aliases. The slug doubles as its
     * own alias by convention so any later [findSlugByAlias] lookup
     * hits regardless of whether the caller passes the slug or one
     * of its aliases. Caller is responsible for catching duplicate
     * key violations and turning them into an MCP error response.
     */
    fun insert(
        slug: String,
        description: String,
        aliases: Set<String>,
        createdBy: String,
        now: Instant = Instant.now(),
    ) {
        val ts = now.atOffset(ZoneOffset.UTC).toLocalDateTime()
        dsl
            .insertInto(KB_TOPICS)
            .set(KB_TOPICS.SLUG, slug)
            .set(KB_TOPICS.DESCRIPTION, description)
            .set(KB_TOPICS.CREATED_AT, ts)
            .set(KB_TOPICS.CREATED_BY, createdBy)
            .set(KB_TOPICS.UPDATED_AT, ts)
            .set(KB_TOPICS.IS_ACTIVE, true)
            .execute()
        insertAliases(slug, aliases + slug)
    }

    /**
     * Replace the mutable fields of an existing topic. Null values
     * leave the corresponding column untouched. When `aliases` is
     * non-null the alias set is replaced wholesale (the caller can't
     * incrementally add aliases via this method — pass the full
     * intended set).
     */
    fun update(
        slug: String,
        description: String?,
        aliases: Set<String>?,
        isActive: Boolean?,
        now: Instant = Instant.now(),
    ): Boolean {
        val ts = now.atOffset(ZoneOffset.UTC).toLocalDateTime()
        var step = dsl.update(KB_TOPICS).set(KB_TOPICS.UPDATED_AT, ts)
        if (description != null) step = step.set(KB_TOPICS.DESCRIPTION, description)
        if (isActive != null) step = step.set(KB_TOPICS.IS_ACTIVE, isActive)
        val rowsAffected = step.where(KB_TOPICS.SLUG.eq(slug)).execute()
        if (rowsAffected == 0) return false
        if (aliases != null) {
            dsl.deleteFrom(KB_TOPIC_ALIASES).where(KB_TOPIC_ALIASES.SLUG.eq(slug)).execute()
            insertAliases(slug, aliases + slug)
        }
        return true
    }

    private fun aliasesFor(slugs: List<String>): Map<String, Set<String>> {
        if (slugs.isEmpty()) return emptyMap()
        return dsl
            .select(KB_TOPIC_ALIASES.SLUG, KB_TOPIC_ALIASES.ALIAS_LOWER)
            .from(KB_TOPIC_ALIASES)
            .where(KB_TOPIC_ALIASES.SLUG.`in`(slugs))
            .fetch()
            .groupBy({ it.value1() ?: "" }, { it.value2() ?: "" })
            .mapValues { (_, aliases) -> aliases.filter { it.isNotBlank() }.toSet() }
    }

    private fun insertAliases(
        slug: String,
        aliases: Set<String>,
    ) {
        aliases
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
            .forEach { alias ->
                dsl
                    .insertInto(KB_TOPIC_ALIASES)
                    .set(KB_TOPIC_ALIASES.ALIAS_LOWER, alias)
                    .set(KB_TOPIC_ALIASES.SLUG, slug)
                    .execute()
            }
    }

    /**
     * Bulk-rename every note scoped `topic:<from>` to `topic:<into>`
     * and soft-deactivate the `from` slug. Returns the number of
     * note rows touched so the caller can report it.
     *
     * Vault-path rewrites stay out of scope here — the curator owns
     * `vault_path` and re-promotes its slice of the inbox per pass.
     * For permanent paths on already-promoted notes, surface the
     * affected ids and let the operator run a vault sweep separately.
     */
    fun mergeInto(
        fromSlug: String,
        intoSlug: String,
        now: Instant = Instant.now(),
    ): Int {
        val ts = now.atOffset(ZoneOffset.UTC).toLocalDateTime()
        val fromScope = "$TOPIC_SCOPE_PREFIX$fromSlug"
        val intoScope = "$TOPIC_SCOPE_PREFIX$intoSlug"
        val touched =
            dsl
                .update(KB_NOTES)
                .set(KB_NOTES.SCOPE, intoScope)
                .set(KB_NOTES.UPDATED_AT, ts)
                .where(KB_NOTES.SCOPE.eq(fromScope))
                .execute()
        dsl
            .update(KB_TOPICS)
            .set(KB_TOPICS.IS_ACTIVE, false)
            .set(KB_TOPICS.UPDATED_AT, ts)
            .where(KB_TOPICS.SLUG.eq(fromSlug))
            .execute()
        return touched
    }

    /**
     * Bulk-rename a tag everywhere it appears in `kb_note_tags`. The
     * PK is `(note_id, tag)`, so a straight UPDATE is fine — there's
     * no per-row uniqueness to fight with a temporary collision.
     * Returns the number of rows touched.
     */
    fun renameTag(
        fromTag: String,
        toTag: String,
    ): Int =
        dsl
            .update(KB_NOTE_TAGS)
            .set(KB_NOTE_TAGS.TAG, toTag)
            .where(KB_NOTE_TAGS.TAG.eq(fromTag))
            .execute()

    private companion object {
        const val TOPIC_SCOPE_PREFIX = "topic:"
    }
}
