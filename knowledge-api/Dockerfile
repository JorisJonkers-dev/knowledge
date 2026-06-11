# syntax=docker/dockerfile:1

FROM gradle:9.4-jdk21-alpine AS build
WORKDIR /app

# Layer 1: build scripts only — keeps dependency resolution cached unless
# one of these files changes.
COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/ gradle/
COPY services/auth-api/build.gradle.kts services/auth-api/
COPY services/system-tests/build.gradle.kts services/system-tests/
COPY services/knowledge-api/build.gradle.kts services/knowledge-api/

RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :services:knowledge-api:dependencies --no-daemon || true

# Layer 2: source.
COPY services/knowledge-api/src/main/ services/knowledge-api/src/main/
RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :services:knowledge-api:bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine AS otel
RUN apk add --no-cache curl && \
    curl -fsSL -o /otel-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar"

# Runtime base mirrors auth-api / assistant-api: BellSoft Liberica JDK 21
# with CRaC patches on Debian slim. CRaC training stage can be added in a
# follow-up the same way auth-api's was; for Phase 4a the cold-start tax
# is acceptable since the service is in-cluster only and isn't on the
# forward-auth hot path.
FROM bellsoft/liberica-runtime-container:jdk-21-crac-slim-glibc
WORKDIR /app
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA}
COPY --from=build /app/services/knowledge-api/build/libs/*.jar app.jar
COPY --from=otel /otel-javaagent.jar otel-javaagent.jar
COPY services/knowledge-api/entrypoint.sh /app/entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh", "-javaagent:otel-javaagent.jar", "-jar", "app.jar"]
