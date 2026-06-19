--liquibase formatted sql
--changeset petrot:000084_add_severity_to_agent_insights_issues
--comment: Promote severity to a first-class column on agent_insights_issues. Previously stored inside the metadata JSON blob on the details table; now persisted at the issue level and returned in all fetch endpoints.

ALTER TABLE agent_insights_issues
    ADD COLUMN severity ENUM ('critical','high','medium','low') NULL DEFAULT NULL AFTER status;

--rollback ALTER TABLE agent_insights_issues DROP COLUMN severity;
