-- Issue #205 (ADR-0069). Who called a proposal off, and why it was called off. Declined and
-- withdrawn are not stored: a MEMBER cancel by the proposer is withdrawn, by anyone else declined
-- (Proposal.outcome, the one place that reads it).
ALTER TABLE bond_proposals
    ADD COLUMN cancelled_by_member_id uuid REFERENCES bond_members (id) ON DELETE CASCADE;
ALTER TABLE bond_proposals ADD COLUMN cancel_cause text;

ALTER TABLE bond_proposals ADD CONSTRAINT bond_proposals_cancel_cause_check
    CHECK (cancel_cause IS NULL OR cancel_cause IN ('MEMBER', 'LAPSED', 'BOND_ENDED'));
-- A cause only on a cancelled row. A cancelled row may have none: one cancelled before V70
-- whose cause could not be known (read as UNRECORDED).
ALTER TABLE bond_proposals ADD CONSTRAINT bond_proposals_cancel_cause_cancelled_check
    CHECK (cancel_cause IS NULL OR cancelled_at IS NOT NULL);
-- A member is named exactly when a member called it off. Never for BOND_ENDED: a leave or a
-- block must not read as anybody declining (doc 26 §2.1).
ALTER TABLE bond_proposals ADD CONSTRAINT bond_proposals_cancelled_by_check
    CHECK ((cancelled_by_member_id IS NOT NULL) = (cancel_cause IS NOT DISTINCT FROM 'MEMBER'));

-- What can be known of the rows already cancelled. A lapsed row is closed (closeLapsed) only once
-- expires_at has passed, while a member can cancel only a live row (findLive), so
-- cancelled_at >= expires_at is exactly a lapse (or a bond ended after the lapse: lapsed either way).
UPDATE bond_proposals SET cancel_cause = 'LAPSED'
 WHERE cancelled_at IS NOT NULL AND cancel_cause IS NULL AND cancelled_at >= expires_at;
-- EndBond cancels at the instant it archives (cancelLiveOf(ended.id, ended.archivedAt)).
UPDATE bond_proposals p SET cancel_cause = 'BOND_ENDED'
  FROM bonds b
 WHERE b.id = p.bond_id AND p.cancelled_at IS NOT NULL AND p.cancel_cause IS NULL
   AND b.archived_at IS NOT NULL AND p.cancelled_at = b.archived_at;
-- The rest were called off by a member before anybody recorded which: UNRECORDED.
