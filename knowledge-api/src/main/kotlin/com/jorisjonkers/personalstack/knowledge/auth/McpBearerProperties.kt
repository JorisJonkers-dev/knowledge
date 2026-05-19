package com.jorisjonkers.personalstack.knowledge.auth

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Static token allow-list for the `/mcp` endpoint. Each entry is
 * `name -> token` so audit logs can record which device a request
 * came from (e.g. `mcp:workstation`, `mcp:laptop`) without exposing
 * the secret value itself.
 *
 * The list is injected via the `KNOWLEDGE_MCP_TOKENS` env var as a
 * comma-separated `name=token` list, projected by VSO from
 * `secret/data/knowledge-system/mcp-bearer`. An empty map disables
 * the endpoint entirely, which is the default for local dev.
 */
@ConfigurationProperties("knowledge.mcp")
data class McpBearerProperties(
    val tokens: Map<String, String> = emptyMap(),
    /**
     * Token names from [tokens] that are also allowed to call the
     * admin-gated MCP tools (`add_topic`, `update_topic`,
     * `merge_topics`, `rename_tag`). Listed by name, not by secret
     * value, so the same Vault projection drives both: the secret
     * stays in `tokens`, the privilege escalation is just a string
     * comparison. Default empty — admin tools are inert until an
     * operator opts a token in.
     */
    val adminTokens: List<String> = emptyList(),
)
