"""Shared fixtures: build a temp-dir fake .jsonl corpus shaped like the
ticket's description (project dirs of transcript files) for the batch
runner proof.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from backfill_batching.manifest import CLAIM_TYPE_FIELD, CONTENT_FIELD, SOURCE_ID_FIELD


def _line(source_id: str, claim_type: str, text: str) -> str:
    return json.dumps(
        {
            SOURCE_ID_FIELD: source_id,
            CLAIM_TYPE_FIELD: claim_type,
            CONTENT_FIELD: text,
        },
        sort_keys=True,
    )


@pytest.fixture()
def corpus(tmp_path: Path) -> Path:
    """A deterministic fake corpus: 3 project dirs, 4 files, 16 records.

    Claim types are mixed so the selection rule sends *some* records to
    Hindsight and retains the rest, exercising both sides of the rule.
    Selecting claim types: explicit_user_decision, explicit_user_preference,
    observed_tool_outcome, synthesized_summary. Non-selected: agent_suggestion.
    """
    # (source_id, claim_type, text) -- 16 records across 4 files
    rows = [
        ("p1/a-1", "explicit_user_decision", "adopt postgres 17"),
        ("p1/a-2", "agent_suggestion", "maybe try redis"),
        ("p1/a-3", "observed_tool_outcome", "watch cache desynced"),
        ("p2/b-1", "explicit_user_preference", "keep deploys under 5m"),
        ("p2/b-2", "agent_suggestion", "could add a retry"),
        ("p2/b-3", "synthesized_summary", "consensus across dashboards"),
        ("p3/c-1", "explicit_user_decision", "use pnpm"),
        ("p3/c-2", "agent_suggestion", "hypothesise slow path"),
        ("p3/c-3", "observed_tool_outcome", "kubelet froze"),
        ("p1/a-4", "agent_suggestion", "maybe drop the shim"),
        ("p1/a-5", "explicit_user_preference", "fail-closed by default"),
        ("p2/b-4", "synthesized_summary", "two dashboards agree"),
        ("p3/c-4", "agent_suggestion", "could add a flag"),
        ("p3/c-5", "explicit_user_decision", "pin image digests"),
        ("p1/a-6", "agent_suggestion", "maybe vendor scripts"),
        ("p2/b-5", "observed_tool_outcome", "restore compared OK"),
    ]
    # Distribute 16 rows across 4 files (4 each) in 3 project dirs.
    files = [
        (fdir, fname)
        for (fdir, fname) in [
            ("proj-one", "t1.jsonl"),
            ("proj-one", "t2.jsonl"),
            ("proj-two", "t3.jsonl"),
            ("proj-three", "t4.jsonl"),
        ]
        for _ in range(4)
    ]
    for (fdir, fname), (sid, ctype, text) in zip(files, rows, strict=True):
        path = tmp_path / fdir / fname
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as fh:
            fh.write(_line(sid, ctype, text) + "\n")
    return tmp_path


EXPECTED_SELECTED = {
    "p1/a-1",
    "p1/a-3",
    "p2/b-1",
    "p2/b-3",
    "p3/c-1",
    "p3/c-3",
    "p1/a-5",
    "p2/b-4",
    "p3/c-5",
    "p2/b-5",
}
EXPECTED_RETAINED = {
    "p1/a-2",
    "p2/b-2",
    "p3/c-2",
    "p1/a-4",
    "p3/c-4",
    "p1/a-6",
}
TOTAL_RECORDS = 16


def iter_sink_ids(written: list[object]) -> list[str]:
    """Extract source_ids from a written-record list in order."""
    return [r.source_id for r in written]


@pytest.fixture()
def checkpoint_path(tmp_path: Path) -> Path:
    return tmp_path / "checkpoint.json"
