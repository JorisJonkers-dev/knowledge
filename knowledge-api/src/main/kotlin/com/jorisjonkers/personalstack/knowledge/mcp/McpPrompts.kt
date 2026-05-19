package com.jorisjonkers.personalstack.knowledge.mcp

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * MCP `prompts` capability (spec 2025-06-18). Surfaces a small set
 * of pre-built user messages the client can offer as slash-style
 * commands (`/mcp__knowledge__<name>`). Unlike hooks — which fire
 * automatically on client-side events — prompts are user-triggered;
 * they replace the hand-written `~/.claude/skills/<name>` affordance
 * with a pure MCP-protocol surface that needs no local install.
 *
 * Each prompt resolves to a `messages: [{role, content}]` payload
 * the client treats as if the user wrote it. The agent then sees
 * the message and follows its instruction — typically a call into
 * one of the existing `knowledge.<...>` tools shaped for the prompt's
 * intent.
 *
 * Kept narrow on purpose: three prompts (`recall_for_task`,
 * `capture_lesson_about`, `topics_audit`) is enough to demonstrate
 * the surface. More can ride later PRs as concrete use cases
 * justify the slot.
 */
@Component
class McpPrompts {
    fun list(): List<Map<String, Any?>> = DEFINITIONS.map(::describe)

    fun get(
        name: String,
        arguments: JsonNode?,
    ): PromptResult? {
        val definition = DEFINITIONS.firstOrNull { it.name == name } ?: return null
        return definition.build(arguments)
    }

    private fun describe(prompt: PromptDefinition): Map<String, Any?> =
        mapOf(
            "name" to prompt.name,
            "description" to prompt.description,
            "arguments" to prompt.arguments.map { it.describe() },
        )

    private companion object {
        val DEFINITIONS: List<PromptDefinition> =
            listOf(
                RecallForTaskPrompt,
                CaptureLessonAboutPrompt,
                TopicsAuditPrompt,
            )
    }
}

/**
 * Single resolved prompt: an optional description plus the
 * `role` / `content` messages the client surfaces to the agent.
 * Mirrors the MCP `GetPromptResult` shape so projection to JSON
 * is a one-liner in the controller.
 */
data class PromptResult(
    val description: String,
    val messages: List<PromptMessage>,
)

data class PromptMessage(
    val role: String,
    val text: String,
)

data class PromptArgument(
    val name: String,
    val description: String,
    val required: Boolean,
) {
    fun describe(): Map<String, Any?> =
        mapOf(
            "name" to name,
            "description" to description,
            "required" to required,
        )
}

/**
 * Internal contract — each concrete prompt knows its metadata + how
 * to render itself given the caller's arguments. Implementations
 * stay as `object`s so the registry is a flat list, not a
 * Spring-managed bean graph.
 */
internal interface PromptDefinition {
    val name: String
    val description: String
    val arguments: List<PromptArgument>

    fun build(arguments: JsonNode?): PromptResult
}

internal object RecallForTaskPrompt : PromptDefinition {
    override val name: String = "recall_for_task"
    override val description: String =
        "Recall context from the knowledge base for a task description. Surfaces relevant " +
            "prior lessons / decisions / notes before work starts. Use proactively at the top " +
            "of any non-trivial task."
    override val arguments: List<PromptArgument> =
        listOf(
            PromptArgument(
                name = "task",
                description = "Natural-language description of what you're about to work on.",
                required = true,
            ),
        )

    override fun build(arguments: JsonNode?): PromptResult {
        val task =
            arguments?.let { JsonArguments.optionalString(it, "task") }.orEmpty().ifBlank { "the current task" }
        val text =
            """
            I'm about to work on: $task.

            Before starting, please run the following discovery steps so the work is informed by what's already in the knowledge base:

            1. Call `knowledge.recall` with a query distilled from the task description, `scope` omitted (curated default), `limit` 5.
            2. Call `knowledge.list_topics` to see which topic slugs are in use; pick the one(s) closest to the task before capturing anything.
            3. If any recall hit looks directly relevant, fetch its full body with `knowledge.get_note(id)` and walk its 1-hop neighbours via `knowledge.relations(id, depth=1)`.

            Then summarise the most relevant hits in 2-3 lines and start the work.
            """.trimIndent()
        return PromptResult(
            description = "Recall knowledge-base context for a specific task.",
            messages = listOf(PromptMessage(role = "user", text = text)),
        )
    }
}

internal object CaptureLessonAboutPrompt : PromptDefinition {
    override val name: String = "capture_lesson_about"
    override val description: String =
        "Capture a lesson learned via `knowledge.capture_lesson`, with a guided structure for " +
            "title / body / scope / tags. Use after solving a non-trivial problem worth keeping."
    override val arguments: List<PromptArgument> =
        listOf(
            PromptArgument(
                name = "subject",
                description = "One-line description of the lesson. Used as the capture title.",
                required = true,
            ),
        )

    override fun build(arguments: JsonNode?): PromptResult {
        val subject =
            arguments
                ?.let { JsonArguments.optionalString(it, "subject") }
                .orEmpty()
                .ifBlank { "<one-line subject>" }
        val text =
            """
            Capture a lesson about: $subject.

            Please call `knowledge.capture_lesson` with:
            - `title`: a concise restatement of the subject above.
            - `body`: a markdown body covering (a) the situation that surfaced the lesson, (b) the root cause / mechanism, (c) the resolution, (d) when this generalises beyond the immediate case.
            - `scope`: pick the closest slug from `knowledge.list_topics` (use `knowledge.suggest_topic` once it lands) or leave omitted to let the curator classify.
            - `tags`: 2-5 tags drawn from `knowledge.list_tags` to match existing spellings — avoid drift like `kotlin` vs `Kotlin` vs `kt`.

            Confirm the capture by reading back the assigned id and the resolved scope.
            """.trimIndent()
        return PromptResult(
            description = "Guided capture_lesson with structure + tag-drift avoidance.",
            messages = listOf(PromptMessage(role = "user", text = text)),
        )
    }
}

internal object TopicsAuditPrompt : PromptDefinition {
    override val name: String = "topics_audit"
    override val description: String =
        "Audit the knowledge-base topic vocabulary + inbox queue. Surfaces topics that are " +
            "thin, slugs that look like duplicates, and notes that haven't been classified yet."
    override val arguments: List<PromptArgument> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    override fun build(arguments: JsonNode?): PromptResult {
        val text =
            """
            Run a knowledge-base vocabulary audit:

            1. Call `knowledge.list_topics` with `limit` 100 — flag any topic with a note_count of 1 or 2 (thin) and any pair of slugs that look like near-duplicates.
            2. Call `knowledge.list_tags` with `limit` 100 — flag tag pairs that differ only in casing or trivial spelling.
            3. Call `knowledge.list_inbox` with `limit` 20 — list anything still pending the curator's classifier pass.

            Report findings as three short sections (thin topics, duplicate-looking tags, pending inbox). Propose specific `knowledge.merge_topics` / `knowledge.rename_tag` calls for the candidates rather than just naming them. Don't actually run the mutations.
            """.trimIndent()
        return PromptResult(
            description = "Audit pass over topics + tags + pending inbox.",
            messages = listOf(PromptMessage(role = "user", text = text)),
        )
    }
}
