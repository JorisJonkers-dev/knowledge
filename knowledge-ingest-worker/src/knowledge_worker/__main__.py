"""Entry point: ``python -m knowledge_worker`` (or via
``opentelemetry-instrument``).

Today the worker only ships the consumer skeleton + the
`LoggingHandler` — each delivery emits one structured log and is
ACKed. The real ingest flow (LightRAG, Ollama embeddings,
knowledge-vault git commits) lands in stacked follow-ups.
"""

from __future__ import annotations

import signal
import sys
from pathlib import Path
from types import FrameType

import structlog
from git import Actor

from knowledge_worker.consumer import Consumer, silence_pika_warning_logs
from knowledge_worker.handlers import Handler, LoggingHandler, VaultHandler
from knowledge_worker.settings import Settings
from knowledge_worker.telemetry import configure as configure_telemetry
from knowledge_worker.vault import VaultGitWriter


def _build_handler(settings: Settings, log: structlog.BoundLogger) -> Handler:
    if not settings.vault_enabled:
        log.info("handler.vault.disabled")
        return LoggingHandler()
    writer = VaultGitWriter(
        clone_url=settings.vault_clone_url,
        clone_dir=Path(settings.vault_clone_dir),
        branch=settings.vault_branch,
        author=Actor(settings.vault_author_name, settings.vault_author_email),
        ssh_key_path=settings.vault_ssh_key_path,
        push=True,
    )
    writer.open()
    log.info("handler.vault.ready", clone=settings.vault_clone_dir)
    return VaultHandler(writer)


def main() -> int:
    settings = Settings.from_env()
    configure_telemetry(level=settings.log_level, service_version=settings.service_version)
    silence_pika_warning_logs()
    log = structlog.get_logger(__name__)

    consumer = Consumer(settings, _build_handler(settings, log))

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
