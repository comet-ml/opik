--liquibase formatted sql
--changeset aadereiko:000111_add_organization_type_to_cipx_trace_identities
--comment: Add organization_type to cipx_trace_identities (individual vs team/enterprise seat basis)

-- The OAuth profile carries organization.organization_type (individual vs team/enterprise). The proxy
-- already reads it but discarded it; persisting it lets AI-Spend price a seat correctly — the same
-- rate-limit tier (e.g. 'max_5x') is an individual Claude Max seat vs a Team Premium seat depending on
-- the org type. Additive column with a default; existing rows read the default (empty).
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS organization_type LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS organization_type;
