--liquibase formatted sql
--changeset o3:000017_atomic_agents_span_type

-- Add new enum values and schema columns for Atomic Agents integration

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans MODIFY COLUMN type Enum8(
    'unknown' = 0 ,
    'general' = 1,
    'tool' = 2,
    'llm' = 3,
    'guardrail' = 4,
    'atomic_agent' = 5,
    'atomic_tool' = 6,
    'atomic_chain' = 7
);

-- Optional JSON schema columns
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans
    ADD COLUMN IF NOT EXISTS atomic_input_schema Nullable(String) AFTER metadata,
    ADD COLUMN IF NOT EXISTS atomic_output_schema Nullable(String) AFTER atomic_input_schema,
    ADD COLUMN IF NOT EXISTS atomic_tool_chain Array(String) AFTER atomic_output_schema;

-- Index for fast filtering by input schema existence
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans
    ADD INDEX IF NOT EXISTS idx_atomic_input_schema atomic_input_schema TYPE bloom_filter GRANULARITY 64; 