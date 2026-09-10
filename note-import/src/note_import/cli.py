"""Command-line interface for ``note-import``.

Pure preparation tool: walks a source vault, maps/classifies/archives and
writes the manifest. It never talks to a live Basic Memory server, Garage or
Postgres store.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import structlog

from note_import.archive import ContentAddressedArchive
from note_import.importer import ImportRunner
from note_import.lineage import LineageResolver, NotAGitRepo
from note_import.manifest import Manifest

_LOG = structlog.get_logger(__name__)


def _configure(level: str) -> None:
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.stdlib.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.dev.ConsoleRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(level.upper()),
    )


def _parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="note-import",
        description=(
            "Import curated notes from a source git vault into the Basic "
            "Memory note format. Preparation only: walks, maps, classifies, "
            "archives to a content-addressed store, and writes an idempotent "
            "manifest. Never touches a live Basic Memory / Garage / Postgres "
            "store."
        ),
    )
    p.add_argument(
        "--vault",
        required=True,
        type=Path,
        help="Source vault root directory (the knowledge-vault checkout).",
    )
    p.add_argument(
        "--archive",
        required=True,
        type=Path,
        help=(
            "Archive root for original bytes (content-addressed layout). A "
            "local directory; a Garage object store can be swapped in later."
        ),
    )
    p.add_argument(
        "--manifest",
        required=True,
        type=Path,
        help="JSONL manifest path (the idempotency ledger).",
    )
    p.add_argument(
        "--log-level",
        default="INFO",
        help="Structlog level (DEBUG/INFO/WARNING/ERROR).",
    )
    return p


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    _configure(args.log_level)

    archive = ContentAddressedArchive(args.archive)
    archive.open()
    manifest = Manifest(args.manifest)

    lineage: LineageResolver | None = None
    try:
        lineage = LineageResolver.open(args.vault)
    except NotAGitRepo:
        _LOG.warning("vault.not_a_git_repo", vault=str(args.vault))

    runner = ImportRunner(
        vault_root=args.vault,
        archive=archive,
        manifest=manifest,
        lineage=lineage,
    )
    summary = runner.run()
    archive.close()

    _LOG.info(
        "note.import.complete",
        vault=str(args.vault),
        archive=str(args.archive),
        manifest=str(args.manifest),
        **summary.as_dict(),
    )
    print(f"walked={summary.walked} new={summary.new} skipped={summary.skipped}")
    print(
        "evidence_backed="
        f"{summary.evidence_backed} legacy_derived={summary.legacy_derived} "
        f"archived={summary.archived}"
    )
    return 0


if __name__ == "__main__":  # pragma: no cover - module entry point
    sys.exit(main())
