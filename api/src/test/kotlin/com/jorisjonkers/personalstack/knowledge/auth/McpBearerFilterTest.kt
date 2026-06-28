package com.jorisjonkers.personalstack.knowledge.auth

import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class McpBearerFilterTest {
    private val filter =
        McpBearerFilter(
            McpBearerProperties(
                tokens =
                    mapOf(
                        "workstation" to "ws-secret",
                        "laptop" to "lap-secret",
                    ),
            ),
        )

    @Test
    fun `non-mcp paths skip the filter without 401-ing`() {
        // shouldNotFilter is protected; observe its effect via doFilter
        // — non-/mcp requests must pass through the chain even without
        // an Authorization header.
        listOf("/api/actuator/health", "/", "/mcpbutnot").forEach { uri ->
            val req = MockHttpServletRequest("GET", uri)
            val res = MockHttpServletResponse()
            val chain = mockk<FilterChain>(relaxed = true)
            filter.doFilter(req, res, chain)
            assertThat(res.status).describedAs("uri=%s", uri).isNotEqualTo(401)
            verify(exactly = 1) { chain.doFilter(req, res) }
        }
    }

    @Test
    fun `mcp paths trigger the filter`() {
        listOf("/mcp", "/mcp/tools", "/mcp/anything").forEach { uri ->
            val req = MockHttpServletRequest("POST", uri)
            val res = MockHttpServletResponse()
            val chain = mockk<FilterChain>(relaxed = true)
            filter.doFilter(req, res, chain)
            // No Authorization header → 401, chain skipped.
            assertThat(res.status).describedAs("uri=%s", uri).isEqualTo(401)
            verify(exactly = 0) { chain.doFilter(any(), any()) }
        }
    }

    @Test
    fun `valid bearer token resolves and chains the request`() {
        val req = MockHttpServletRequest("POST", "/mcp")
        req.addHeader("Authorization", "Bearer ws-secret")
        val res = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(200)
        assertThat(res.getHeader("X-User-Id")).isEqualTo("mcp:workstation")
        assertThat(req.getAttribute(McpBearerFilter.MCP_USER_ATTRIBUTE)).isEqualTo("mcp:workstation")
        verify(exactly = 1) { chain.doFilter(req, res) }
    }

    @Test
    fun `case-insensitive bearer prefix is accepted`() {
        val req = MockHttpServletRequest("POST", "/mcp")
        req.addHeader("Authorization", "bearer lap-secret")
        val res = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(req, res, chain)

        assertThat(res.getHeader("X-User-Id")).isEqualTo("mcp:laptop")
        verify(exactly = 1) { chain.doFilter(req, res) }
    }

    @Test
    fun `missing authorization header returns 401 and skips the chain`() {
        val req = MockHttpServletRequest("POST", "/mcp")
        val res = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(401)
        assertThat(res.getHeader("WWW-Authenticate")).contains("Bearer")
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `wrong token returns 401 with JSON-RPC error body`() {
        val req = MockHttpServletRequest("POST", "/mcp")
        req.addHeader("Authorization", "Bearer wrong")
        val res = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentAsString).contains("\"code\":-32001")
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `non-bearer scheme is rejected`() {
        val req = MockHttpServletRequest("POST", "/mcp")
        req.addHeader("Authorization", "Basic ws-secret")
        val res = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(401)
    }

    @Test
    fun `empty token list rejects every request — default-deny`() {
        val emptyFilter = McpBearerFilter(McpBearerProperties(tokens = emptyMap()))
        val req = MockHttpServletRequest("POST", "/mcp")
        req.addHeader("Authorization", "Bearer ws-secret")
        val res = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        emptyFilter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(401)
    }
}
