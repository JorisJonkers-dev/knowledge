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
from types import FrameType

import structlog

from knowledge_worker.consumer import Consumer, silence_pika_warning_logs
from knowledge_worker.handlers import LoggingHandler
from knowledge_worker.settings import Settings
from knowledge_worker.telemetry import configure as configure_telemetry


def main() -> int:
    settings = Settings.from_env()
    configure_telemetry(level=settings.log_level, service_version=settings.service_version)
    silence_pika_warning_logs()
    log = structlog.get_logger(__name__)

    consumer = Consumer(settings, LoggingHandler())

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
