--liquibase formatted sql
--changeset claude:000046_create_daily_trace_stats_materialized_view
--comment: Create materialized view for daily trace statistics to improve dashboard performance

-- Create a materialized view that aggregates trace statistics by day
-- This significantly improves performance for dashboard queries showing daily metrics
CREATE MATERIALIZED VIEW IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.daily_trace_stats_mv ON CLUSTER '{cluster}'
ENGINE = SummingMergeTree()
ORDER BY (workspace_id, project_id, date)
POPULATE
AS SELECT
    workspace_id,
    project_id,
    toDate(start_time) as date,
    count() as trace_count,
    countIf(end_time IS NOT NULL) as completed_trace_count,
    countIf(error_info IS NOT NULL AND error_info != '{}') as error_trace_count,
    avg(if(end_time IS NOT NULL, dateDiff('millisecond', start_time, end_time), 0)) as avg_duration_ms,
    quantile(0.50)(if(end_time IS NOT NULL, dateDiff('millisecond', start_time, end_time), 0)) as p50_duration_ms,
    quantile(0.95)(if(end_time IS NOT NULL, dateDiff('millisecond', start_time, end_time), 0)) as p95_duration_ms,
    quantile(0.99)(if(end_time IS NOT NULL, dateDiff('millisecond', start_time, end_time), 0)) as p99_duration_ms,
    max(if(end_time IS NOT NULL, dateDiff('millisecond', start_time, end_time), 0)) as max_duration_ms,
    uniqExact(thread_id) as unique_threads,
    toStartOfHour(start_time) as hour
FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
GROUP BY workspace_id, project_id, date, hour;

-- Create an hourly trace statistics view for more granular analysis
CREATE MATERIALIZED VIEW IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.hourly_trace_stats_mv ON CLUSTER '{cluster}'
ENGINE = SummingMergeTree()
ORDER BY (workspace_id, project_id, hour)
POPULATE
AS SELECT
    workspace_id,
    project_id,
    toStartOfHour(start_time) as hour,
    count() as trace_count,
    countIf(end_time IS NOT NULL) as completed_trace_count,
    countIf(error_info IS NOT NULL AND error_info != '{}') as error_trace_count,
    avg(if(end_time IS NOT NULL, dateDiff('millisecond', start_time, end_time), 0)) as avg_duration_ms,
    quantile(0.95)(if(end_time IS NOT NULL, dateDiff('millisecond', start_time, end_time), 0)) as p95_duration_ms
FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
GROUP BY workspace_id, project_id, hour;

-- Create a view for project-level summary statistics
CREATE MATERIALIZED VIEW IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.project_summary_stats_mv ON CLUSTER '{cluster}'
ENGINE = ReplacingMergeTree()
ORDER BY (workspace_id, project_id)
POPULATE
AS SELECT
    workspace_id,
    project_id,
    count() as total_traces,
    max(start_time) as last_trace_time,
    min(start_time) as first_trace_time,
    uniqExact(thread_id) as total_unique_threads,
    countIf(error_info IS NOT NULL AND error_info != '{}') as total_errors,
    now() as updated_at
FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
GROUP BY workspace_id, project_id;

--rollback DROP VIEW IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.daily_trace_stats_mv ON CLUSTER '{cluster}';
--rollback DROP VIEW IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.hourly_trace_stats_mv ON CLUSTER '{cluster}';
--rollback DROP VIEW IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.project_summary_stats_mv ON CLUSTER '{cluster}';
