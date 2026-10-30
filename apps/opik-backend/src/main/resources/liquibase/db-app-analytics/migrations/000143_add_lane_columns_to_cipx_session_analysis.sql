--liquibase formatted sql
--changeset petrot:000143_add_lane_columns_to_cipx_session_analysis
--comment: Per-lane input fingerprints and summary-pass version for the two-lane session analysis

-- Lane 1 (labels and bounds) writes `fingerprint`; lane 2 (text and links) writes `summary_version` and `summary_fingerprint`.
-- Old rows read 0 = 0, so lane 2 is not due until lane 1 runs again.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS fingerprint         UInt64 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS summary_version     UInt16 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS summary_fingerprint UInt64 DEFAULT 0;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS fingerprint, DROP COLUMN IF EXISTS summary_version, DROP COLUMN IF EXISTS summary_fingerprint;
