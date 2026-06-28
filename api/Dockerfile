# syntax=docker/dockerfile:1

FROM gradle:9.5.1-jdk21-alpine AS build
WORKDIR /app

# Layer 1: build scripts only — keeps dependency resolution cached unless
# one of these files changes.
COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/ gradle/
COPY api/build.gradle.kts api/
COPY client-spec/build.gradle.kts client-spec/
COPY client-spec/openapi/ client-spec/openapi/
COPY clients/java/build.gradle.kts clients/java/
COPY clients/kotlin/build.gradle.kts clients/kotlin/

RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :api:dependencies --no-daemon || true

# Layer 2: source.
COPY api/src/main/ api/src/main/
RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :api:bootJar --no-daemon

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
COPY --from=build /app/api/build/libs/*.jar app.jar
COPY --from=otel /otel-javaagent.jar otel-javaagent.jar
COPY api/entrypoint.sh /app/entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh", "-javaagent:otel-javaagent.jar", "-jar", "app.jar"]
