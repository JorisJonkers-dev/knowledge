package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.auth.AdminAuthorization
import com.jorisjonkers.personalstack.knowledge.domain.Topic
import com.jorisjonkers.personalstack.knowledge.repo.TopicRepository
import org.jooq.exception.IntegrityConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * Admin-only MCP tools that mutate the topic vocabulary or rename
 * tags across `kb_note_tags`. Gating runs through
 * [AdminAuthorization.requireAdmin] on the first line of each
 * handler; non-admin tokens get a JSON-RPC `-32001 Unauthorized`
 * response without the SQL ever running.
 *
 * Tools (Phase 4e):
 *   - `knowledge.add_topic`      — insert a new slug + aliases + description.
 *   - `knowledge.update_topic`   — replace description / aliases / is_active.
 *   - `knowledge.merge_topics`   — UPDATE `kb_notes.scope` from one slug
 *                                  to another, soft-deactivate the source.
 *   - `knowledge.rename_tag`     — UPDATE `kb_note_tags.tag` everywhere.
 *
 * Each tool is split into a descriptor + a handler so the per-method
 * length stays inside detekt's threshold (matches the
 * [ReadMcpTools] pattern). The descriptor builders are deliberately
 * verbose — the MCP `tools/list` payload is the agent-facing API,
 * and the description text is part of that contract.
 */
