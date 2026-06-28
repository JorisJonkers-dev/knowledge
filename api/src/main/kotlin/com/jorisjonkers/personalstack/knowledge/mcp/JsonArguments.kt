@file:Suppress("DEPRECATION") // Jackson 3 deprecated asText()/isTextual.

package com.jorisjonkers.personalstack.knowledge.mcp

import tools.jackson.databind.JsonNode

/**
 * `params.arguments` extraction helpers shared between the capture
 * tools and the read tools.
 *   - `requireString`: throws on missing / non-text / whitespace-only.
 *   - `optionalString`: returns null for missing / blank.
 *   - `rawText`: like `optionalString` but preserves whitespace —
 *     `recall` uses this so an accidentally-blank query yields 0
 *     hits rather than a 500.
 */
internal object JsonArguments {
    fun requireString(
        node: JsonNode,
        field: String,
    ): String = optionalString(node, field) ?: error("missing required field: $field")

    fun optionalString(
        node: JsonNode,
        field: String,
    ): String? =
        node
            .get(field)
            ?.takeUnless { it.isNull }
            ?.takeIf { it.isTextual }
            ?.asText()
            ?.takeIf { it.isNotBlank() }

    fun rawText(
        node: JsonNode,
        field: String,
    ): String? =
        node
            .get(field)
            ?.takeUnless { it.isNull }
            ?.takeIf { it.isTextual }
            ?.asText()

    fun optionalDouble(
        node: JsonNode,
        field: String,
    ): Double? =
        node
            .get(field)
            ?.takeUnless { it.isNull }
            ?.takeIf { it.isNumber }
            ?.asDouble()

    fun optionalInt(
        node: JsonNode,
        field: String,
    ): Int? =
        node
            .get(field)
            ?.takeUnless { it.isNull }
            ?.takeIf { it.isIntegralNumber }
            ?.asInt()

    fun optionalBoolean(
        node: JsonNode,
        field: String,
    ): Boolean? =
        node
            .get(field)
            ?.takeUnless { it.isNull }
            ?.takeIf { it.isBoolean }
            ?.asBoolean()

    fun optionalStringArray(
        node: JsonNode,
        field: String,
    ): List<String> {
        val arr = node.get(field) ?: return emptyList()
        if (!arr.isArray) return emptyList()
        return arr.mapNotNull { if (it.isTextual) it.asText() else null }
    }
}
