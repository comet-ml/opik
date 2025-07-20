--liquibase formatted sql
--changeset andrescrz:000019_add_automation_rule_evaluators_new_types

ALTER TABLE automation_rule_evaluators MODIFY COLUMN `type` ENUM('llm_as_judge', 'user_defined_metric_python', 'trace_thread_llm_as_judge', 'trace_thread_user_defined_metric_python') NOT NULL;

--rollback empty
