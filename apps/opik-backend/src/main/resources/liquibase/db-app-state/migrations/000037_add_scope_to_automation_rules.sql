--liquibase formatted sql
--changeset thiaghora:000037_add_span_evaluator_types
--comment: Add span evaluator types (span_llm_as_judge and span_user_defined_metric_python) to support span-level online scoring rules

ALTER TABLE automation_rule_evaluators MODIFY COLUMN `type` ENUM('llm_as_judge', 'user_defined_metric_python', 'trace_thread_llm_as_judge', 'trace_thread_user_defined_metric_python', 'span_llm_as_judge', 'span_user_defined_metric_python') NOT NULL;

--rollback empty
