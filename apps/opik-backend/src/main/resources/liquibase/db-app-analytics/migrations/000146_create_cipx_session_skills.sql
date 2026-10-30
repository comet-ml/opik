--liquibase formatted sql
--changeset petrot:000146_create_cipx_session_skills
--comment: One row per skill activation range of an analysed session, written by the two-lane session analysis

-- Lane 1 writes labels and bounds (turns, trace ids, origin, trigger, ordinal, judge_status, tokens, cost, is_deleted); lane 2 writes description and summary.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_skills ON CLUSTER '{cluster}'
(
    workspace_id    String,
    project_id      FixedString(36),
    session_id      String,
    slug            String,
    activation_turn UInt32,
    start_turn      UInt32,
    end_turn        UInt32,
    first_trace_id  FixedString(36),
    last_trace_id   FixedString(36),
    origin          LowCardinality(String),
    trigger         LowCardinality(String),
    ordinal         UInt16,
    judge_status    LowCardinality(String),
    description     String,
    tokens          UInt64,
    cost_usd        Decimal(38, 12),
    summary         String,
    analyzed_at     DateTime64(6, 'UTC') DEFAULT now64(6),
    is_deleted      UInt8 DEFAULT 0
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_session_skills',
    '{replica}',
    analyzed_at,
    is_deleted
)
ORDER BY (workspace_id, project_id, session_id, slug, activation_turn, start_turn);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_skills ON CLUSTER '{cluster}';
