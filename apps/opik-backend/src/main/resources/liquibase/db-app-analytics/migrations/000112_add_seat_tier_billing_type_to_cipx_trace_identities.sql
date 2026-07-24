--liquibase formatted sql
--changeset aadereiko:000112_add_seat_tier_billing_type_to_cipx_trace_identities
--comment: Add seat_tier + billing_type to cipx_trace_identities (authoritative seat class + billing cadence)

-- The OAuth profile's organization object carries seat_tier ("standard"/"priority") and
-- billing_type ("stripe_subscription" monthly / "stripe_subscription_contracted" | "manual" annual /
-- "usage_based" API). The proxy already fetches this response but only mapped a subset. Persisting these
-- lets AI-Spend price a seat exactly — seat_tier names the Standard vs Premium class directly (instead of
-- inferring it from the rate-limit tier), and billing_type selects the monthly vs annual seat rate.
-- Additive columns with a default; existing rows read the default (empty).
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS seat_tier LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS billing_type LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS seat_tier, DROP COLUMN IF EXISTS billing_type;
