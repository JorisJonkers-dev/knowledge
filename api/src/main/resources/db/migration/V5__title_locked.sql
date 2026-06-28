-- Plan A of the curator quality plan: a retroactive title
-- renormaliser passes over kb_notes whose `title` exceeds the
-- 80-char contract and asks the classifier for a tighter rewrite.
-- `title_locked` is the off-switch operators can flip after a
-- hand-edit so the next renormaliser pass leaves their work alone.
--
-- Default FALSE: every existing row participates in the first
-- pass. New rows ingested via the inbox pipeline also default to
-- unlocked — the inbox pass already enforces the 80-char contract
-- via the classifier schema, so the renormaliser is a no-op on
-- them anyway.
ALTER TABLE kb_notes
    ADD COLUMN title_locked BOOLEAN NOT NULL DEFAULT FALSE;
