-- The AAC variant of a voice note (issue #152, ADR-0060): AAC-LC in MP4, mono, about 32 kbit/s,
-- its index first, so that iOS's AVPlayer can stream it, which it cannot do with the Ogg Opus that
-- stays the note's primary copy (ADR-0049).
--
-- Where it is kept, recorded when the note becomes READY with one: `v/{uuid}/voice.m4a`, derived
-- from the upload's key as the Opus's is (`StorageKey.forVoiceAac`). Recorded rather than only
-- derived because a note may have none: one made before this migration (not backfilled: ADR-0060
-- decision 6), or one whose variant could not be made (best effort, decision 3). Null then, and
-- the API's `urlAac` is null.
--
-- What is *deleted* does not read this column: every deletion (the owner's, the reaper's, an
-- erasure's, account deletion's, the operator's) ends in `media.purge`, which deletes every key
-- derived from the upload's, recorded or not (`MediaAsset.objectKeys`).
ALTER TABLE media_assets ADD COLUMN aac_key text;
ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_aac_key_check CHECK (aac_key IS NULL OR kind = 'VOICE');
