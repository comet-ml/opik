--liquibase formatted sql
--changeset aadereiko:000119_add_agent_link_rollup_to_cipx_trace_identities
--comment: Persist the session-grain subagent-dispatch link counters and the producing cipx build on cipx_trace_identities
--
-- The companion to cipx_spends.link_failure_reason (migration 000118) at the other grain. The
-- per-call reason explains the failures cipx can diagnose; these counters are the only place the
-- worst failure mode is visible at all. A subagent whose dispatch was never captured is
-- indistinguishable from a main-loop turn on the wire, so no call carries a reason -- only a missing
-- increment here reveals it. cipx already ships all four on metadata.cipx.session; none reached a
-- column, so downstream cannot tell cipx correctly refusing to guess from cipx losing dispatches.
--
--   agents_dispatched  subagent dispatches OBSERVED in this session.
--   agents_linked      of those, how many a later call tied back to their dispatch.
--   agents_ambiguous   of those, how many cipx declined to attribute because two or more dispatches
--                      in the session shared a byte-identical prompt and nothing on the wire
--                      separated their calls. This is cipx fail-closing on purpose, NOT a defect.
--   cipx_version       the build-stamped daemon version that wrote the envelope ("0.0.56", "dev" on
--                      an unstamped local build). Carried because every number above is only as
--                      trustworthy as the binary that produced it: "link rate collapsed" and "half
--                      the fleet runs a build from before the fix" are the same rows without it.
--
-- HOW TO READ THESE -- four constraints, all load-bearing:
--
-- 1. SESSION-CUMULATIVE, RE-STAMPED PER TRACE. The counters are session running totals, and cipx
--    re-stamps the current value on EVERY trace upsert of that session. cipx_trace_identities is
--    keyed per trace, so a session with N traces holds N rows carrying N successive snapshots.
--    Aggregate with max() per session_id -- NEVER sum(), which multiplies the totals by the trace
--    count. (Same shape as the session-grain columns already on this table: session_id, harness,
--    plan, billing_mode. New only in being monotonically increasing.)
-- 2. CLAMP missed AT ZERO. The three counters are disjoint at dispatch grain, which normally makes
--    missed = dispatched - ambiguous - linked non-negative. But the producer's dispatch log is
--    capped per session (512) and evicts oldest-first, and evicting one of a colliding pair makes
--    the survivor look unique again -- so past that cap a dispatch counted ambiguous can later link
--    and missed can go negative. Bounded and known; clamp rather than trusting it.
-- 3. GUARD THE ZERO DENOMINATOR. rate = linked / (dispatched - ambiguous) is UNDEFINED, not zero,
--    when the denominator is 0 (a session that dispatched no agents, or only ambiguous ones). A
--    dashboard that reports 0% there invents a link failure out of an idle session.
-- 4. ZERO IS NOT "UNKNOWN". The producer omits these when empty and absence reads as zero, so a
--    default of 0 is faithful for a daemon that dispatched nothing. A daemon too old to emit them at
--    all is told apart by cipx_version / schema_version, not by the zeros.
--
-- Additive columns with defaults; pre-existing rows read 0 / '' per the above.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS agents_dispatched UInt32                 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS agents_linked     UInt32                 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS agents_ambiguous  UInt32                 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cipx_version      LowCardinality(String) DEFAULT '';

-- Rollback is NOT safe to run while a backend carrying these columns is live: CipxTraceIdentityDAO
-- names all four in a single INSERT, so dropping them makes every cipx identity upsert fail as a
-- whole batch, and CostIntelligenceIngestionListener logs-and-swallows that error with no retry and
-- no dead letter. Roll the backend back first, then the schema.
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS agents_dispatched, DROP COLUMN IF EXISTS agents_linked, DROP COLUMN IF EXISTS agents_ambiguous, DROP COLUMN IF EXISTS cipx_version;
