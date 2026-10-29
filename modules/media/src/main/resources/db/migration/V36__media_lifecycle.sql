-- The media lifecycle (Phase 4 slice D5, ADR-0050): the reaper, the demotion,
-- and the purge of an erased asset's objects.
--
-- Numbered V36: V34 and V35 are held by D3 (voice), which is built beside this
-- slice; Flyway runs without outOfOrder, so whichever merges second must not
-- carry a number below the first's.

-- When an entry last let this asset go: a PATCH that put another photo in its
-- place (ADR-0048). The reaper counts an unattached asset's 24 hours (doc
-- 06:186) from the latest of its creation, its readiness and this, so a photo
-- taken off an entry is its owner's for a day, as one never attached is.
ALTER TABLE media_assets ADD COLUMN detached_at timestamptz;

-- When `media.purge` deleted the asset's objects from the storage: set once, in
-- the delivery that did it, and only ever on a deleted asset. A deleted asset
-- without it is one whose objects may still be in the bucket, which is what
-- the runbook (ops/monitoring/media-pipeline-stuck.md) asks after.
ALTER TABLE media_assets ADD COLUMN purged_at timestamptz;
ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_purged_check CHECK (purged_at IS NULL OR status = 'DELETED');

-- The nightly demotion's candidates (D-16): attached photos still holding their
-- full-resolution variant. Partial, so the index holds about ninety days of
-- photos rather than every photo ever kept.
CREATE INDEX media_assets_tier_one_idx ON media_assets (ready_at)
    WHERE tier = 1 AND status = 'READY' AND attached_entry_id IS NOT NULL;

-- The hourly reaper's candidates: whatever is not deleted and not attached.
-- Small by construction (an upload is attached or reaped within a day), where
-- `(status, created_at)` would also walk every READY photo ever attached.
CREATE INDEX media_assets_unattached_idx ON media_assets (status)
    WHERE attached_entry_id IS NULL AND status <> 'DELETED';
