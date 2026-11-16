-- Issue #205 (ADR-0069).
ALTER TABLE notifications DROP CONSTRAINT notifications_category_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_category_check CHECK (
    category IN ('REMINDER', 'PARTNER_JOINED', 'REVEAL', 'SOLO_UNLOCK', 'MILESTONE', 'ON_THIS_DAY',
                 'PROPOSAL_OPENED', 'SHARED_SETTING_CHANGED')
);

-- Which change a SHARED_SETTING_CHANGED push is about, and nothing for any other category. Kept
-- on the row because a held push is sent again from its row alone (V58's reason), and the words
-- the phone shows depend on it. SEVERAL is written only by coalescing (ADR-0069 decision 7).
ALTER TABLE notifications ADD COLUMN shared_change text;
ALTER TABLE notifications ADD CONSTRAINT notifications_shared_change_check CHECK (
    (shared_change IS NULL) = (category <> 'SHARED_SETTING_CHANGED')
    AND (shared_change IS NULL OR shared_change IN
         ('SETTING', 'PROPOSAL_ACCEPTED', 'PROPOSAL_DECLINED', 'PROPOSAL_WITHDRAWN', 'SEVERAL'))
);

-- Ending a Bond calls off every push of it still owed, inside the leave's own transaction
-- (ADR-0069 decision 5): this reads the Bond's owed rows, not the whole ledger. Partial, so it
-- stays as small as what is owed.
CREATE INDEX notifications_bond_owed_idx ON notifications (bond_id) WHERE status IN ('REQUESTED', 'HELD');
