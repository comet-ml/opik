--liquibase formatted sql
--changeset idoberko2:000044_add_output_keys_to_traces_table
--comment: Add materialized output_keys column with types to traces table for performance optimization of JSON key and type extraction

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS output_keys Array(Tuple(key String, type String)) MATERIALIZED 
        arrayMap(
            key -> tuple(key, toString(JSONType(JSONExtractRaw(output, key)))),
            JSONExtractKeys(output)
        );

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS output_keys;


