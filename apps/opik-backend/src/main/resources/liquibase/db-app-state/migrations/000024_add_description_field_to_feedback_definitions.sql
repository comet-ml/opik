--liquibase formatted sql
--changeset BorisTkachenko:000024_add_description_field_to_feedback_definitions
--comment: Add description field to feedback_definitions table for enhanced user annotation experience

ALTER TABLE feedback_definitions ADD COLUMN description VARCHAR(255) DEFAULT NULL;

--rollback ALTER TABLE feedback_definitions DROP COLUMN description;

