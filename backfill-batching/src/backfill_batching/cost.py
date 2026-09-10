"""Cost-known-before-run protocol.

#240 sampled the backfill and found it is *embed-only and self-hosted* — so
the embedding pass is ~$0 monetary regardless of corpus size. A *separate
LLM extraction pass* (off by default) is priced at ~$14-20 for the sample
if enabled. This module turns those givens into an enforceable
projection-and-gate: a run refuses to start with the LLM pass on if the
projection is not covered by an explicit allowance.
"""

from __future__ import annotations

from dataclasses import dataclass

# [GIVEN-FROM-TICKET] #240 findings.
EMBED_COST_USD_PER_RECORD = 0.0  # self-hosted TEI (bge-m3) -> ~$0 monetary
LLM_EXTRACT_USD_LOW = 14.0  # per-sample envelope, low end
LLM_EXTRACT_USD_HIGH = 20.0  # per-sample envelope, high end
LLM_EXTRACT_SAMPLE_RECORDS = 250  # [DESIGN] sampled corpus size for the estimate

DEFAULT_MODEL_ID = "openrouter/pareto-code"  # [GIVEN-FROM-TICKET] #240 finding


class CostPolicyError(RuntimeError):
    """Raised when a run would start without a sufficient cost allowance."""


@dataclass
class CostEstimate:
    """Cost projection computed before any embedding/sink write happens."""

    total_records: int
    projected_selected: int
    llm_pass_enabled: bool
    embed_cost_usd: float
    extract_cost_usd: float
    model_id: str

    @property
    def total_usd(self) -> float:
        return self.embed_cost_usd + self.extract_cost_usd


def _project_selected(records: int) -> int:
    """[DESIGN] selection projection for planning.

    Not a mere fraction of the corpus: this is a placeholder cost model the
    caller may replace with a real sample-based estimator. The protocol's
    contract is that *some* deterministic projection exists before the run,
    not this particular constant.
    """
    return max(0, records - (records // 3))


def estimate_cost(
    total_records: int,
    *,
    llm_pass_enabled: bool = False,
    model_id: str = DEFAULT_MODEL_ID,
) -> CostEstimate:
    """Project cost for a corpus run before any writes.

    Embedding is self-hosted (=>~$0). If the LLM extraction pass is enabled,
    scale the #240 envelope by the projected selectable record count relative
    to the sample the estimate was taken over.
    """
    projected_selected = _project_selected(total_records)
    embed = EMBED_COST_USD_PER_RECORD * projected_selected
    if not llm_pass_enabled:
        extract = 0.0
        model = "none (embed-only self-hosted)"
    else:
        scale = projected_selected / LLM_EXTRACT_SAMPLE_RECORDS
        extract = ((LLM_EXTRACT_USD_LOW + LLM_EXTRACT_USD_HIGH) / 2.0) * scale
        model = model_id
    return CostEstimate(
        total_records=total_records,
        projected_selected=projected_selected,
        llm_pass_enabled=llm_pass_enabled,
        embed_cost_usd=embed,
        extract_cost_usd=extract,
        model_id=model,
    )


def enforce_allowance(estimate: CostEstimate, allowance_usd: float | None) -> None:
    """Refuse to start an LLM-pass run whose projection exceeds the allowance.

    Embed-only runs cost ~$0 and always pass. The LLM pass (off by default)
    must be both explicitly enabled and covered by an ``--llm-allowance-usd``
    allowance, else the run refuses rather than spending money blind.
    """
    if not estimate.llm_pass_enabled:
        return
    if estimate.extract_cost_usd == 0.0:
        return
    if allowance_usd is None:
        raise CostPolicyError(
            "LLM extraction pass enabled but no --llm-allowance-usd given; "
            f"projected extract cost ~${estimate.extract_cost_usd:.2f}"
        )
    if estimate.extract_cost_usd > allowance_usd:
        raise CostPolicyError(
            f"projected extract cost ${estimate.extract_cost_usd:.2f} exceeds "
            f"allowance ${allowance_usd:.2f}"
        )
