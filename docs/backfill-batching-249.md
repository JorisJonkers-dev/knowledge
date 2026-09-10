# Backfill batching for the 1037-file transcript corpus — analysis note

**Ticket:** fleet-infra#249 (estimate/spec for the transcript backfill into Hindsight)
**Status:** analysis + local proof only — no cluster access, no dependence on the #261 PVC
**Tags in this doc:** `[GIVEN-FROM-TICKET]` = asserted by the ticket / prior verified findings;
`[DESIGN]` = this note's proposal, to be confirmed by reviewers.

---

## 1. Scope and goal (the "NOT everything" rule)

`[GIVEN-FROM-TICKET]` The corpus for this backfill is **~1037 `.jsonl`
transcript files / ~1.1 GB / 55 project dirs**. `[GIVEN-FROM-TICKET]` The
transcripts live **outside** these checkouts — in the `#261`-diverged
`knowledge-vault-clone` PVC and/or the archive. `[GIVEN-FROM-TICKET]`
**Loading everything into Hindsight is explicitly NOT the goal.**

`[GIVEN-FROM-TICKET]` Dedup rule: *dedup must be proven before both a native
Hindsight hook and the backfill are enabled.* That is a hard gate, not a
softness.

`[GIVEN-FROM-TICKET]` #240 sample estimate: the backfill is **embed-only and
self-hosted ⇒ ~$0 monetary**; a separate **LLM extraction pass would cost
~$14–20** for the sample if enabled. `[GIVEN-FROM-TICKET]` The estate default
model is currently **`openrouter/pareto-code`** (a router, per the #240
finding), not `deepseek-v4-flash`.

This note rules out the "embed everything" reading of a backfill and defines:
what is selected (goes in), what is durable, how batches are reconciled, how
interrupt+restart cannot multiply knowledge, and how cost is known *before* a
run starts. The proof in part B implements the protocol against a fake corpus
and proves the three invariants.

---

## 2. Corpus discovery — what is *reachable* here

`[GIVEN-FROM-TICKET]` Do **not** chase the live / PVC copies — not reachable,
forbidden. `[GIVEN-FROM-TICKET]` Do **not** build anything that depends on the
`#261`-diverged PVC (its state is contested, HEAD hundreds of commits from the
canonical repo — see handoff §4).

`[DESIGN]` Therefore this ticket ships **tooling + protocol** only, verified
locally against a synthetic corpus. The discovery walker (enumerate
`*.jsonl`, stable-sort by path, read `n` lines per file) is tested against
fixtures shaped like the described corpus (55 project dirs, ~1037 files) but
the real bytes are a deferred precondition. Opening the backfill on a
contested PVC is an explicit **don't** and is deferred to #261's resolution.

---

## 3. Resumable batch protocol `[DESIGN]`

A backfill is a sequence of **batches**. A batch is "up to `batch_records`
consecutive `.jsonl` records from up to `batch_files` consecutive files" —
the unit of both **work and reconciliation**. Nothing is "everything": a run
progresses batch-by-batch, and a batch is atomic with respect to the
checkpoint (see §4).

1. **Plan** — walk the corpus, stable-sort by `project/<relative-path>`, and
   produce an ordered list of batch descriptors (file + line range +
   stable id set). The plan is pure (`manifest.json`), never mutated.
2. **Resume** — read the durable checkpoint (§4). If absent, start from batch
   0; if present, start from `checkpoint.next_batch`.
3. **Process** — for each batch, apply the selection rule (§6) → produce the
   Hindsight-write set and the corpus-retained set → record the outcome
   (§4.4) → advance `next_batch` and fsync the checkpoint *before*
   considering the batch durable.
4. **Finish** — when `next_batch == total_batches`, mark the run `complete`;
   emit a final reconciliation table.

### Batch size

