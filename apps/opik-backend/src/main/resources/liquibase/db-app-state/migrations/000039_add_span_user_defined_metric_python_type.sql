--liquibase formatted sql
--changeset thiaghora:000039_add_span_user_defined_metric_python_type
--comment: Add span_user_defined_metric_python evaluator type to support span-level Python metrics scoring rules

ALTER TABLE automation_rule_evaluators MODIFY COLUMN `type` ENUM('llm_as_judge', 'user_defined_metric_python', 'trace_thread_llm_as_judge', 'trace_thread_user_defined_metric_python', 'span_llm_as_judge', 'span_user_defined_metric_python') NOT NULL;

--rollback empty


