"""Postgres write-back for the canonical `kb_notes` row.

The knowledge-api inserts each captured note synchronously when the
MCP `capture_*` tool runs — at that moment `vault_path` is a
placeholder (``<scope>/<type>/draft.md``) and `vault_commit` is
``NULL`` because the worker hasn't committed yet. After
``VaultGitWriter.write`` succeeds the worker UPDATEs the row with
the real path + sha so `recall` can return a usable pointer instead
of a draft placeholder.

A single shared `ConnectionPool` keeps the worker from re-handshaking
TLS + auth on every delivery; pool size is intentionally small (2)
because the worker itself is single-replica and only issues one
UPDATE per message.
"""

from __future__ import annotations

from typing import Protocol

import structlog
from psycopg import sql
from psycopg_pool import ConnectionPool


class NoteStore(Protocol):
    def update_vault_pointer(
        self,
        note_id: str,
        vault_path: str,
        vault_commit: str,
    ) -> int: ...


class PostgresNoteStore:
    """`NoteStore` backed by Postgres via a `psycopg_pool` connection pool."""

    def __init__(
        self,
        *,
        host: str,
        port: int,
        database: str,
        user: str,
        password: str,
        min_size: int = 1,
        max_size: int = 2,
    ) -> None:
        conninfo = (
            f"host={host} port={port} dbname={database} "
            f"user={user} password={password} application_name=knowledge-ingest-worker"
        )
        self._pool = ConnectionPool(
            conninfo=conninfo,
            min_size=min_size,
            max_size=max_size,
            open=False,
        )
        self._log = structlog.get_logger(__name__)

    def open(self) -> None:
        self._pool.open(wait=True, timeout=10.0)
        self._log.info("store.opened")

    def close(self) -> None:
        self._pool.close()

    def update_vault_pointer(
        self,
        note_id: str,
        vault_path: str,
        vault_commit: str,
    ) -> int:
        """UPDATE the canonical row. Returns the number of rows affected.

        Zero rows usually means the api row was never inserted (the
        capture-side MCP tool didn't run) or a malformed `id` slipped
        through; either way callers log + continue rather than nack.
        """

        query = sql.SQL(
            "UPDATE kb_notes "
            "SET vault_path = %s, vault_commit = %s, updated_at = NOW() "
            "WHERE id = %s"
        )
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(query, (vault_path, vault_commit, note_id))
            return cur.rowcount


class NullNoteStore:
    """Dev fallback. Pretends every UPDATE found exactly one row."""

    def update_vault_pointer(
        self,
        _note_id: str,
        _vault_path: str,
        _vault_commit: str,
    ) -> int:
        return 1
