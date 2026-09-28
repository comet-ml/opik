-- runbook spans-local-v2-cutover — ROLLBACK stage B: swap the tables back (driven by ../rollback.sh --stage B)
-- The gate test SpansLocalV2CutoverTest reimplements this rollback inline; keep the two in step (see its Javadoc).
--
-- Use when the EXCHANGE ran but the wrap did NOT. `spans` holds the successor data and `spans_pre_cutover_backup`
-- parks the original. A SINGLE atomic multi-target RENAME rotates both names back: the successor (`spans`) is parked as
-- `spans_post_rollback_backup` (a retained backup, dropped only by finalize.sh — NOT the disposable `spans_local_v2`
-- shadow), and the original (`spans_pre_cutover_backup`) returns to `spans` (the name freed by the first clause) —
-- restoring the canonical state: spans = original live, spans_post_rollback_backup = successor parked. Gapless and with
-- no orphan risk: the multi-target RENAME is one statement, atomic PER HOST (all clauses apply or none), so on any node
-- there is no window where a partial failure strands the successor under a wrong name — the flaw of a separate
-- EXCHANGE + RENAME, which has that window even on a single host. ACROSS the shard's replicas (production is multi-replica)
-- ON CLUSTER runs synchronously — the client blocks until every reachable replica applies it, or throws naming a laggard
-- that then converges via the DDL queue — so there is no durable mixed topology, only a sub-second cross-replica skew as
-- it propagates, during which a read on a not-yet-renamed replica sees the old `spans`. That is the same accepted
-- ON CLUSTER skew as the wrap (nil on a single replica); run this in the rollback maintenance moment (see the runbook).
-- Non-destructive.
--
-- STAGE B IS THE EXPECTED ROLLBACK ON SPANS, not the exotic one — more so than on traces. The wrap is deferred by
-- default there, and on spans this runbook defers it further still as a scope decision (the flag and its
-- clickhouse-spans-topology readiness check have both shipped, OPIK-7799 and OPIK-8376), so the post-EXCHANGE resting
-- state is where this window rests and stage B is what reverses it. Provision
-- its grants with the forward set rather than treating them as an optional extra (README, "Required privileges").
--
-- rollback.sh runs the reverse-replay (000004_rollback_reverse_replay.sql) right after this so deletes since
-- cutover_start do not resurrect. rollback.sh asserts the post-EXCHANGE, pre-wrap topology (spans = successor schema,
-- not Distributed) before running it.
SET log_comment = 'spans_local_v2_rollback:stage_b';
RENAME TABLE
    ${ANALYTICS_DB_DATABASE_NAME}.spans TO ${ANALYTICS_DB_DATABASE_NAME}.spans_post_rollback_backup,
    ${ANALYTICS_DB_DATABASE_NAME}.spans_pre_cutover_backup TO ${ANALYTICS_DB_DATABASE_NAME}.spans
    ON CLUSTER '{cluster}';
