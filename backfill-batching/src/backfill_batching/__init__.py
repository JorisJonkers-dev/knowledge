"""Resumable, deduplicating batch backfill driver (fleet-infra#249).

Pure standard library. Given a tree of ``.jsonl`` transcript files, it
walks and stable-sorts the corpus, groups records into capped batches, and
drives each batch through a selection rule into a sink (Hindsight) with a
durable checkpoint so an interrupt+restart resumes without multiplying
knowledge.

This package is the *protocol proof*, not the enablement hook: it ships no
cluster client and depends on nothing from the #261-diverged PVC.
"""

from backfill_batching.checkpoint import (
    BatchOutcome,
    Checkpoint,
    ReconciliationError,
)
from backfill_batching.cost import CostEstimate, CostPolicyError, estimate_cost
from backfill_batching.manifest import Batch, CorpusPlan, plan_corpus
from backfill_batching.runner import Runner, RunReport, VerboseSink

__all__ = [
    "Batch",
    "BatchOutcome",
    "Checkpoint",
    "CorpusPlan",
    "CostEstimate",
    "CostPolicyError",
    "ReconciliationError",
    "RunReport",
    "Runner",
    "VerboseSink",
    "estimate_cost",
    "plan_corpus",
]
