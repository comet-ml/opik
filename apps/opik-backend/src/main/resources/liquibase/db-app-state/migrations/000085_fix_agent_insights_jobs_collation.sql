--liquibase formatted sql
--changeset aadereiko:000085_fix_agent_insights_jobs_collation
--comment: 000083 created agent_insights_jobs with the utf8mb4 default collation (utf8mb4_0900_ai_ci), which
--comment: diverges from the rest of the schema (utf8mb4_unicode_ci) and breaks JOINs against projects with an
--comment: "illegal mix of collations" error. Align the table with the schema-wide collation.

ALTER TABLE agent_insights_jobs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

--rollback ALTER TABLE agent_insights_jobs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

