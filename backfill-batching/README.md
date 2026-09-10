# knowledge-backfill-batching

Resumable, deduplicating **batch backfill driver** for the transcript corpus
into Hindsight (fleet-infra#249). Pure standard library, no cluster, no live
corpus — proof of the protocol described in
`../docs/backfill-batching-249.md`, not the enablement hook.

## What it proves

Against a temp-dir fake `.jsonl` corpus (project dirs → files → records):

1. **Interrupt + restart produces no duplicate records** — the durable
   checkpoint records per-record creates mid-write; a restart re-processes
   only from the batch boundary and the watermark turns already-written
   records into duplicates (never re-written).
2. **Per-batch success/failure reconciles to the source count** —
   `source_lines == selected + retained + duplicates + failed` for every
   batch, and the sum across batches equals the corpus total.
3. **Selection rule is recorded** — each batch records which rule ran and
   which ids went to Hindsight vs stayed, for audit.
4. **Cost known before run** — a `--dry-run` computes and prints the cost
   projection with no writes; the LLM extraction pass (off by default) is
   refused unless an allowance covers its projection.

## Run

```bash
uv sync --dev
uv run pytest --cov
uv run ruff check .
uv run mypy
```

## CLI

```bash
python -m backfill_batching plan  --corpus <dir> --checkpoint <path> [--batch-records 10]
python -m backfill_batching run   --corpus <dir> --checkpoint <path> [--batch-records 10]
                                  [--dry-run] [--enable-llm --llm-allowance-usd 20]
                                  [--reset-checkpoint]
```

The corpus lives **outside** these checkouts (in the #261-diverged PVC /
archive) and is out of scope here by ticket; point `--corpus` at whatever tree
you are authorised to backfill.