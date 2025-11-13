--liquibase formatted sql

--changeset yariv:000032_add_boolean_type_to_feedback_definitions
--comment: Add boolean type to feedback_definitions type enum
ALTER TABLE feedback_definitions MODIFY COLUMN type ENUM('numerical', 'categorical', 'boolean') NOT NULL;

--rollback empty
