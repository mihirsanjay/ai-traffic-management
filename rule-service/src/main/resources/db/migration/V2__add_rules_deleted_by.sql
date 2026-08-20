-- Record who deleted a rule, not just when.
--
-- The rules table has recorded created_by since V1, but deletion captured only
-- deleted_at. That gap becomes visible in Phase 2: a RULE_DELETED event has a
-- changedBy field, and without this column there is nothing truthful to put in
-- it. An event that omits its actor is a lie by omission, and "who changed what"
-- is precisely what the event stream exists to carry.
--
-- Nullable, necessarily and permanently: a live rule has no deleter, and the
-- rows that already exist have no value to backfill. Anything else would require
-- inventing an actor for history that did not record one.

ALTER TABLE rules
    ADD COLUMN deleted_by VARCHAR(100);

COMMENT ON COLUMN rules.deleted_by IS
  'Who soft-deleted this rule. NULL while the rule is live.';
