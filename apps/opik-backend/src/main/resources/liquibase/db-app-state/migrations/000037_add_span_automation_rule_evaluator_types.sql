--liquibase formatted sql
--changeset thiagot:000037_add_span_automation_rule_evaluator_types

ALTER TABLE automation_rule_evaluators MODIFY COLUMN `type` ENUM('llm_as_judge', 'user_defined_metric_python', 'trace_thread_llm_as_judge', 'trace_thread_user_defined_metric_python', 'span_llm_as_judge') NOT NULL;

--rollback empty
