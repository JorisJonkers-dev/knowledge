# knowledge-ingest-worker

Python consumer that reads `knowledge.ingest`-bound messages from RabbitMQ and (in stacked follow-ups) commits canonical notes to the `knowledge-vault` git repo + populates LightRAG chunks/entities/relations in `knowledge_db`.

Today the worker ships the skeleton: connect, consume, log each delivery as a structured JSON line, ACK. The git-vault writer lands next; the LightRAG ingest pipeline (Ollama embeddings + pgvector + entity/relation extraction) layers on top of that.

## Local development

```bash
cd ingest-worker
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
