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
            "TRUNCATE TABLE kb_notes, kb_note_tags, kb_relations " +
                "RESTART IDENTITY CASCADE",
        )
    }

    companion object {
        private val postgres =
            PostgreSQLContainer("postgres:17-alpine").apply {
                withDatabaseName("knowledge_db")
                withUsername("kb_user")
                withPassword("kb_password")
            }

        // kotlin-common's RabbitMqConfig boots even though knowledge-api
        // 4a doesn't publish anything yet — the wildcard @ComponentScan
        // picks it up. A real broker is the simplest way to keep the
        // composite /actuator/health UP; spring-boot-starter-amqp also
        // wires a RabbitHealthIndicator that would otherwise fail.
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
