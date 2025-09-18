--liquibase formatted sql
--changeset andreicautisanu:000040_add_prompt_version_id_to_traces
--comment: Add prompt_version_id column to traces table to link traces with specific prompt versions

-- Add prompt_version_id column to traces table
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' 
ADD COLUMN prompt_version_id Nullable(FixedString(36)) DEFAULT NULL;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS prompt_version_id;

