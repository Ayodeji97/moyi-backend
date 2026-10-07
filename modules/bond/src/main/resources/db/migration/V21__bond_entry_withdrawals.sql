-- A member took their own entries back when the bond ended (FR-029a, BR-10a).
-- Written in the transaction that ends the bond, so every read can honour it
-- from that commit on, before anything has been erased. Insert-only.
CREATE TABLE bond_entry_withdrawals (
    bond_id      uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    member_id    uuid        NOT NULL,
    withdrawn_at timestamptz NOT NULL,
    PRIMARY KEY (bond_id, member_id)
);
