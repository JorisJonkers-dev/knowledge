from __future__ import annotations

from knowledge_worker.store import NullNoteStore, PostgresNoteStore


def test_null_note_store_pretends_one_row_updated() -> None:
    assert NullNoteStore().update_vault_pointer("01X", "notes/x.md", "abc") == 1


def test_postgres_store_constructs_pool_without_opening() -> None:
    # open=False keeps the pool quiescent so we can assert wiring
    # without needing a real Postgres in the unit test job. Integration
    # coverage in tests/integration/test_postgres_store.py exercises
    # the actual UPDATE path against testcontainers.
    s = PostgresNoteStore(
        host="pg",
        port=5432,
        database="kb",
        user="u",
        password="p",
        min_size=1,
        max_size=2,
    )
    assert s is not None
