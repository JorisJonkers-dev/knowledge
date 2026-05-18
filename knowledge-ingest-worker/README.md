# knowledge-ingest-worker

Python consumer that reads `knowledge.ingest`-bound messages from RabbitMQ and (in later phases) commits canonical notes to the `knowledge-vault` git repo + populates LightRAG chunks/entities/relations in `knowledge_db`.

Phase 5-1 ships the worker skeleton: it connects, consumes, logs each delivery as a structured JSON line, and ACKs. Phase 5-2 layers the git-vault writer on top, Phase 5-3 the LightRAG ingest pipeline.

## Local development

```bash
cd services/knowledge-ingest-worker
uv sync                          # creates .venv, installs runtime + dev deps
uv run pytest                    # unit tests only (default)
uv run pytest -m integration     # Testcontainers RabbitMQ smoke tests
uv run ruff check .              # lint
uv run mypy                      # type check
```

## Configuration

| Env var                               | Default                                  | Notes                                                      |
| ------------------------------------- | ---------------------------------------- | ---------------------------------------------------------- |
| `RABBITMQ_HOST`                       | `rabbitmq.data-system.svc.cluster.local` | k8s service DNS                                            |
| `RABBITMQ_PORT`                       | `5672`                                   |                                                            |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest`                        | Vault-projected in production                              |
| `INGEST_QUEUE`                        | `knowledge.ingest`                       | matches `IngestQueueConfig` on the knowledge-api side      |
| `INGEST_PREFETCH`                     | `4`                                      | Bounded by Ollama / LightRAG latency, not AMQP             |
| `LOG_LEVEL`                           | `INFO`                                   |                                                            |
| `SERVICE_VERSION`                     | `unknown`                                | Stamped onto each log line (baked into the image at build) |
