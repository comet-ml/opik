--liquibase formatted sql
--changeset andriid:000118_create_cipx_savings_facts
--comment: Daily realized/avoided savings facts per user and recommendation, written by the cost API measurement job

-- One row per (workspace, day, user, recommendation, quantity, tier, causal_link),
-- computed once per day by the cost API's measurement job from cipx_spend_blocks and the
-- recommendation apply log. The dollar amount is priced on the computation day, per
-- (model, billing tier) inside the job before aggregating to this grain: the money not
-- spent on a day was priced at that day's rates, so the figure stays stable through later
-- rate changes. A rate-table correction is a partition drop + recompute from the source
-- blocks.
--
--   quantity          - 'realized' (user had own spend before the change) or 'avoided'
--                       (spend never existed for this user; unit is imputed). The two are
--                       never summed; they aggregate as separate lines.
--   tier              - confidence of the number as it was computed: 'calculated' (both
--                       sides observed), 'attributed' (real bytes, own baseline),
--                       'modelled' (imputed unit). Derived by the engine from the
--                       evidence that actually fed the row, then frozen as provenance —
--                       never hand-written.
--   causal_link       - which producer created the change event the row measures:
--                       'performed' / 'configured' / 'detected'. Same derivation rule.
--                       Both carried on the row, and never collapsed into a blended
--                       label: the read hands each combination to the client as its own
--                       amount, so the UI's three-axis presentation reads from this table
--                       alone with no cross-store join.
--   harness           - the coding agent the change was applied for. Part of the key
--                       because the apply log is keyed by it too: the same recommendation
--                       applied for two harnesses is two applied things, and without this
--                       column their rows would share a key and one would be dropped.
--   recommendation_id - the catalog id whose applied change the row measures. An org total
--                       across several recommendations is not the sum of these rows —
--                       their populations can overlap — so it arrives with the union
--                       query that the second measured recommendation needs, not before.
--   alloc             - the prevented tokens (ingestion's chars-proportional allocation,
--                       summed across models and billing tiers); the "saved N MTok"
--                       display figure, not a pricing input.
--   usd               - the day-priced dollar amount.
--
-- Unlike the create-only cipx ingestion tables, a day's rows can be written more than
-- once: last_updated_at is the write timestamp and ReplacingMergeTree keeps the latest run,
-- so a run interrupted mid-insert, or a deliberate recompute after a measurement-code fix,
-- is idempotent. (The job itself skips a day that already carries rows, so this is a repair
-- path, not the daily one.) Reads must therefore collapse versions (FINAL or argMax) -
-- acceptable here because facts are orders of magnitude smaller than blocks.
-- Leads on workspace_id like the rest of the cipx family (the org's reserved
-- __ai_spend_<orgId>__ workspace, 1:1 with the org). Unlike the ingestion tables it does
-- not carry project_id: a fact is already aggregated across the workspace's projects,
-- and every read scopes by workspace and day.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_savings_facts ON CLUSTER '{cluster}'
(
    workspace_id      String,
    day               Date,
    user_uuid         String,
    recommendation_id String,
    harness           LowCardinality(String),
    quantity          LowCardinality(String),
    tier              LowCardinality(String),
    causal_link       LowCardinality(String),

    alloc             Float64,
    usd               Float64,

    last_updated_at   DateTime64(6, 'UTC') DEFAULT now64(6)
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_savings_facts',
    '{replica}',
    last_updated_at
)
PARTITION BY toYYYYMM(day)
ORDER BY (workspace_id, day, user_uuid, recommendation_id, harness, quantity, tier, causal_link);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_savings_facts ON CLUSTER '{cluster}';
