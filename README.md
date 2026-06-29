# knowledge

Knowledge API, MCP endpoint, and ingest worker for the JorisJonkers-dev agent
knowledge base.

## What It Is

`knowledge` is a Kotlin/Spring API service plus a Python ingest worker. The API
serves REST and MCP read/write tools backed by Postgres, RabbitMQ, and the
knowledge-vault Git repository. The worker consumes ingest messages, writes vault
notes, and updates persistence metadata.

## Local Use

```bash
./gradlew :api:test :api:integrationTest
cd ingest-worker && uv sync --frozen && uv run pytest
```

API consumers should use the published OpenAPI contract or generated client
packages rather than copying internal service code.

## Related

- API contract: [CONTRACT.md](./CONTRACT.md)
- OpenAPI spec: [client-spec/openapi/knowledge-api.json](./client-spec/openapi/knowledge-api.json)
- Deployment source: [deploy/deployment.yml](./deploy/deployment.yml)

## Links

- [Organization profile](https://github.com/JorisJonkers-dev)
- [Security policy](https://github.com/JorisJonkers-dev/.github/security/policy)
- [License](./LICENSE)

Copyright (c) Joris Jonkers. Source available for viewing only; use, copying,
modification, redistribution, deployment, or reuse is not licensed. See
[LICENSE](./LICENSE).
