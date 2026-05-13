plugins {
    id("spring-conventions")
    id("detekt-conventions")
    id("ktlint-conventions")
    id("testing-conventions")
    id("jooq-codegen-conventions")
}

jooqCodegen {
    schemaName = "public"
    packageName = "com.jorisjonkers.personalstack.knowledge.jooq"
    migrationLocations = listOf("filesystem:src/main/resources/db/migration")
}

dependencies {
    implementation(project(":libs:kotlin-common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // kotlin-common ships a shared RabbitMqConfig that gets picked up by
    // the wildcard @ComponentScan in every service application; pulling
    // in the amqp starter is what makes its DirectExchange / Queue beans
    // class-load. Phase 4c (queue publisher) is the consumer.
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // Spring AOP runtime for kotlin-common's ApplicationTracingAspect. Without
    // these the @Aspect bean isn't proxied and the advice never fires; Spring
    // Boot 4 dropped the `spring-boot-starter-aop` shortcut so pull them
    // directly the same way auth-api does.
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver:1.9.25.1")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.jooq:jooq")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.2")
    runtimeOnly("org.postgresql:postgresql")
    // Tracing runtime jars — adds micrometer-tracing bridge + OTLP exporter
    // so the OTel javaagent's MDC enrichment + auto-instrumentation activate
    // and traces ship to Alloy → Tempo, with traceId/spanId on log lines.
    runtimeOnly("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
