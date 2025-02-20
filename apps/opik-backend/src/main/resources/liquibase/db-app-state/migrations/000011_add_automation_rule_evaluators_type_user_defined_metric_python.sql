--liquibase formatted sql
--changeset andrescrz:000011_add_automation_rule_evaluators_type_user_defined_metric_python

ALTER TABLE automation_rule_evaluators MODIFY COLUMN `type` ENUM('llm_as_judge', 'user_defined_metric_python') NOT NULL;
