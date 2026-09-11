--liquibase formatted sql

-- REFERENCE MIGRATION for the topology-aware traces DDL pattern (OPIK-7772). TEST FIXTURE, NOT A SHIPPED MIGRATION.
--
-- ISOLATION. This file is under src/test/resources, so the shipped changelog's includeAll over
-- liquibase/db-app-analytics/migrations/ cannot reach it and no deployment can apply it. It has no migration number and
-- its changeset author is `opik-7772-test-fixture`, so the ledger rows it writes in a test container are unmistakable
-- and cannot collide with a shipped changeset id. A real traces migration copies this SHAPE into the shipped
-- migrations directory under the usual NNNNNN_ name and the `opik` author -- it does not include this file.
--
-- WHY THIS SHAPE. The cutover to the partitioned, sharding-ready trace table is produced by the operator runbook
-- (data-migrations/traces-local-v2-cutover), not by Liquibase, so from the moment an install cuts over the changelog and
-- the runtime topology disagree — and the fleet stays mixed for months (SaaS cut over; self-hosted on their own cadence;
-- fresh installs still pre-cutover). One migration file therefore has to be correct against BOTH layouts:
--
--   pre-cutover:   traces        = the live MergeTree           traces_local_v2 = the empty successor (shadow)
--   post-cutover:  traces        = a Distributed wrapper        traces_local    = the MergeTree shard beneath it
--
-- THE PATTERN. Ship the change as TWO complementary changesets guarded on the same fact — whether traces_local exists —
-- so exactly one branch executes and the other is recorded MARK_RAN. The guard is a sqlCheck against system.tables
-- rather than a tableExists precondition because it must read the *runtime* topology, which no changelog records.
-- Both branches are written with IF [NOT] EXISTS so a re-run, a partially-applied branch, or an install that reaches
-- this migration from either side is idempotent.
--
-- ON CLUSTER. Every statement carries ON CLUSTER '{cluster}', as every shipped traces/spans ALTER does and as
-- migrations.md requires: without it the DDL reaches only the node the migration connected to, leaving the other
-- replicas short of the column while the changeset is recorded as applied. That matters twice over here — the guard
-- branch is chosen from a LOCAL system.tables read, so on a divergent cluster one node can record MARK_RAN for a
-- topology the others are not in. The macro is resolved server-side, so this stays portable across installs.
--
-- WHERE A CHANGE LANDS (the general rule):
--   * Anything that changes the READ-FACING COLUMN LIST (a column, including MATERIALIZED / ALIAS) must be applied to
--     the shard AND the Distributed wrapper. The wrapper stores nothing, but it resolves column names: a shard-only
--     ADD COLUMN succeeds silently and is then unreadable through the wrapper (code 47).
--   * Anything STORAGE-ONLY (skip index, codec, TTL, projection) goes to the SHARD ONLY. The Distributed wrapper holds
--     no data, so it has nowhere to put an index and rejects the statement.
--   * Pre-cutover the same change applies to BOTH traces and the traces_local_v2 shadow, or the cutover promotes a
--     table that no longer matches the live one.
--   * A PRESERVED (non-derived) column must ALSO be added to the cutover backfill's explicit column list
--     (data-migrations/traces-local-v2-cutover/scripts/db-app-analytics/000001_backfill_traces_local_v2.sql), or the
--     cutover copies it as its default and the data is lost. TracesSchemaParityPreCutoverTest enforces this.
--
-- This reference demonstrates the two cases we have historically shipped, one of each kind:
--   * a FIELD  — reference_derived, a MATERIALIZED column, so read-facing: shard + wrapper.
--   * an INDEX — idx_reference_storage, a data-skipping index, so storage-only: shard alone.
-- A derived (MATERIALIZED) column is used deliberately: it is read-facing, so it exercises the wrapper branch, while
-- needing no backfill-list entry (the destination recomputes it), which keeps this fixture from having to edit shipped
-- cutover SQL. A preserved column would additionally need the backfill-list edit described above.
--
-- Structural changes (ORDER BY / PRIMARY KEY / PARTITION BY) are immutable on MergeTree and cannot be ALTERed at all;
-- they require a table recreate, ride the successor's table definition rather than an in-window ALTER, and are to be
-- avoided entirely during the mixed-fleet window. They are out of scope for this pattern.

--changeset opik-7772-test-fixture:reference_topology_aware_change_pre_cutover
--comment: Pre-cutover branch — traces is the live MergeTree and traces_local_v2 is the shadow; apply to both
--preconditions onFail:MARK_RAN onError:HALT onFailMessage:traces_local exists, so this install is post-cutover; the pre-cutover branch is skipped
--precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS reference_derived UInt64 MATERIALIZED length(name);
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD INDEX IF NOT EXISTS idx_reference_storage name TYPE set(0) GRANULARITY 1;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS reference_derived UInt64 MATERIALIZED length(name);
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' ADD INDEX IF NOT EXISTS idx_reference_storage name TYPE set(0) GRANULARITY 1;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_reference_storage;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS reference_derived;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_reference_storage;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS reference_derived;

--changeset opik-7772-test-fixture:reference_topology_aware_change_post_cutover
--comment: Post-cutover branch — traces is the Distributed wrapper over traces_local; the column goes to both, the index to the shard only
--preconditions onFail:MARK_RAN onError:HALT onFailMessage:traces_local does not exist, so this install is pre-cutover; the post-cutover branch is skipped
--precondition-sql-check expectedResult:1 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS reference_derived UInt64 MATERIALIZED length(name);
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}' ADD INDEX IF NOT EXISTS idx_reference_storage name TYPE set(0) GRANULARITY 1;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS reference_derived UInt64 MATERIALIZED length(name);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS reference_derived;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_reference_storage;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS reference_derived;
