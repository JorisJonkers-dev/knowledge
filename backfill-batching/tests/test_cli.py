"""CLI smoke tests: the documented flags exist and behave (plan/dry-run/refuse).

Coverage note: ``__main__.py`` is the thin argparse shell; these tests drive it
so the cost-refusal path is exercised through the real interface.
"""

from __future__ import annotations

from pathlib import Path

from conftest import TOTAL_RECORDS

from backfill_batching.__main__ import main
from backfill_batching.manifest import plan_corpus


def _argv(corpus: Path, checkpoint: Path, *extra: str) -> list[str]:
    return [
        "backfill_batching",
        "run",
        "--corpus",
        str(corpus),
        "--checkpoint",
        str(checkpoint),
        *extra,
    ]


def test_plan_command(monkeypatch, corpus: Path, checkpoint_path: Path) -> None:
    args = [
        "backfill_batching",
        "plan",
        "--corpus",
        str(corpus),
        "--checkpoint",
        str(checkpoint_path),
        "--batch-records",
        "4",
    ]
    monkeypatch.setattr("sys.argv", args)
    assert main() == 0


def test_run_command_smoke(monkeypatch, corpus: Path, checkpoint_path: Path) -> None:
    monkeypatch.setattr("sys.argv", _argv(corpus, checkpoint_path, "--batch-records", "4"))
    assert main() == 0
    plan = plan_corpus(corpus, batch_records=4)
    assert plan.total_records == TOTAL_RECORDS
    assert checkpoint_path.exists()


def test_dry_run_flag(monkeypatch, corpus: Path, checkpoint_path: Path) -> None:
    monkeypatch.setattr(
        "sys.argv", _argv(corpus, checkpoint_path, "--batch-records", "4", "--dry-run")
    )
    assert main() == 0
    assert not checkpoint_path.exists()


def test_llm_refused_via_cli(monkeypatch, corpus: Path, checkpoint_path: Path) -> None:
    monkeypatch.setattr(
        "sys.argv", _argv(corpus, checkpoint_path, "--batch-records", "4", "--enable-llm")
    )
    assert main() == 2  # refused: no allowance


def test_reset_checkpoint(monkeypatch, corpus: Path, checkpoint_path: Path) -> None:
    monkeypatch.setattr("sys.argv", _argv(corpus, checkpoint_path, "--batch-records", "4"))
    assert main() == 0
    assert checkpoint_path.exists()

    # Reset clears prior progress, then a fresh run re-creates the checkpoint
    # and completes cleanly.
    monkeypatch.setattr(
        "sys.argv",
        _argv(corpus, checkpoint_path, "--batch-records", "4", "--reset-checkpoint"),
    )
    assert main() == 0
    assert checkpoint_path.exists()
