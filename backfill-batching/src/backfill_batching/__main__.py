"""CLI for the resumable backfill driver.

Usage::

    python -m backfill_batching plan --corpus <dir> --checkpoint <path>
    python -m backfill_batching run --corpus <dir> --checkpoint <path> [--dry-run]
                                    [--enable-llm --llm-allowance-usd 20]
                                    [--reset-checkpoint]
                                    [--batch-records 10]
"""

from __future__ import annotations

import argparse
from pathlib import Path

from backfill_batching.cost import CostPolicyError
from backfill_batching.manifest import plan_corpus
from backfill_batching.runner import BackfillInterrupted, Runner, VerboseSink


def _parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(prog="backfill_batching")
    sub = ap.add_subparsers(dest="command", required=True)

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--corpus", required=True, type=Path)
    common.add_argument("--checkpoint", required=True, type=Path)
    common.add_argument("--batch-records", type=int, default=10)

    plan_p = sub.add_parser("plan", parents=[common])
    plan_p.set_defaults(handler="plan")

    run_p = sub.add_parser("run", parents=[common])
    run_p.add_argument("--dry-run", action="store_true")
    run_p.add_argument("--enable-llm", action="store_true")
    run_p.add_argument("--llm-allowance-usd", type=float)
    run_p.add_argument("--reset-checkpoint", action="store_true")
    run_p.set_defaults(handler="run")
    return ap.parse_args()


def main() -> int:
    args = _parse_args()
    plan = plan_corpus(args.corpus, batch_records=args.batch_records)
    checkpoint = args.checkpoint
    if getattr(args, "reset_checkpoint", False):
        checkpoint.unlink(missing_ok=True)

    if args.handler == "plan":
        print(f"batches={plan.total_batches} records={plan.total_records}")
        print(f"fingerprint={plan.fingerprint}")
        return 0

    runner = Runner(
        plan=plan,
        checkpoint_path=checkpoint,
        sink=VerboseSink(),
        llm_pass_enabled=args.enable_llm,
        llm_allowance_usd=args.llm_allowance_usd,
        dry_run=args.dry_run,
    )
    try:
        report = runner.run()
    except CostPolicyError as exc:
        print(f"REFUSED: {exc}")
        return 2
    except BackfillInterrupted:
        print("INTERRUPTED: checkpoint advanced to safe batch boundary")
        return 1

    print(
        f"cost_usd={report.cost.total_usd:.2f} "
        f"selected={report.selected_total} "
        f"retained={report.retained_total} "
        f"duplicates={report.duplicates_total} "
        f"failed={report.failed_total}"
    )
    if not report.dry_run:
        ok = report.reconcile() == report.plan.total_records
        print(f"reconcile_ok={ok}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
