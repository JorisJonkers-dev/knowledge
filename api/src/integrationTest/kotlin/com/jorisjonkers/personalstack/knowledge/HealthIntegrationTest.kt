package com.jorisjonkers.personalstack.knowledge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Smoke test for the Phase 4a skeleton: confirms the application boots,
 * Flyway runs the V1 provenance migration against the Testcontainers
 * Postgres, and `/api/actuator/health` reports the composite UP with a
 * healthy `db` contributor. Mirrors the shape of auth-api's
 * HealthIntegrationTest but without security (knowledge-api 4a doesn't
 * wire in spring-security yet).
 */
class HealthIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
    }

    @Test
    fun `composite health returns 200 with status UP and a db contributor`() {
        val result = mockMvc.get("/api/actuator/health").andReturn()

        assertThat(result.response.status)
            .describedAs("composite /api/actuator/health body=%s", result.response.contentAsString)
            .isEqualTo(200)

        val body = objectMapper.readTree(result.response.contentAsString)
        assertThat(body["status"].asText()).isEqualTo("UP")

        val components = body["components"] ?: body["details"] ?: error("no components: $body")
        assertThat(components.fieldNames().asSequence().toList())
            .describedAs("expected core infra contributors to be present: $body")
            .contains("db", "ping")
    }
}
