-- Reclassification watermarks for the curator's retroactive
-- topic-reassignment pass.
--
-- The topic vocabulary moves: operators add new topics to
-- `kb_topics`, merge old ones, or refine aliases. Existing
-- `kb_notes` rows are silently stale until something pokes
-- them. Two timestamps on `kb_notes` give a cheap watermark
-- per axis:
--
--   topic_classified_at  — when this row's `scope` (the
--                          `topic:<slug>` value) was last
--                          settled against the live vocab.
--   tag_classified_at    — when this row's `kb_note_tags`
--                          set was last settled.
--
-- The reclassifier loop on the curator side reads
-- `MAX(created_at) FROM kb_topics` and re-considers rows whose
-- `topic_classified_at` is older. That's an O(N) read but the
-- index makes it fast. Updates push the watermark forward.
--
-- Backfill both columns to `captured_at` so the very first
-- reclassifier pass treats every existing row as fair game —
-- exactly what we want for the one-shot historical cleanup.
--
-- DDL is split into single-action ALTER TABLE statements so the
-- jOOQ codegen's H2-flavoured DDL simulator can parse it. Postgres
-- merges them at apply time either way.
ALTER TABLE kb_notes ADD COLUMN topic_classified_at TIMESTAMPTZ;
ALTER TABLE kb_notes ADD COLUMN tag_classified_at TIMESTAMPTZ;

UPDATE kb_notes SET topic_classified_at = captured_at;
UPDATE kb_notes SET tag_classified_at = captured_at;

ALTER TABLE kb_notes ALTER COLUMN topic_classified_at SET NOT NULL;
ALTER TABLE kb_notes ALTER COLUMN tag_classified_at SET NOT NULL;

ALTER TABLE kb_notes ALTER COLUMN topic_classified_at SET DEFAULT NOW();
ALTER TABLE kb_notes ALTER COLUMN tag_classified_at SET DEFAULT NOW();

CREATE INDEX idx_kb_notes_topic_classified_at
    ON kb_notes (topic_classified_at);
CREATE INDEX idx_kb_notes_tag_classified_at
    ON kb_notes (tag_classified_at);
