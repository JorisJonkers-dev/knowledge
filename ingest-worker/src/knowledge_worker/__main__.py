"""Entry point: ``python -m knowledge_worker`` (or via
``opentelemetry-instrument``).

Wires zero or more downstream handlers from settings and, when more than
one is enabled, fans a delivery out to all of them via ``MultiHandler``:

* ``VaultHandler`` (``VAULT_ENABLED=true``) — the legacy write path.
  Clones the knowledge-vault git repo, writes one markdown file per
  delivery, commits + pushes, and calls back to ``kb_notes`` via
  ``PostgresNoteStore`` when ``KB_PERSIST_ENABLED=true``.
* ``HindsightHandler`` (``HINDSIGHT_ENABLED=true``) — retains into
  Hindsight's HTTP API (fleet-infra#246 placement).
* ``BasicMemoryHandler`` (``BASIC_MEMORY_ENABLED=true``) — upserts via
  Basic Memory's MCP tools (fleet-infra#246 placement).

No target enabled falls back to ``LoggingHandler`` for local smoke runs.

Parse failures and handler errors are nacked without requeue so they
route to ``knowledge.ingest.dlq`` via the DLX declared by knowledge-api.
"""

from __future__ import annotations

import signal
import sys
from pathlib import Path
from types import FrameType

import structlog
from git import Actor

from knowledge_worker.basic_memory_client import BasicMemoryClient
from knowledge_worker.consumer import Consumer, silence_pika_warning_logs
from knowledge_worker.handlers import (
    BasicMemoryHandler,
    Handler,
    HindsightHandler,
    LoggingHandler,
    MultiHandler,
    VaultHandler,
)
from knowledge_worker.hindsight_client import HindsightClient
from knowledge_worker.jobs import IngestionJobService, PostgresJobStore
from knowledge_worker.mcp_transport import SseMcpTransport
from knowledge_worker.settings import Settings
from knowledge_worker.store import NoteStore, NullNoteStore, PostgresNoteStore
from knowledge_worker.telemetry import configure as configure_telemetry
from knowledge_worker.vault import VaultGitWriter


def _build_store(settings: Settings, log: structlog.BoundLogger) -> NoteStore:
    if not settings.kb_persist_enabled:
        log.info("handler.store.disabled")
        return NullNoteStore()
    store = PostgresNoteStore(
        host=settings.db_host,
        port=settings.db_port,
        database=settings.db_name,
        user=settings.db_user,
        password=settings.db_password,
    )
    store.open()
    log.info("handler.store.ready", host=settings.db_host, db=settings.db_name)
    return store


def _build_vault_handler(settings: Settings, log: structlog.BoundLogger) -> Handler:
    writer = VaultGitWriter(
        clone_url=settings.vault_clone_url,
        clone_dir=Path(settings.vault_clone_dir),
        branch=settings.vault_branch,
        author=Actor(settings.vault_author_name, settings.vault_author_email),
        ssh_key_path=settings.vault_ssh_key_path,
        push=True,
    )
    writer.open()
    store = _build_store(settings, log)
    log.info("handler.vault.ready", clone=settings.vault_clone_dir)
    return VaultHandler(writer, store)


def _build_hindsight_handler(settings: Settings, log: structlog.BoundLogger) -> Handler:
    client = HindsightClient(
        base_url=settings.hindsight_base_url,
        default_bank=settings.hindsight_default_bank,
        api_key=settings.hindsight_api_key or None,
    )
    log.info("handler.hindsight.ready", base_url=settings.hindsight_base_url)
    return HindsightHandler(client)


def _build_basic_memory_handler(settings: Settings, log: structlog.BoundLogger) -> Handler:
    transport = SseMcpTransport(base_url=settings.basic_memory_base_url)
    client = BasicMemoryClient(
        transport, project=settings.basic_memory_project, folder=settings.basic_memory_folder
    )
    log.info("handler.basic_memory.ready", base_url=settings.basic_memory_base_url)
    return BasicMemoryHandler(client)


def _build_handler(settings: Settings, log: structlog.BoundLogger) -> Handler:
    """Build every enabled downstream handler and fan out across them.

    Independent per-target toggles (fleet-infra#246 placement) let the
    knowledge-platform-system Deployment run Hindsight + Basic Memory
    together while knowledge-system keeps running vault-only — no target
    enabled is the local/smoke-test default (`LoggingHandler`).
    """
    handlers: list[Handler] = []
    if settings.vault_enabled:
        handlers.append(_build_vault_handler(settings, log))
    else:
        log.info("handler.vault.disabled")
    if settings.hindsight_enabled:
        handlers.append(_build_hindsight_handler(settings, log))
    else:
        log.info("handler.hindsight.disabled")
    if settings.basic_memory_enabled:
        handlers.append(_build_basic_memory_handler(settings, log))
    else:
        log.info("handler.basic_memory.disabled")

    if not handlers:
        return LoggingHandler()
    if len(handlers) == 1:
        return handlers[0]
    return MultiHandler(handlers)


def _build_job_service(
    settings: Settings, log: structlog.BoundLogger
) -> IngestionJobService | None:
    """Wire #246 job store into the consumer when the DB is reachable.

    The job/outbox/source-manifest tables carry dedup, resume + deadletter
    state. They share the same Postgres as ``kb_notes``; when ``KB_PERSIST``
    is off there is no DB to back them, so the consumer falls back to the
    legacy write-through path (no dedup).
    """
    if not settings.kb_persist_enabled:
        log.info("handler.job_store.disabled")
        return None
    store = PostgresJobStore(
        host=settings.db_host,
        port=settings.db_port,
        database=settings.db_name,
        user=settings.db_user,
        password=settings.db_password,
    )
    store.open()
    log.info("handler.job_store.ready", db=settings.db_name)
    return IngestionJobService(store)


def main() -> int:
    settings = Settings.from_env()
    configure_telemetry(level=settings.log_level, service_version=settings.service_version)
    silence_pika_warning_logs()
    log = structlog.get_logger(__name__)

    consumer = Consumer(
        settings, _build_handler(settings, log), job_service=_build_job_service(settings, log)
    )

    def shutdown(signum: int, _frame: FrameType | None) -> None:
        log.info("consumer.shutdown.signal", signal=signum)
        consumer.stop()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    log.info("consumer.boot", version=settings.service_version, queue=settings.queue)
    consumer.start()
    try:
        consumer.run_forever()
    except KeyboardInterrupt:  # pragma: no cover — covered by `shutdown` above
        consumer.stop()
    return 0


if __name__ == "__main__":  # pragma: no cover — module entry point
    sys.exit(main())