`[GIVEN-FROM-TICKET]` The in-cluster Hindsight worker caps concurrency at
**`batch 10, poll 500ms, 3 retries`** (#243) so a backfill cannot starve live
recall. `[DESIGN]` The backfill driver uses the same ceiling (default
`batch_records=10`, `max_retries=3`) and **never** exceeds it; a larger batch
is a configuration change, not a constant. The synthetic proof uses tiny
batches so the interrupt/restart and failure paths are exercised cheaply.

---

## 4. Durable checkpoint — resume without multiplying knowledge `[DESIGN]`

The checkpoint is the **single source of truth** that makes
interrupt+restart idempotent. It is a JSON document, replaced atomically
(write temp + `os.replace`) so a crash mid-write cannot yield a half-written
checkpoint.

```jsonc
{
  "schema_version": 1,
  "corpus_fingerprint": "sha256:…",        // of the manifest; a changed corpus
                                            // aborts the run instead of drifting
  "next_batch": 12,                          // first unprocessed batch index
  "completed_batches": [0,1,…,11],           // strictly monotone, contiguous
  "outcomes": {                              // per-batch reconciliation (per §4.4)
    "0": {"selected": 7, "retained": 3, "duplicates": 0, "failed": 0, "source_lines": 10},
    "11": {"selected": 4, "retained": 6, "duplicates": 2, "failed": 0, "source_lines": 10}
  },
  "run_id": "uuid",                          // one run id for the whole resume chain
  "started_at": "…", "updated_at": "…"
}
```

### 4.1 Idempotency rule

`[DESIGN]` A batch is only marked completed **after** its side effects (the
Hindsight writes) are durable **and** the checkpoint fsyncs. On restart the
runner reads `next_batch` and **re-processes only batches ≥ next_batch**;
batches below it are never re-run. Because the watermark (§5) is a junction
of (stable id, content hash) — both deterministic for a given source — a
source emitted and then re-encountered dedups to one record. The proof's
interrupt test kills the run mid-batch, restarts, and asserts the union
record count equals the source count.

### 4.2 Multi-file page-crash safety

`[DESIGN]` If the process dies *between* writing a Hindsight record and
writing the checkpoint, the record sits in Hindsight but the batch is not
completed. On restart the batch is reprocessed; §5 dedup makes the
re-processing converge (no second copy). This is the **multiply-knowledge**
hazard the checkpoint + dedup jointly close: checkpoint alone makes progress
atomic; dedup makes re-processing safe.

### 4.3 Corrupt / changed corpus

`[DESIGN]` If `corpus_fingerprint` no longer matches the manifest hash, the
runner **refuses to continue** (does not silently reprocess on a different
corpus). Operator must either resolve #261 first or explicitly reset the
checkpoint (`--reset-checkpoint`), which is a deliberate, audited action.

### 4.4 Per-batch reconciliation

`[DESIGN]` Each batch records, and is only accepted when it satisfies:

```
source_lines == selected + retained + duplicates + failed
```

where:
- `source_lines` = records read from the source in that batch (the ground truth),
- `selected` = wrote new records into Hindsight,
- `retained` = stayed in the corpus (not selected; §6),
- `duplicates` = matched an already-seen stable id / content hash (skipped),
- `failed` = raised past the retry ceiling.

A batch whose tally does not equal `source_lines` is **not** completed and is
not advanced past. The union across all batches must equal the total source
count — proved by the reconcile test.

---

## 5. Watermark / stable-id dedup `[DESIGN]`

`[GIVEN-FROM-TICKET]` Gate: *dedup proven before both a native hook and
backfill are enabled.* That gate is **not yet met** — enabling either is
deferred until the dedup is demonstrated against real data.

`[DESIGN]` Dedup key = the pair **(stable_id, content_hash)** where:

- **stable_id** — the knowledge-contract `source_id` (constant across
  revisions of the same logical source), so re-ingesting a changed revision
  of the same transcript does **not** create a second memory.
- **content_hash** — SHA-256 of the canonical record bytes (the contract's
  `content_hash`), so the *same* logical source arriving via two URIs (a
  documented contract trap) is recognized as one source, not two.

Record `(stable_id, content_hash)` as a **watermark** — the highest stable id
seen, plus the set of seen hashes — so that on replay a record already in the
watermark is `duplicates += 1` and never re-embedded. For 1.1 GB of
transcripts the seen-hash set is small (order of 10³–10⁴ entries, KBs–MBs)
and lives in the checkpoint, not in Hindsight.

Rationale for pair-vs-hash-only: hash-only would treat an edited transcript
as "new" (multiply knowledge across revisions); stable-id-only would collapse
two genuinely distinct docs that reused an id. The pair is the conservative
junction. This mirrors the existing contract fixtures
(`duplicate-source-uri-1/2.json`, same `content_hash`, different `source_id`
→ one logical source).

---

## 6. Selection rule — what goes into Hindsight vs stays `[DESIGN]`

`[GIVEN-FROM-TICKET]` Everything is NOT loaded. `[DESIGN]` Hindsight (a
vector memory over `bge-m3`, 1024-dim, self-hosted TEI per #240/#243)
receives only:

1. **Relevant experiences** — records whose content matches the recall
   surface: decisions, outcomes, preferences (contract `claim_type` in
   `explicit_user_decision`, `explicit_user_preference`,
   `observed_tool_outcome`, `synthesized_summary`), i.e. material a future
   agent should be able to recall.
2. **Durable extracted knowledge** — the LLM-extraction pass product (the
   ~$14–20 line in the #240 estimate) *if* that pass is enabled; it is
   **off by default** because `[GIVEN-FROM-TICKET]` the backfill is
   embed-only self-hosted (~$0) unless extracted knowledge is requested.

**Stays in the corpus (not loaded):**
- Duplicates (already-seen `(stable_id, content_hash)`).
- Raw/full transcripts that add no recall surface — the corpus remains the
  immutable source of record (`content_hash`, `original_bytes`), per the
  archive contract. Not loading them is the "NOT everything" rule made
  concrete.
- Records that fail selection (e.g. pure agent chatter / `agent_suggestion`
  with `agent_hypothesis` claim types that are unverified — the contract
  warns they are provisional, not corroboration).

`[DESIGN]` Formally: `selected = pass(content, claim_type) ∧ record ∉ watermark`.
A record that fails `pass(...)` is `retained` (stays in corpus), not dropped —
nothing is destroyed. The selection predicate is a `[DESIGN]` surface to be
reviewed against the actual transcript schema, and the proof records the rule
as chosen per batch for auditability.

---

## 7. Cost-known-before-run protocol `[DESIGN]`

`[GIVEN-FROM-TICKET]` #240 estimate: embed-only self-hosted ⇒ **~$0
monetary**; LLM pass **~$14–20** if enabled.

`[DESIGN]` These are *estimates from a sample*, not a guarantee at full scale.
The protocol therefore makes cost a **precondition of starting a run**:

1. **Plan phase emits a cost projection** before any embedding:
   - `projected_selected` from the selection rule over the sample,
   - `embed_tokens ≈ Σ selected bytes` (self-hosted embed ⇒ compute, ~$0),
   - `extract_tokens` only if the LLM pass is `--enable-llm` (default off),
     priced at the #240 envelope; a run refuses to start without
     `--llm-allowance-usd` if the LLM pass is on and the projection exceeds it.
2. **Dry-run first** — `--dry-run` performs plan + selection + tally with
   **no writes** and prints the full reconciliation + cost table; this is the
   "known-before-run" artifact.
3. **Monitor during run** — the driver prints per-batch `selected/retained/
   dup/failed` and a running cost accumulator so an overrun is visible
   before the allowance is spent.
4. **Recorded after run** — the final reconciliation, projected-vs-actual
   cost, and model id (`openrouter/pareto-code` if the extraction pass runs)
   are stored next to the checkpoint.

`[DESIGN]` Because the default is embed-only self-hosted, the honest headline
is **≈ $0 monetary regardless of the 1.1 GB size**; the ~$14–20 only
materializes if the extraction pass is switched on, and it stays switched off
until a reviewer approves. The proof asserts that `--dry-run` returns the
cost projection and that `--enable-llm` without `--llm-allowance-usd` on an
over-limit projection is refused.

---

## 8. What this note explicitly does NOT authorize

- `[GIVEN-FROM-TICKET]` No access to the live / PVC transcripts (unreachable, forbidden).
- `[GIVEN-FROM-TICKET]` No dependence on the `#261`-diverged `knowledge-vault-clone`.
- `[DESIGN]` **No enabling of the dedup native hook or the backfill** until the
  dedup gate is proven (per §5). This ticket ships the protocol + local proof,
  not the enablement.
- `[DESIGN]` No "embed everything" interpretation. The selection rule (§6) is
  the product.

## 9. Proof (Part B) — invariants demonstrated locally

`[RUN-VERIFIED]` (see the sibling `backfill-batching/` Python package +
`pytest`/`ruff`/`mypy` run in the commit) against a temp-dir fake corpus:

1. **Interrupt + restart ⇒ no duplicate records** — kill mid-batch, restart,
   union record count == source count (count + boundary spot-check).
2. **Per-batch reconcile to source count** — `source_lines ==
   selected + retained + duplicates + failed` per batch, and summed across
   batches == corpus total.
3. **Selection rule recorded** — each batch emits its chosen predicate and a
   tally; a `--dry-run` prints the cost projection before any write.