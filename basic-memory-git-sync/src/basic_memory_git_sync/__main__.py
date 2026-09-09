"""Entry point: ``python -m basic_memory_git_sync``.

Wires Settings from env, configures structlog, and runs the pull→commit→push
loop against the shared vault working tree.
"""

from __future__ import annotations

import sys

import structlog

from basic_memory_git_sync.settings import Settings
from basic_memory_git_sync.sync import run_forever


def _configure(level: str, version: str) -> None:
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.stdlib.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.dev.ConsoleRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(level.upper()),
    )
    structlog.get_logger("basic_memory_git_sync").info("service.boot", version=version)


def main() -> int:
    settings = Settings.from_env()
    _configure(settings.log_level, settings.service_version)
    # run_forever loops until a pull conflict / refused push halts it; a
    # conflict is never auto-resolved, so the loop exits only on a stop signal
    # or an unreconcilable state (both logged loudly).
    run_forever(settings)
    return 0


if __name__ == "__main__":  # pragma: no cover - module entry point
    sys.exit(main())
