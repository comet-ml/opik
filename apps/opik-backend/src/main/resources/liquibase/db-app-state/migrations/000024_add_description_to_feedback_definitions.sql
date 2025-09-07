--liquibase formatted sql
--changeset yariv:add_description_to_feedback_definitions
--comment: Add description field to feedback definitions for annotation queue configuration

ALTER TABLE feedback_definitions 
ADD COLUMN description TEXT DEFAULT NULL AFTER name;

--rollback ALTER TABLE feedback_definitions DROP COLUMN description;

