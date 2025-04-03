--liquibase formatted sql
--changeset idoberko2:000016_guardrails_span_type


ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans MODIFY COLUMN type Enum8(
    'unknown' = 0 ,
    'general' = 1,
    'tool' = 2,
    'llm' = 3,
    'guardrail' = 4);

