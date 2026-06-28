package com.jorisjonkers.personalstack.knowledge.auth

import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Authorization gate for the admin-only MCP tools. Reads the
 * `MCP_USER_ATTRIBUTE` request attribute set by [McpBearerFilter]
 * and checks that the resolved token name is in
 * [McpBearerProperties.adminTokens].
 *
 * The check happens inside the tool handler via [requireAdmin] —
 * that keeps `McpTool`'s handler signature stable and lets each
 * admin tool make the call exactly once at the top of its handler.
 * Failure throws [McpAuthorizationError], which the controller maps
 * to a JSON-RPC `-32001 Unauthorized` response.
 */
@Component
class AdminAuthorization(
    private val properties: McpBearerProperties,
) {
    /**
     * Resolve the current request's bearer token name and verify it
     * is on the admin allow-list. Returns the resolved user
     * attribute (`mcp:<name>`) for logging / audit.
     *
     * Calls must happen inside a Spring MVC request — outside of
     * one (e.g. a unit test that exercises the handler directly)
     * [RequestContextHolder] returns null and this method throws
     * [McpAuthorizationError]. Tests that don't want to mock the
     * request context exercise the auth gate through the controller
     * integration test instead.
     */
    fun requireAdmin(): String {
        val resolved = currentBearer()
        val name = resolved.removePrefix("mcp:")
        if (name !in properties.adminTokens) {
            denied("admin tools require a token on `knowledge.mcp.admin-tokens` — `$name` is not")
        }
        return resolved
    }

    private fun currentBearer(): String {
        val attributes =
            RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
                ?: denied("admin tools require an active MCP request")
        return attributes.request.getAttribute(McpBearerFilter.MCP_USER_ATTRIBUTE) as? String
            ?: denied("admin tools require a resolved bearer token")
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun denied(message: String): Nothing = throw McpAuthorizationError(message)
}

/**
 * Raised by [AdminAuthorization.requireAdmin] when the caller is
 * not on the admin allow-list. Distinct from a generic
 * `IllegalStateException` so the controller can map it to the
 * dedicated JSON-RPC code without false positives from validation
 * failures lower down the stack.
 */
class McpAuthorizationError(
    message: String,
) : RuntimeException(message)
