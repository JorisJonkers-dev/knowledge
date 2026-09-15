"""Copies job-store rows into the knowledge-platform-system database (fleet-infra#246 placement)."""

from __future__ import annotations

from dataclasses import dataclass

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


class JobStoreCopyVerificationError(RuntimeError):
    """A table's destination row count didn't match the source after copy."""


@dataclass(frozen=True, slots=True)
class TableCopyResult:
    table: str
    rows_copied: int
    source_count: int
    dest_count: int


# (table, conflict/primary-key column). ingest_outbox's PK is the BIGSERIAL
# `id`, carried over as-is from the source so a resumed job's `checkpoint`
# (an outbox `seq`, not `id`) still lines up; its sequence is resynced
# after copying so the destination's own future inserts don't collide.
_COPY_TABLES: tuple[tuple[str, str], ...] = (
    ("ingest_jobs", "job_id"),
    ("ingest_outbox", "id"),
    ("ingest_source_manifest", "source_id"),
)


def build_conninfo(*, host: str, port: int, dbname: str, user: str, password: str) -> str:
    return f"host={host} port={port} dbname={dbname} user={user} password={password}"


class JobStoreCopier:
    """Copies the three job-store tables from one Postgres database to
    another, verifying row counts as it goes."""

    def __init__(self, source_conninfo: str, dest_conninfo: str) -> None:
        self._source_conninfo = source_conninfo
        self._dest_conninfo = dest_conninfo

    def copy_all(self) -> list[TableCopyResult]:
        results: list[TableCopyResult] = []
        with (
            psycopg.connect(self._source_conninfo) as src,
            psycopg.connect(self._dest_conninfo) as dst,
        ):
            for table, key_column in _COPY_TABLES:
                results.append(self._copy_table(src, dst, table, key_column))
        return results

    def _copy_table(
        self, src: psycopg.Connection, dst: psycopg.Connection, table: str, key_column: str
    ) -> TableCopyResult:
        with src.cursor(row_factory=dict_row) as read_cur:
            read_cur.execute(f"SELECT * FROM {table} ORDER BY {key_column}")
            rows = read_cur.fetchall()

        # Before/after dest counts (not `len(rows)`) give the number of
        # rows this run actually added — 0 on an idempotent re-run, where
        # every row is re-attempted but `ON CONFLICT DO NOTHING` drops it.
        dest_count_before = self._count(dst, table)

        if rows:
            columns = list(rows[0].keys())
            col_list = ", ".join(columns)
            placeholders = ", ".join(f"%({c})s" for c in columns)
            insert_sql = (
                f"INSERT INTO {table} ({col_list}) VALUES ({placeholders}) "
                f"ON CONFLICT ({key_column}) DO NOTHING"
            )
            # `ingest_outbox.payload` is JSONB; psycopg can't adapt a raw
            # dict without an explicit Jsonb() wrapper.
            prepared = [
                {k: (Jsonb(v) if isinstance(v, dict) else v) for k, v in row.items()}
                for row in rows
            ]
            with dst.cursor() as write_cur:
                write_cur.executemany(insert_sql, prepared)

        if table == "ingest_outbox":
            # Explicit ids were just inserted under the BIGSERIAL PK; the
            # destination's own sequence hasn't advanced, so the next
            # worker-side insert would collide with a copied id.
            with dst.cursor() as seq_cur:
                seq_cur.execute(
                    "SELECT setval(pg_get_serial_sequence(%s, %s), "
                    "COALESCE((SELECT MAX(id) FROM ingest_outbox), 0))",
                    (table, "id"),
                )
        dst.commit()

        source_count = self._count(src, table)
        dest_count = self._count(dst, table)
        if dest_count != source_count:
            raise JobStoreCopyVerificationError(
                f"{table}: source has {source_count} rows, dest has {dest_count} after copy"
            )
        return TableCopyResult(
            table=table,
            rows_copied=dest_count - dest_count_before,
            source_count=source_count,
            dest_count=dest_count,
        )

    @staticmethod
    def _count(conn: psycopg.Connection, table: str) -> int:
        with conn.cursor() as cur:
            cur.execute(f"SELECT COUNT(*) FROM {table}")
            row = cur.fetchone()
            assert row is not None
            return int(row[0])


__all__ = [
    "JobStoreCopier",
    "JobStoreCopyVerificationError",
    "TableCopyResult",
    "build_conninfo",
]
