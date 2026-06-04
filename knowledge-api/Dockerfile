FROM gradle:9.4-jdk21-alpine AS build
WORKDIR /app

# Layer 1: build scripts only — keeps dependency resolution cached unless
# one of these files changes.
COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/ gradle/
COPY build-logic/ build-logic/
COPY libs/kotlin-common/build.gradle.kts libs/kotlin-common/
COPY services/auth-api/build.gradle.kts services/auth-api/
COPY services/assistant-api/build.gradle.kts services/assistant-api/
COPY services/agent-gateway/build.gradle.kts services/agent-gateway/
COPY services/system-tests/build.gradle.kts services/system-tests/
COPY services/knowledge-api/build.gradle.kts services/knowledge-api/

RUN gradle :services:knowledge-api:dependencies --no-daemon || true

# Layer 2: source.
COPY libs/kotlin-common/src/main/ libs/kotlin-common/src/main/
COPY services/knowledge-api/src/main/ services/knowledge-api/src/main/
RUN gradle :services:knowledge-api:bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine AS otel
RUN apk add --no-cache curl && \
    curl -fsSL -o /otel-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar"

# Training-capable image. Used only by the knowledge-api CRaC job in
# build-and-publish.yml: launched alongside Postgres (pgvector) + RabbitMQ
# sidecars, drives the `crac-train` Spring profile (CracTrainingRunner from
# kotlin-common hits each warmup endpoint locally then calls
# Core.checkpointRestore()), and dumps the JVM checkpoint to
# /opt/crac/checkpoint before exiting 137. CI copies the checkpoint dir out
# and feeds it back as the final stage's CRAC_CHECKPOINT_DIR. Production never
# runs this stage.
FROM bellsoft/liberica-runtime-container:jdk-21-crac-slim-glibc AS train
WORKDIR /app
COPY --from=build /app/services/knowledge-api/build/libs/*.jar app.jar
COPY --from=otel /otel-javaagent.jar otel-javaagent.jar
RUN mkdir -p /opt/crac/checkpoint
EXPOSE 8080
ENTRYPOINT ["java", \
    "-XX:CRaCCheckpointTo=/opt/crac/checkpoint", \
    "-XX:+UseZGC", \
    "-XX:MaxRAMPercentage=75", \
    "-Dspring.profiles.active=crac-train", \
    "-javaagent:otel-javaagent.jar", \
    "-jar", "app.jar"]

# Runtime base mirrors auth-api / assistant-api: BellSoft Liberica JDK 21
# with CRaC patches on Debian slim.
FROM bellsoft/liberica-runtime-container:jdk-21-crac-slim-glibc
WORKDIR /app
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA}
COPY --from=build /app/services/knowledge-api/build/libs/*.jar app.jar
COPY --from=otel /otel-javaagent.jar otel-javaagent.jar
COPY services/knowledge-api/entrypoint.sh /app/entrypoint.sh
COPY services/knowledge-api/crac-launch.sh /app/crac-launch.sh
# Bake the CRaC checkpoint. Defaults to the empty placeholder dir so a plain
# `docker build` (local / feature branch) produces a checkpoint-less image
# that cold-starts; the CI CRaC job overrides CRAC_CHECKPOINT_DIR with the
# real CRIU checkpoint it generated from this same SHA's jar. crac-launch.sh
# treats a dir with no *.img files as "no checkpoint" and cold-starts.
ARG CRAC_CHECKPOINT_DIR=services/knowledge-api/crac-empty
COPY ${CRAC_CHECKPOINT_DIR}/ /opt/crac/checkpoint/
EXPOSE 8080
# Default entrypoint is the plain cold-start path; the k8s deployment overrides
# `command` to source Vault secrets and then exec /app/crac-launch.sh, which
# chooses restore vs cold start.
ENTRYPOINT ["/app/entrypoint.sh", "-javaagent:otel-javaagent.jar", "-jar", "app.jar"]
