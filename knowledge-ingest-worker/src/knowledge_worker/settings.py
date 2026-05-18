"""Settings loaded from environment variables.

Kept boring — no .env files, no Spring-style profile machinery. The
deployment manifest sets the env vars; tests override via monkeypatch
or by constructing `Settings` directly.
"""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Settings:
    rabbitmq_host: str
    rabbitmq_port: int
    rabbitmq_user: str
    rabbitmq_password: str
    rabbitmq_vhost: str
    queue: str
    prefetch_count: int
    log_level: str
    service_version: str

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> Settings:
        e = os.environ if env is None else env
        return cls(
            rabbitmq_host=e.get("RABBITMQ_HOST", "rabbitmq.data-system.svc.cluster.local"),
            rabbitmq_port=int(e.get("RABBITMQ_PORT", "5672")),
            rabbitmq_user=e.get("RABBITMQ_USER", "guest"),
            rabbitmq_password=e.get("RABBITMQ_PASSWORD", "guest"),
            rabbitmq_vhost=e.get("RABBITMQ_VHOST", "/"),
            # Bound to `knowledge.*` by IngestQueueConfig on the
            # knowledge-api side — see Phase 4c-1.
            queue=e.get("INGEST_QUEUE", "knowledge.ingest"),
            # Low prefetch — each message kicks off chunking + embedding +
            # a git commit; concurrency is bounded by Ollama + LightRAG
            # latency, not by AMQP. Starting at 4 gives the worker some
            # backlog tolerance without letting jobs pile up if a single
            # extraction hangs.
            prefetch_count=int(e.get("INGEST_PREFETCH", "4")),
            log_level=e.get("LOG_LEVEL", "INFO"),
            # Stamped onto every log line / span so Loki can group by
            # service.version the same way the JVM services do.
            service_version=e.get("SERVICE_VERSION", "unknown"),
        )
