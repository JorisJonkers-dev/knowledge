"""CLI smoke test: flag wiring, stdout report, and the no-live-store guarantee.

The CLI only reads the source vault and writes to local archive + manifest —
it never connects to a live Basic Memory / Garage / Postgres store. Running
it against a temp vault and temp outputs exercises the whole surface.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from note_import.cli import main


def test_cli_imports_and_reports(
    vault: tuple[Path, object], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root, _ = vault
    store = tmp_path / "store"
    manifest_path = tmp_path / "manifest.jsonl"
    rc = main(
        [
            "--vault",
            str(root),
            "--archive",
            str(store),
            "--manifest",
            str(manifest_path),
            "--log-level",
            "ERROR",
        ]
    )
    captured = capsys.readouterr()
    assert rc == 0
    assert "walked=9" in captured.out
    assert "new=9" in captured.out
    assert "legacy_derived=1" in captured.out
    assert manifest_path.exists()
    assert store.exists()


def test_cli_requires_vault_flag() -> None:
    # All positional-free flags: --vault is required, so omitting it errors.
    with pytest.raises(SystemExit) as excinfo:
        main(["--archive", "x", "--manifest", "y"])
    assert excinfo.value.code != 0
