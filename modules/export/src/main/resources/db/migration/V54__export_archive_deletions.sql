-- Archives owed a deletion (ADR-0059 decision 13, the follow-up to the re-review of #162).
--
-- An archive's object is deleted from the media storage, which is not in the database's
-- transaction. Deleting it before the transaction that forgets its key commits can leave a
-- READY row pointing at nothing, if that transaction then rolls back; deleting it after, with
-- nothing written down, leaves the object stored for ever if the storage cannot be reached
-- then (the `x/` prefix has no lifecycle rule, and a signed URL to it stays good for its 24
-- hours). So the key is written here in the same transaction that forgets it, the object is
-- deleted after the commit, and the row goes once it is; one the storage refused is tried
-- again by the next build run.
--
-- It holds an opaque storage key (`x/<random>.zip`) and an instant: no user id, nothing anyone
-- wrote. A key is here only until its object is gone.
CREATE TABLE export_archive_deletions (
    storage_key text        PRIMARY KEY,
    owed_since  timestamptz NOT NULL
);

-- The sweep's walk, oldest first.
CREATE INDEX export_archive_deletions_owed_idx ON export_archive_deletions (owed_since);
