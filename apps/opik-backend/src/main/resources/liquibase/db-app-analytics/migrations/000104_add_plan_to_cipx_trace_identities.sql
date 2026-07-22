--liquibase formatted sql
--changeset boryst:000104_add_plan_to_cipx_trace_identities
--comment: Add payment-plan columns to cipx_trace_identities (subscription tier / billing mode / usage, OPIK-7288)

-- The proxy now resolves the Claude subscription tier and usage state from the OAuth profile +
-- /api/oauth/usage, and the billing mode (subscription vs api pricing) from the request auth header,
-- promoting them onto cipx.session.identity. Identity is trace-level, so these land here. Additive
-- columns with defaults; existing rows read the default (empty). Values:
--   billing_mode:      'subscription' | 'api' | ''
--   plan:              subscription tier, empty for api/free. 'max'/'pro' for individual accounts;
--                      team/enterprise accounts report their org rate-limit tier, e.g. 'max_5x'/'max_20x'.
--   plan_usage_status: 'within' | 'over' | ''  (subscription limit windows; empty for api/unfetched)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS billing_mode      LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS plan              LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS plan_usage_status LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS billing_mode, DROP COLUMN IF EXISTS plan, DROP COLUMN IF EXISTS plan_usage_status;