@Component
class AdminMcpTools(
    private val topicRepository: TopicRepository,
    private val adminAuthorization: AdminAuthorization,
) {
    private val log = LoggerFactory.getLogger(AdminMcpTools::class.java)

    fun tools(): List<McpTool> =
        listOf(
            McpTool(addTopicDescriptor(), ::addTopicHandler),
            McpTool(updateTopicDescriptor(), ::updateTopicHandler),
            McpTool(mergeTopicsDescriptor(), ::mergeTopicsHandler),
            McpTool(renameTagDescriptor(), ::renameTagHandler),
            McpTool(mergeTagsDescriptor(), ::mergeTagsHandler),
        )

    // -------- add_topic --------

    private fun addTopicDescriptor(): Map<String, Any> =
        toolDescriptor(
            name = "knowledge.add_topic",
            description =
                "Insert a new topic into `kb_topics`. `slug` is required, " +
                    "`description` and `aliases` are optional. Aliases are " +
                    "lower-cased before insert; the slug doubles as its own " +
                    "alias. Admin-only — non-admin callers get -32001.",
            required = listOf("slug"),
            properties =
                mapOf(
                    "slug" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string"),
                    "aliases" to
                        mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                        ),
                ),
        )

    private fun addTopicHandler(args: JsonNode): Map<String, Any?> {
        val actor = adminAuthorization.requireAdmin()
        val slug = JsonArguments.requireString(args, "slug")
        val description = JsonArguments.optionalString(args, "description").orEmpty()
        val aliases = JsonArguments.optionalStringArray(args, "aliases").toSet()
        try {
            topicRepository.insert(slug, description, aliases, createdBy = actor)
        } catch (exc: IntegrityConstraintViolationException) {
            log.info("admin.add_topic.duplicate", exc)
            throw IllegalStateException(
                "topic `$slug` already exists or one of its aliases collides — " +
                    "use update_topic to modify, or merge_topics to consolidate",
            )
        }
        val created =
            topicRepository.findBySlug(slug)
                ?: error("topic `$slug` missing immediately after insert")
        return mapOf("topic" to projectTopic(created), "actor" to actor)
    }

    // -------- update_topic --------

    private fun updateTopicDescriptor(): Map<String, Any> =
        toolDescriptor(
            name = "knowledge.update_topic",
            description =
                "Replace `description` / `aliases` / `is_active` on an existing " +
                    "topic. Null fields leave the corresponding column untouched. " +
                    "`aliases`, when present, replaces the alias set wholesale " +
                    "(pass the full intended set). Admin-only.",
            required = listOf("slug"),
            properties =
                mapOf(
                    "slug" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string"),
                    "aliases" to
                        mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                        ),
                    "is_active" to mapOf("type" to "boolean"),
                ),
        )

    private fun updateTopicHandler(args: JsonNode): Map<String, Any?> {
        val actor = adminAuthorization.requireAdmin()
        val slug = JsonArguments.requireString(args, "slug")
        val description = JsonArguments.optionalString(args, "description")
        val aliases =
            if (args.has("aliases")) JsonArguments.optionalStringArray(args, "aliases").toSet() else null
        val isActive = JsonArguments.optionalBoolean(args, "is_active")
        val updated = topicRepository.update(slug, description, aliases, isActive)
        if (!updated) throw IllegalStateException("topic `$slug` does not exist")
        val refreshed =
            topicRepository.findBySlug(slug)
                ?: error("topic `$slug` disappeared after update")
        return mapOf("topic" to projectTopic(refreshed), "actor" to actor)
    }

    // -------- merge_topics --------

    private fun mergeTopicsDescriptor(): Map<String, Any> =
        toolDescriptor(
            name = "knowledge.merge_topics",
            description =
                "Move every `kb_notes` row scoped `topic:<from_slug>` over to " +
                    "`topic:<into_slug>` and soft-deactivate the source slug. " +
                    "Returns the number of notes that moved. Vault-path " +
                    "rewrites for already-promoted notes are not handled here " +
                    "— surface the count and run a vault sweep separately. " +
                    "Admin-only.",
            required = listOf("from_slug", "into_slug"),
            properties =
                mapOf(
                    "from_slug" to mapOf("type" to "string"),
                    "into_slug" to mapOf("type" to "string"),
                ),
        )

    private fun mergeTopicsHandler(args: JsonNode): Map<String, Any?> {
        val actor = adminAuthorization.requireAdmin()
        val fromSlug = JsonArguments.requireString(args, "from_slug")
        val intoSlug = JsonArguments.requireString(args, "into_slug")
        if (fromSlug == intoSlug) {
            throw IllegalStateException("merge_topics: from_slug and into_slug must differ")
        }
        if (topicRepository.findBySlug(intoSlug) == null) {
            throw IllegalStateException("merge_topics: into_slug `$intoSlug` is not defined")
        }
        val moved = topicRepository.mergeInto(fromSlug, intoSlug)
        return mapOf(
            "from_slug" to fromSlug,
            "into_slug" to intoSlug,
            "notes_moved" to moved,
            "actor" to actor,
        )
    }

    // -------- rename_tag --------

    private fun renameTagDescriptor(): Map<String, Any> =
        toolDescriptor(
            name = "knowledge.rename_tag",
            description =
                "Rename a tag everywhere it appears in `kb_note_tags`. Returns " +
                    "the number of rows touched. The PK on `kb_note_tags` is " +
                    "`(note_id, tag)`, so an UPDATE fails loudly if some note " +
                    "already carries both the old and the new tag — dedupe " +
                    "first via `list_tags` or `find_duplicates`. Admin-only.",
            required = listOf("from", "to"),
            properties =
                mapOf(
                    "from" to mapOf("type" to "string"),
                    "to" to mapOf("type" to "string"),
                ),
        )

    private fun renameTagHandler(args: JsonNode): Map<String, Any?> {
        val actor = adminAuthorization.requireAdmin()
        val fromTag = JsonArguments.requireString(args, "from")
        val toTag = JsonArguments.requireString(args, "to")
        if (fromTag == toTag) throw IllegalStateException("rename_tag: from and to must differ")
        val touched = topicRepository.renameTag(fromTag, toTag)
        return mapOf(
            "from" to fromTag,
            "to" to toTag,
            "rows_touched" to touched,
            "actor" to actor,
        )
    }

    // -------- merge_tags --------

    private fun mergeTagsDescriptor(): Map<String, Any> =
        toolDescriptor(
            name = "knowledge.merge_tags",
            description =
                "Merge multiple near-duplicate tags into a single canonical tag. `from` " +
                    "is the list of tags to fold; `into` is the survivor. Idempotent — " +
                    "a re-run with the same arguments is a no-op. Notes carrying both a " +
                    "source tag and the destination drop the source row rather than " +
                    "duplicate the PK. Use after `list_tag_candidates` surfaces a cluster " +
                    "for review. Admin-only.",
            required = listOf("from", "into"),
            properties =
                mapOf(
                    "from" to
                        mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "minItems" to 1,
                        ),
                    "into" to mapOf("type" to "string"),
                ),
        )

    private fun mergeTagsHandler(args: JsonNode): Map<String, Any?> {
        val actor = adminAuthorization.requireAdmin()
        val fromTags = JsonArguments.optionalStringArray(args, "from")
        val intoTag = JsonArguments.requireString(args, "into")
        if (fromTags.isEmpty()) error("merge_tags: `from` must contain at least one source tag")
        val result = topicRepository.mergeTags(fromTags, intoTag)
        log.info(
            "merge_tags applied: from={} into={} renamed={} dropped_dupes={} actor={}",
            fromTags,
            intoTag,
            result.rowsRenamed,
            result.rowsDeletedAsDupes,
            actor,
        )
        return mapOf(
            "from" to fromTags,
            "into" to intoTag,
            "rows_renamed" to result.rowsRenamed,
            "rows_dropped_as_dupes" to result.rowsDeletedAsDupes,
            "actor" to actor,
        )
    }

    // -------- shared projection --------

    private fun projectTopic(topic: Topic): Map<String, Any?> =
        mapOf(
            "slug" to topic.slug,
            "description" to topic.description,
            "aliases" to topic.aliases.toList().sorted(),
            "created_at" to topic.createdAt.toString(),
            "created_by" to topic.createdBy,
            "updated_at" to topic.updatedAt.toString(),
            "is_active" to topic.isActive,
        )
}
