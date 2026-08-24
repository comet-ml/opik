--liquibase formatted sql
--changeset aadereiko:000119_add_agent_link_rollup_to_cipx_trace_identities
--comment: Persist the session-grain subagent-dispatch link counters and the producing cipx build on cipx_trace_identities
--
-- The companion to cipx_spends.link_failure_reason (migration 000120) at the other
-- grain. The per-call reason explains the failures cipx can diagnose; these counters
-- are the only place the worst one is visible at all, because a subagent whose
-- dispatch was never captured is indistinguishable from a main-loop turn and carries
-- no reason. cipx already ships all four on metadata.cipx.session.
--
--   agents_dispatched  subagent dispatches OBSERVED in this session.
--   agents_linked      of those, how many a later call tied back to its dispatch.
--   agents_ambiguous   of those, how many cipx declined to attribute because two
--                      dispatches shared a byte-identical prompt. Fail-closed on
--                      purpose, NOT a defect.
--   cipx_version       daemon build that wrote the envelope. "link rate collapsed"
--                      and "half the fleet predates the fix" are the same rows
--                      without it.
--
-- Four rules for reading them, all load-bearing:
--
-- 1. Aggregate with max() per session_id, NEVER sum(). They are session running
--    totals re-stamped on every trace upsert, so a session with N traces holds N
--    successive snapshots and sum() multiplies by the trace count.
-- 2. Clamp missed = dispatched - ambiguous - linked at zero. The producer's dispatch
--    log is capped at 512 per session and evicts oldest-first, so past the cap a
--    dispatch counted ambiguous can later link and missed can go negative.
-- 3. Guard the zero denominator: rate = linked / (dispatched - ambiguous) is
--    UNDEFINED, not zero. Reporting 0% invents a failure out of an idle session.
-- 4. Zero is not "unknown". Absence reads as zero, which is faithful for a daemon
--    that dispatched nothing; one too old to emit them is told apart by
--    cipx_version / schema_version.
--
-- Additive columns with defaults; pre-existing rows read 0 / '' per the above.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS agents_dispatched UInt32                 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS agents_linked     UInt32                 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS agents_ambiguous  UInt32                 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cipx_version      LowCardinality(String) DEFAULT '';

-- Roll the backend back BEFORE this: CipxTraceIdentityDAO names all four in one
-- INSERT, so dropping them fails every cipx identity upsert as a whole batch, and
-- the listener logs-and-swallows that with no retry and no dead letter.
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS agents_dispatched, DROP COLUMN IF EXISTS agents_linked, DROP COLUMN IF EXISTS agents_ambiguous, DROP COLUMN IF EXISTS cipx_version;
