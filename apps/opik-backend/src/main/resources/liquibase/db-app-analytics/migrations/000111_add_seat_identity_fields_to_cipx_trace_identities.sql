--liquibase formatted sql
--changeset aadereiko:000111_add_seat_identity_fields_to_cipx_trace_identities
--comment: Add organization_type + seat_tier + billing_type to cipx_trace_identities (authoritative seat class + billing cadence)

-- The OAuth profile's organization object carries the three fields AI-Spend needs to price a seat
-- exactly, which the proxy already fetches but discarded:
--   organization_type - individual vs team/enterprise: the same rate-limit tier (e.g. 'max_5x') is an
--                       individual Claude Max seat vs a Team Premium seat depending on the org type.
--   seat_tier         - "standard"/"priority": names the Standard vs Premium class directly, instead
--                       of inferring it from the rate-limit tier.
--   billing_type      - "stripe_subscription" monthly / "stripe_subscription_contracted" | "manual"
--                       annual / "usage_based" API: selects the monthly vs annual seat rate.
-- Additive columns with a default; existing rows read the default (empty).
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS organization_type LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS seat_tier LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS billing_type LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS organization_type, DROP COLUMN IF EXISTS seat_tier, DROP COLUMN IF EXISTS billing_type;
