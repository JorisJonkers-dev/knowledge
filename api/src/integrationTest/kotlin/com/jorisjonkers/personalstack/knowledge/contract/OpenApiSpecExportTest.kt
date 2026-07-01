package com.jorisjonkers.personalstack.knowledge.contract

import com.jorisjonkers.personalstack.common.test.openapi.OpenApiSliceExporter
import com.jorisjonkers.personalstack.common.test.openapi.OpenApiWebMvcSliceConfiguration
import com.jorisjonkers.personalstack.knowledge.audit.AuditService
import com.jorisjonkers.personalstack.knowledge.discovery.DiscoveryService
import com.jorisjonkers.personalstack.knowledge.discovery.TagClusterService
import com.jorisjonkers.personalstack.knowledge.installer.InstallerController
import com.jorisjonkers.personalstack.knowledge.recall.RecallService
import com.jorisjonkers.personalstack.knowledge.review.ReviewService
import com.jorisjonkers.personalstack.knowledge.web.KnowledgeAuditController
import com.jorisjonkers.personalstack.knowledge.web.KnowledgeDiscoveryController
import com.jorisjonkers.personalstack.knowledge.web.KnowledgeReadController
import com.jorisjonkers.personalstack.knowledge.web.KnowledgeReviewController
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import java.nio.file.Path
import java.nio.file.Paths

// Pinned contract: hitting /api/v1/api-docs in the springdoc MVC slice
// produces the OpenAPI spec checked into the repo. The `exportOpenApiSpec`
// Gradle task runs only this tag and writes the JSON output to
// client-spec/openapi/knowledge-api.json.
@Tag("contract-export")
@WebMvcTest(
    controllers = [
        InstallerController::class,
        KnowledgeAuditController::class,
        KnowledgeDiscoveryController::class,
        KnowledgeReadController::class,
        KnowledgeReviewController::class,
    ],
    properties = [
        "spring.jackson.property-naming-strategy=SNAKE_CASE",
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api/v1/api-docs",
        "springdoc.writer-with-default-pretty-printer=true",
        "springdoc.writer-with-order-by-keys=true",
    ],
)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(
    classes = [
        OpenApiSpecExportTest.Application::class,
        OpenApiSpecExportTest.Collaborators::class,
        OpenApiWebMvcSliceConfiguration::class,
        InstallerController::class,
        KnowledgeAuditController::class,
        KnowledgeDiscoveryController::class,
        KnowledgeReadController::class,
        KnowledgeReviewController::class,
    ],
)
class OpenApiSpecExportTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
    ) {
        @Test
        fun exportOpenApiSpecToRepoRoot() {
            OpenApiSliceExporter.writeJson(mockMvc, resolveOpenApiSpecPath(), "/api/v1/api-docs")
        }

        private fun resolveOpenApiSpecPath(): Path {
            // The Gradle task sets `openapi.spec.output` to the canonical
            // committed location. Fallback to `<cwd>/knowledge-api.json` when run
            // directly from the IDE so a one-off invocation still works.
            val override = System.getProperty("openapi.spec.output")
            if (override != null) {
                return Paths.get(override)
            }
            return Paths.get(System.getProperty("user.dir")).resolve("knowledge-api.json")
        }

        @SpringBootConfiguration
        class Application

        @TestConfiguration(proxyBeanMethods = false)
        class Collaborators {
            @Bean
            fun auditService(): AuditService = mockk(relaxed = true)

            @Bean
            fun discoveryService(): DiscoveryService = mockk(relaxed = true)

            @Bean
            fun recallService(): RecallService = mockk(relaxed = true)

            @Bean
            fun reviewService(): ReviewService = mockk(relaxed = true)

            @Bean
            fun tagClusterService(): TagClusterService = mockk(relaxed = true)
        }
    }
