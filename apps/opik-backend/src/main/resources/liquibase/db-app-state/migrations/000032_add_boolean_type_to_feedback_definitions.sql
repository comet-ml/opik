--liquibase formatted sql

--changeset BooleanFeedbackType:1
--comment: Add boolean type to feedback_definitions type enum
ALTER TABLE feedback_definitions MODIFY COLUMN type ENUM('numerical', 'categorical', 'boolean') NOT NULL;

