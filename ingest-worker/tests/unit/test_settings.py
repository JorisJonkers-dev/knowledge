from __future__ import annotations

import pytest

from knowledge_worker.settings import Settings


def test_from_env_uses_defaults_when_no_overrides() -> None:
    s = Settings.from_env(env={})
    assert s.rabbitmq_host == "rabbitmq.data-system.svc.cluster.local"
    assert s.rabbitmq_port == 5672
    assert s.queue == "knowledge.ingest"
    assert s.prefetch_count == 4
    assert s.log_level == "INFO"
    assert s.service_version == "unknown"


def test_from_env_honours_overrides() -> None:
    s = Settings.from_env(
        env={
            "RABBITMQ_HOST": "rabbit",
            "RABBITMQ_PORT": "5673",
            "RABBITMQ_USER": "kb",
            "RABBITMQ_PASSWORD": "secret",
            "INGEST_QUEUE": "knowledge.ingest.test",
            "INGEST_PREFETCH": "16",
            "LOG_LEVEL": "DEBUG",
            "SERVICE_VERSION": "deadbeef",
        }
    )
    assert s.rabbitmq_host == "rabbit"
    assert s.rabbitmq_port == 5673
    assert s.rabbitmq_user == "kb"
    assert s.rabbitmq_password == "secret"
    assert s.queue == "knowledge.ingest.test"
    assert s.prefetch_count == 16
    assert s.log_level == "DEBUG"
    assert s.service_version == "deadbeef"


def test_from_env_rejects_non_numeric_port() -> None:
    with pytest.raises(ValueError):
        Settings.from_env(env={"RABBITMQ_PORT": "not-a-number"})


def test_kb_persist_defaults_off_and_carries_db_defaults() -> None:
    s = Settings.from_env(env={})
    assert s.kb_persist_enabled is False
    assert s.db_host == "postgres.data-system.svc.cluster.local"
    assert s.db_port == 5432
    assert s.db_name == "knowledge_db"


def test_kb_persist_honours_overrides() -> None:
    s = Settings.from_env(
        env={
            "KB_PERSIST_ENABLED": "true",
            "DB_HOST": "pg",
            "DB_PORT": "5433",
            "DB_NAME": "kb",
            "DB_USER": "kb_app",
            "DB_PASSWORD": "hunter2",
        }
    )
    assert s.kb_persist_enabled is True
    assert s.db_host == "pg"
    assert s.db_port == 5433
    assert s.db_name == "kb"
    assert s.db_user == "kb_app"
    assert s.db_password == "hunter2"
