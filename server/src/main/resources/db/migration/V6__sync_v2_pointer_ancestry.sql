ALTER TABLE someday_sync_v2_epochs
    ADD COLUMN previous_epoch_pointer_digest TEXT;

UPDATE someday_sync_v2_epochs AS successor
SET previous_epoch_pointer_digest = predecessor.pointer_digest
FROM someday_sync_v2_epochs AS predecessor
WHERE successor.user_id = predecessor.user_id
  AND successor.previous_epoch_id = predecessor.epoch_id;

ALTER TABLE someday_sync_v2_epochs
    ADD CONSTRAINT someday_sync_v2_previous_epoch_identity_pair
        CHECK ((previous_epoch_id IS NULL) = (previous_epoch_pointer_digest IS NULL));
