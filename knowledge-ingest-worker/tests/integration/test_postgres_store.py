"""Integration test for `PostgresNoteStore` against a real Postgres.

Mirrors the shape the knowledge-api side produces: the `kb_notes`
schema is replicated locally (knowledge-api owns the canonical
migration; the worker doesn't run Flyway). The test seeds one row
with `vault_commit = NULL`, then verifies `update_vault_pointer`
flips the path + sha in place.
"""

from __future__ import annotations

from collections.abc import Iterator

import psycopg
import pytest

try:  # pragma: no cover — Docker is required for integration runs
    from testcontainers.postgres import PostgresContainer
except ImportError:  # pragma: no cover
    PostgresContainer = None  # type: ignore[assignment]

from knowledge_worker.store import PostgresNoteStore

pytestmark = pytest.mark.integration


_CREATE_KB_NOTES = """
CREATE TABLE kb_notes (
    id              VARCHAR(64) PRIMARY KEY,
    type            VARCHAR(32) NOT NULL,
    scope           VARCHAR(128) NOT NULL,
    source          VARCHAR(256) NOT NULL,
    captured_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    session_id      VARCHAR(128),
    confidence      REAL NOT NULL DEFAULT 0.4,
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    vault_path      TEXT NOT NULL,
    vault_commit    VARCHAR(64),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
"""


@pytest.fixture(scope="module")
def postgres() -> Iterator[dict[str, object]]:
    if PostgresContainer is None:  # pragma: no cover
        pytest.skip("testcontainers.postgres not installed")
    with PostgresContainer("postgres:17-alpine") as container:
        host = container.get_container_host_ip()
        port = int(container.get_exposed_port(5432))
        conninfo = (
            f"host={host} port={port} dbname={container.dbname} "
            f"user={container.username} password={container.password}"
        )
        with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
            cur.execute(_CREATE_KB_NOTES)
            conn.commit()
        yield {
            "host": host,
            "port": port,
            "database": container.dbname,
            "user": container.username,
            "password": container.password,
            "conninfo": conninfo,
        }


@pytest.fixture()
def store(postgres: dict[str, object]) -> Iterator[PostgresNoteStore]:
    s = PostgresNoteStore(
        host=str(postgres["host"]),
        port=int(postgres["port"]),  # type: ignore[arg-type]
        database=str(postgres["database"]),
        user=str(postgres["user"]),
        password=str(postgres["password"]),
    )
    s.open()
    try:
        yield s
    finally:
        s.close()


def _seed_note(conninfo: str, note_id: str) -> None:
    with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO kb_notes "
            "(id, type, scope, source, title, body, vault_path) "
            "VALUES (%s, 'lesson', 'personal', 'claude-code', "
            "        't', 'b', 'personal/lesson/draft.md')",
            (note_id,),
        )
        conn.commit()


def _read(conninfo: str, note_id: str) -> tuple[str, str | None]:
    with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT vault_path, vault_commit FROM kb_notes WHERE id = %s",
            (note_id,),
        )
        row = cur.fetchone()
        assert row is not None
        return row[0], row[1]


def test_update_replaces_draft_path_and_sets_commit(
    store: PostgresNoteStore,
    postgres: dict[str, object],
) -> None:
    conninfo = str(postgres["conninfo"])
    _seed_note(conninfo, "01PG00000000000000000000")

    affected = store.update_vault_pointer(
        "01PG00000000000000000000",
        "notes/personal/lesson/01PG00000000000000000000.md",
        "deadbeef" * 5,
    )
    assert affected == 1

    path, commit = _read(conninfo, "01PG00000000000000000000")
    assert path == "notes/personal/lesson/01PG00000000000000000000.md"
    assert commit == "deadbeef" * 5


def test_update_returns_zero_when_row_missing(store: PostgresNoteStore) -> None:
    affected = store.update_vault_pointer(
        "01MISSING0000000000000000",
        "notes/x.md",
        "abc",
    )
    assert affected == 0
