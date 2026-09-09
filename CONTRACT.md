# knowledge Contract

This repository publishes the knowledge service boundary:

- `api/` runs the Spring Boot `knowledge-api` service.
- `ingest-worker/` runs the Python AMQP worker that writes to `knowledge-vault`.
- `basic-memory-git-sync/` runs the git commit backstop for the Basic Memory
  vault, committing + pushing the notes Basic Memory writes into the shared
  working tree.
- `client-spec/openapi/knowledge-api.json` is the committed REST contract.
- Java, Kotlin, and TypeScript clients are generated and published from the committed contract by the shared `publish-api-clients` workflow.

The OpenAPI contract is refreshed with `./gradlew :api:exportOpenApiSpec` and validated in CI before client packages or images are published.
