plugins {
    alias(libs.plugins.extratoast.spring)
    alias(libs.plugins.extratoast.detekt)
    alias(libs.plugins.extratoast.ktlint)
    alias(libs.plugins.extratoast.testing)
    alias(libs.plugins.extratoast.jooq.codegen)
}

jooqCodegen {
    schemaName.set("PUBLIC")
    packageName.set("com.jorisjonkers.personalstack.knowledge.jooq")
    migrationLocations.set(listOf("filesystem:src/main/resources/db/migration"))
}

dependencies {
    implementation(libs.kotlin.commons.messaging)
    implementation(libs.kotlin.commons.observability)
    implementation(libs.kotlin.commons.timing)
    implementation(libs.kotlin.commons.web)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // kotlin-commons-messaging declares the shared user-registration
    // topology when the AMQP starter provides RabbitTemplate.
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // Spring AOP runtime for kotlin-commons-observability's ApplicationTracingAspect. Without
    // these the @Aspect bean isn't proxied and the advice never fires; Spring
    // Boot 4 dropped the `spring-boot-starter-aop` shortcut so pull them
    // directly the same way auth-api does.
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver:1.9.25.1")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.jooq:jooq")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.4")
    // Springdoc publishes `/api/v1/api-docs` (OpenAPI 3 JSON) over the
    // committed REST controllers under `web/`. The committed
    // `services/knowledge-api/openapi.json` is the contract knowledge-ui
    // consumes via `pnpm contract:generate`. Same dep + path convention
    // assistant-api uses.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    runtimeOnly("org.postgresql:postgresql")
    // Tracing runtime jars — adds micrometer-tracing bridge + OTLP exporter
    // so the OTel javaagent's MDC enrichment + auto-instrumentation activate
    // and traces ship to Alloy → Tempo, with traceId/spanId on log lines.
    runtimeOnly("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    testImplementation(libs.kotlin.commons.test.support)
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

// `integrationTest` runs the default-tag suite but skips the contract-
// export tag. The spec dump is a build-time artefact, not a routine
// integration verification step. `exportOpenApiSpec` runs only the springdoc
// MVC slice export test and writes the spec to disk for the drift gate.
tasks.named<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
        excludeTags("contract-export")
    }
}

tasks.register<Test>("exportOpenApiSpec") {
    description = "Exports the OpenAPI spec to services/knowledge-api/openapi.json from a springdoc MVC slice"
    group = "documentation"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform {
        includeTags("contract-export")
    }
    systemProperty("openapi.spec.output", file("openapi.json").absolutePath)
    // Always re-run: the spec is derived from the live springdoc output,
    // so caching past runs would defeat the drift gate. The CI workflow
    // diffs the freshly-written file against the committed copy.
    outputs.upToDateWhen { false }
}
