# knowledge-ingest-worker

Python consumer that reads `knowledge.ingest`-bound messages from RabbitMQ, commits canonical notes to the `knowledge-vault` git repo, and writes vault pointers back to `kb_notes` in Postgres. The LightRAG chunking + embedding pipeline (Ollama embeddings, pgvector, entity/relation extraction) layers on top of that in stacked follow-ups.

## Error handling

| Failure mode | Behaviour |
|---|---|
| Malformed JSON / schema violation / bad encoding | `basic_nack(requeue=False)` → routed to `knowledge.ingest.dlq` via DLX |
| Handler error (vault write, DB error) | `basic_nack(requeue=False)` → same DLQ |
| Clean dispatch | `basic_ack` |

The DLX topology (`knowledge.dlx` exchange + `knowledge.ingest.dlq` queue) is declared by the knowledge-api service on startup. The worker only consumes — it never needs to declare queues.

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
| `VAULT_ENABLED`                       | `false`                                  | Set `true` in production; falls back to `LoggingHandler`   |
| `VAULT_CLONE_URL`                     | `git@github.com:…/knowledge-vault.git`   | SSH URL for the vault repo                                 |
| `VAULT_CLONE_DIR`                     | `/var/lib/knowledge-vault`               | Persistent volume mount point                              |
| `VAULT_BRANCH`                        | `main`                                   |                                                            |
| `VAULT_SSH_KEY_PATH`                  | `/etc/git-secrets/id_ed25519`            | Vault Agent injects deploy key in production               |
| `VAULT_AUTHOR_NAME`                   | `knowledge-ingest-worker`                |                                                            |
| `VAULT_AUTHOR_EMAIL`                  | `worker@knowledge.local`                 |                                                            |
| `KB_PERSIST_ENABLED`                  | `false`                                  | Set `true` to write vault pointers back to `kb_notes`      |
| `DB_HOST`                             | `postgres.data-system.svc.cluster.local` | k8s service DNS                                            |
| `DB_PORT`                             | `5432`                                   |                                                            |
| `DB_NAME`                             | `knowledge_db`                           |                                                            |
| `DB_USER` / `DB_PASSWORD`             | `kb_user` / `kb_password`                | Vault-projected in production                              |
