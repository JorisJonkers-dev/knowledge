package com.jorisjonkers.personalstack.knowledge

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer

@Tag("integration")
@SpringBootTest
@Testcontainers
abstract class IntegrationTestBase {
    @Autowired
    private lateinit var dsl: DSLContext

    @AfterEach
    fun resetSharedState() {
        dsl.execute(
            "TRUNCATE TABLE kb_notes, kb_note_tags, kb_relations, kb_audit " +
                "RESTART IDENTITY CASCADE",
        )
    }

    companion object {
        // pgvector/pgvector:pg17 is the upstream Postgres image with the
        // pgvector extension pre-built. Required since V9 adds the
        // `embedding vector(1024)` column and the HNSW index — the bare
        // `postgres:17-alpine` image errors on `CREATE EXTENSION vector`.
        // The image still presents as `PostgreSQLContainer` (PG 17 with
        // the same defaults); wrapping it via `DockerImageName.asCompatibleSubstituteFor`
        // keeps Testcontainers' Postgres-specific waitFor / jdbcUrl logic.
        private val postgres =
            PostgreSQLContainer(
                org.testcontainers.utility.DockerImageName
                    .parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"),
            ).apply {
                withDatabaseName("knowledge_db")
                withUsername("kb_user")
                withPassword("kb_password")
            }

        // kotlin-commons-messaging declares the shared RabbitMQ topology.
        // A real broker is the simplest way to keep the composite
        // /actuator/health UP; spring-boot-starter-amqp also wires a
        // RabbitHealthIndicator that would otherwise fail.
        private val rabbitmq = RabbitMQContainer("rabbitmq:3-management-alpine")

        init {
            postgres.start()
            rabbitmq.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.rabbitmq.host") { rabbitmq.host }
            registry.add("spring.rabbitmq.port") { rabbitmq.amqpPort.toString() }
        }
    }
}
