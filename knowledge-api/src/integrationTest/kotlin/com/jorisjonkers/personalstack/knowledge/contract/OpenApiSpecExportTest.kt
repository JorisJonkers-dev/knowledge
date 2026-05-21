package com.jorisjonkers.personalstack.knowledge.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// Pinned contract: hitting /api/v1/api-docs in a fully-booted Spring
// context produces the OpenAPI spec checked into the repo. The
// `exportOpenApiSpec` Gradle task runs only this tag and writes the
// JSON output to services/knowledge-api/openapi.json. The regular
// `integrationTest` task excludes the tag so it never runs as part of
// the normal pipeline — booting the container, hitting the endpoint,
// and dumping the file is fast but pointless during routine CI.
//
// Mirror of services/assistant-api/.../OpenApiSpecExportTest.kt.
@Tag("contract-export")
class OpenApiSpecExportTest : IntegrationTestBase() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    @Test
    fun `export OpenAPI spec to repo root`() {
        val result =
            mockMvc
                .perform(get("/api/v1/api-docs"))
                .andExpect(status().isOk)
                .andReturn()

        val raw = result.response.contentAsString
        // Round-trip through Jackson so the on-disk JSON is pretty-printed
        // with the project's standard sort order — keeps diffs reviewable
        // when the spec changes intentionally.
        val mapper =
            ObjectMapper().apply {
                enable(SerializationFeature.INDENT_OUTPUT)
                enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            }
        val tree = mapper.readTree(raw)
        val pretty = mapper.writeValueAsString(tree) + "\n"

        val outputPath = resolveOpenApiSpecPath()
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, pretty)
    }

    private fun resolveOpenApiSpecPath(): Path {
        // The Gradle task sets `openapi.spec.output` to the canonical
        // committed location. Fallback to `<cwd>/openapi.json` when run
        // directly from the IDE so a one-off invocation still works.
        val override = System.getProperty("openapi.spec.output")
        if (override != null) {
            return Paths.get(override)
        }
        return Paths.get(System.getProperty("user.dir")).resolve("openapi.json")
    }
}
