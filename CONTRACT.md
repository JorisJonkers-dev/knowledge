# knowledge Contract

This repository publishes the knowledge service boundary:

- `api/` runs the Spring Boot `knowledge-api` service.
- `ingest-worker/` runs the Python AMQP worker that writes to `knowledge-vault`.
- `client-spec/openapi/knowledge-api.json` is the committed REST contract.
- `clients/java`, `clients/kotlin`, and `clients/typescript` generate published clients from the committed contract.

The OpenAPI contract is refreshed with `./gradlew :api:exportOpenApiSpec` and validated in CI before client packages or images are published.
