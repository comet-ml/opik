--liquibase formatted sql
--changeset idoberko2:000042_add_version_sequence_to_dataset_versions
--comment: Add version_sequence column to dataset_versions for sequential version numbering (v1, v2, v3, etc.)

ALTER TABLE dataset_versions ADD COLUMN version_sequence INT NOT NULL DEFAULT 0;

-- Create index for efficient querying by dataset_id and version_sequence
CREATE INDEX idx_dataset_versions_dataset_id_version_sequence
    ON dataset_versions (dataset_id, version_sequence DESC);

--rollback DROP INDEX idx_dataset_versions_dataset_id_version_sequence ON dataset_versions;
--rollback ALTER TABLE dataset_versions DROP COLUMN version_sequence;

