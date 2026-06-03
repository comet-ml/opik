--liquibase formatted sql
--changeset yarivhashai:000086_fix_automation_rule_evaluator_logs_level_enum
--comment: Fix level enum typo 'WARM' -> 'WARN' on automation_rule_evaluator_logs (OPIK-6781). The column was created in 000010/000017 as Enum8(...,'WARM'=3,...) but the application emits 'WARN', so every WARN-level log insert was rejected (UNKNOWN_ELEMENT_OF_ENUM / async-insert parse failures). Renaming the label for value 3 is a metadata-only change; the on-disk Int8 representation and all existing values are preserved.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs ON CLUSTER '{cluster}'
    MODIFY COLUMN level Enum8('TRACE' = 0, 'DEBUG' = 1, 'INFO' = 2, 'WARN' = 3, 'ERROR' = 4);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs ON CLUSTER '{cluster}' MODIFY COLUMN level Enum8('TRACE' = 0, 'DEBUG' = 1, 'INFO' = 2, 'WARM' = 3, 'ERROR' = 4);
