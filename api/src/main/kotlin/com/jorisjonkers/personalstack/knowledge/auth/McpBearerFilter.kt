package com.jorisjonkers.personalstack.knowledge.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Enforces bearer-token auth on the `/mcp` endpoint and everything
 * under `/mcp/`. Public Traefik lets these paths through without
 * forward-auth (CLI tools don't do SSO cookies), so this filter is
 * the only gate between the open internet and the MCP transport.
 *
 * On success the resolved token name is exposed as `X-User-Id`
 * (`mcp:<name>`) for downstream logging / span attributes; on failure
 * the response is a 401 with a small JSON-RPC error body so the
 * client transport surfaces the cause rather than treating it as a
 * transport-level disconnect.
 *
 * Declared as a plain class (no `@Component`) and registered as a
 * `@Bean` in `KnowledgeApiApplication`. kotlin-commons-observability's
 * `ApplicationTracingAspect` proxies every `@Component` with CGLIB,
 * which can't proxy `GenericFilterBean.init(FilterConfig)` (it's
 * final). The proxied init() left the inherited `logger` field null
 * and crashed Tomcat startup with
 * `Cannot invoke "Log.isDebugEnabled()" because "this.logger" is
 * null`. The kotlin-commons-timing filters use the same `@Bean`
 * trick for the same reason.
 */
class McpBearerFilter(
    private val properties: McpBearerProperties,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(McpBearerFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return !(path == "/mcp" || path.startsWith("/mcp/"))
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val resolved = resolveToken(request.getHeader(HttpHeaders.AUTHORIZATION))
        if (resolved == null) {
            writeUnauthorized(response)
            return
        }

        request.setAttribute(MCP_USER_ATTRIBUTE, "mcp:$resolved")
        response.setHeader("X-User-Id", "mcp:$resolved")
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(authorization: String?): String? {
        if (properties.tokens.isEmpty()) {
            log.debug("MCP bearer token list is empty — rejecting all /mcp requests")
        }
        return extractBearerToken(authorization)?.let { presented ->
            // Linear scan with constantTimeEquals — the token list is
            // device-scale (handfuls of entries), not user-scale, so
            // hashing-then-lookup buys nothing.
            properties.tokens.firstNotNullOfOrNull { (name, expected) ->
                if (constantTimeEquals(presented, expected)) name else null
            }
        }
    }

    private fun extractBearerToken(authorization: String?): String? {
        val header = authorization?.trim().orEmpty()
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return header.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
    }

    private fun writeUnauthorized(response: HttpServletResponse) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mcp\"")
        response.writer.write(
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001," +
                "\"message\":\"Unauthorized: present a valid bearer token\"},\"id\":null}",
        )
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        const val MCP_USER_ATTRIBUTE = "knowledge.mcp.user"
    }
}
