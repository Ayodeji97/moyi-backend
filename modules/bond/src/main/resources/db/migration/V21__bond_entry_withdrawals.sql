-- A member took their own entries back when the bond ended (FR-029a, BR-10a).
-- Written in the transaction that ends the bond, so every read can honour it
-- from that commit on, before anything has been erased. Insert-only.
--
-- member_id is a bond_members id and carries no foreign key, on purpose.
-- Its siblings in this module do carry one (bond_invites.created_by_member_id,
-- bond_proposals.proposed_by_member_id: REFERENCES bond_members (id) ON DELETE
-- CASCADE), because an invite or a proposal means nothing once its member row
-- is gone. This row is the opposite: it is the standing order to hide and then
-- erase that member's entries, and it must outlive the member row if the
-- member row ever goes first. A CASCADE would delete the marker with the
-- member, and entries not yet erased would become readable again: the
-- withdrawal undone by an unrelated delete. A plain (restricting) reference
-- would instead make this table a reason a member row cannot be removed.
-- (Nothing removes a member row today: a member who leaves keeps theirs.)
-- The row goes when the bond does, by the reference on bond_id.
CREATE TABLE bond_entry_withdrawals (
    bond_id      uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    member_id    uuid        NOT NULL,
    withdrawn_at timestamptz NOT NULL,
    PRIMARY KEY (bond_id, member_id)
);
