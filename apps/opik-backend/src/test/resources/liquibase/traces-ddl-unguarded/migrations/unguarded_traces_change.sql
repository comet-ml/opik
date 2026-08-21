--liquibase formatted sql

-- NEGATIVE CONTROL for OPIK-7772. TEST FIXTURE, NEVER SHIPPED.
--
-- ISOLATION: under src/test/resources, so the shipped changelog cannot reach it; no migration number, and the
-- `opik-7772-test-fixture` changeset author keeps its ledger rows out of the shipped id namespace.
--
-- This is what a traces migration looks like when it is written without the topology-aware pattern: a single
-- unconditional ALTER against `traces`, which is how every pre-cutover migration in db-app-analytics/migrations was
-- legitimately written before the cutover existed. It applies cleanly on BOTH topologies and is WRONG on both:
--
--   * pre-cutover  — it reaches the live `traces` but not the `traces_local_v2` shadow, so the next cutover promotes a
--                    table missing this column.
--   * post-cutover — `traces` is the Distributed wrapper, which takes the ADD COLUMN as metadata only, so the column
--                    resolves on reads but no shard stores it.
--
-- Neither failure raises anything at migration time. The parity gates
-- (TracesSchemaParityPreCutoverTest / TracesSchemaParityPostCutoverTest) apply this fixture and assert they reject it,
-- which is what proves a non-conforming migration cannot merge.

-- It is otherwise written correctly -- IF NOT EXISTS, ON CLUSTER '{cluster}', a real rollback -- so the precondition
-- guard is the ONLY thing it is missing. That keeps the negative control sharp: when the gates reject it, they are
-- rejecting the absent guard and nothing else.
--
--changeset opik-7772-test-fixture:unguarded_traces_change
--comment: Deliberately un-guarded — the mistake the pattern exists to prevent
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS unguarded_column String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS unguarded_column;
