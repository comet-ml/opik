--liquibase formatted sql
--changeset andriid:000121_create_cipx_savings_facts
--comment: Daily realized/avoided savings facts per user and recommendation, written by the cost API measurement job

-- Daily savings facts, written by the cost API measurement job from cipx_spend_blocks and
-- the recommendation apply log. Amounts are priced on the computation day, so a figure
-- stays stable through later rate changes.
--
-- quantity, tier and causal_link are key columns because their values are never summed
-- together: a measured saving and an imputed one reach the client as separate amounts.
-- Reads collapse versions with FINAL or argMax, since a repaired day is re-inserted.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_savings_facts ON CLUSTER '{cluster}'
(
    workspace_id      String,
    project_id        FixedString(36),
    day               Date,
    user_uuid         String,
    recommendation_id String,
    harness           LowCardinality(String),
    quantity          LowCardinality(String),
    tier              LowCardinality(String),
    causal_link       LowCardinality(String),

    alloc             Float64,
    usd               Decimal(38, 12),

    last_updated_at   DateTime64(6, 'UTC') DEFAULT now64(6)
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_savings_facts',
    '{replica}',
    last_updated_at
)
PARTITION BY toYYYYMM(day)
ORDER BY (workspace_id, project_id, day, user_uuid, recommendation_id, harness, quantity,
          tier, causal_link);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_savings_facts ON CLUSTER '{cluster}';
